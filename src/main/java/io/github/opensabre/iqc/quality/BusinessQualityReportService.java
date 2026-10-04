package io.github.opensabre.iqc.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.result.BatchResultQueryService;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.scheme.InspectionSchemeService;
import io.github.opensabre.iqc.scheme.SchemeDefinition;
import io.github.opensabre.iqc.scheme.SchemeResultEvaluator;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.dao.TaskExecutionMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.task.model.TaskExecution;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded business statistics separated by frozen standard and result source; no legacy message averages. */
@Service
@RequiredArgsConstructor
public class BusinessQualityReportService {
    private final InspectionTaskMapper tasks;
    private final ConversationInspectionResultMapper results;
    private final BusinessItemReviewService reviews;
    private final IqcDataScope scope;
    private final ObjectMapper mapper;
    private final TaskExecutionMapper executions;

    public record ScoreSummary(int resultCount, int finalCount, int pendingCount, int notApplicableCount,
                               int qualifiedCount, BigDecimal averageScore, BigDecimal qualifiedRate) { }
    /** Failure rate uses only resolved applicable decisions; unresolved and absent results are never passes. */
    public record ItemSummary(int resultCount, int passCount, int failCount, int notApplicableCount,
                              int notEvaluatedCount, int reviewRequiredCount, int errorCount, BigDecimal failureRate) { }
    public record ItemGroup(String itemCode, String name, boolean scored, Integer points, boolean veto,
                            ItemSummary machine, ItemSummary reviewed) { }
    public record PolicyGroup(String groupKey, String schemeId, String versionNo, String draftRevision, String kind,
                              String mode, int baseScore, int passingScore, List<String> taskIds, int conversationCount,
                              List<String> executionIds, int missingResultCount, int missingReviewCount, int pendingReviewCount,
                              ScoreSummary machine, ScoreSummary reviewed, List<ItemGroup> items) { }
    public record Report(String scope, String runPolicy, int taskCount, int conversationCount, List<PolicyGroup> groups) { }
    public enum RunPolicy { SELECTED_RUNS, FIRST_PER_CONVERSATION, LATEST_PER_CONVERSATION }

    /** Explicit task selection only; authorize and bound all scopes before reading any result data. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report report(List<String> taskIds) {
        return report(taskIds, null, RunPolicy.SELECTED_RUNS.name());
    }

    /** Explicit execution IDs select exact attempts; first/latest policies deduplicate repeated conversations per frozen standard. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report report(List<String> taskIds, String executionSelectionJson, String requestedRunPolicy) {
        if (taskIds == null || taskIds.isEmpty() || taskIds.size() > 20
                || taskIds.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 64))
            throw IqcException.invalidArgument("业务统计必须选择 1–20 个有效任务");
        RunPolicy runPolicy;
        try { runPolicy = RunPolicy.valueOf(requestedRunPolicy == null ? RunPolicy.SELECTED_RUNS.name() : requestedRunPolicy); }
        catch (IllegalArgumentException invalid) { throw IqcException.invalidArgument("不支持的运行统计口径"); }
        var runSelections = readExecutionSelections(executionSelectionJson, taskIds.stream().distinct().toList());
        if (runPolicy != RunPolicy.SELECTED_RUNS && !runSelections.isEmpty())
            throw IqcException.invalidArgument("首次/最新口径不能同时指定单个执行实例");
        var selected = new ArrayList<Selection>();
        int count = 0; long itemCount = 0;
        for (String id : taskIds.stream().distinct().toList()) {
            var task = tasks.selectById(id);
            if (task == null || !scope.canView(task.getCreatedBy(), task.getOwnerGroupId())) throw IqcException.accessDenied("无权统计所选任务");
            String executionId = runSelections.get(id);
            if (executionId != null && !"LATEST".equals(executionId)) {
                TaskExecution execution = executions.selectById(executionId);
                if (execution == null || !id.equals(execution.getTaskId())) throw IqcException.invalidArgument("所选执行实例不属于对应任务");
            } else executionId = null;
            try {
                var snapshot = mapper.readTree(task.getRuleSnapshotJson());
                var definition = SchemeResultEvaluator.definition(snapshot, mapper);
                if (definition == null) throw IqcException.invalidArgument("业务统计不支持旧消息任务");
                var conversations = BatchResultQueryService.exportConversationIds(task, mapper);
                count += conversations.size(); itemCount += (long) conversations.size() * definition.items().size();
                if (count > 500 || itemCount > 50_000) throw IqcException.invalidArgument("业务统计最多 500 个任务会话、50000 个质检项");
                var marker = snapshot.path("schemeSnapshot");
                // Conservative grouping: frozen release content, identity and trial/published version must all agree.
                String key = InspectionSchemeService.contentHash(mapper.writeValueAsString(List.of(
                        marker.path("schemeId"), marker.path("versionNo"), marker.path("draftRevision"), marker.path("kind"), marker.path("release"))));
                selected.add(new Selection(task, conversations, definition, marker, key, executionId));
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw IqcException.invalidState("任务方案快照损坏"); }
        }
        var candidates = new ArrayList<RunCandidate>();
        for (var selection : selected) {
            if (runPolicy == RunPolicy.SELECTED_RUNS) {
                for (String conversation : selection.conversations()) {
                    var result = selection.executionId() == null
                            ? results.selectLatestForTaskConversation(selection.task().getId(), conversation)
                            : results.selectForTaskExecutionConversation(selection.task().getId(), selection.executionId(), conversation);
                    candidates.add(runCandidate(selection, conversation, result));
                }
                continue;
            }

            if (selection.conversations().isEmpty()) continue;
            boolean latest = runPolicy == RunPolicy.LATEST_PER_CONVERSATION;
            var policyRuns = results.selectPolicyRunsForTaskConversations(
                    selection.task().getId(), selection.conversations(), latest);
            var runsByConversation = new LinkedHashMap<String, ConversationInspectionResult>();
            policyRuns.forEach(result -> runsByConversation.put(result.getConversationId(), result));
            for (String conversation : selection.conversations()) {
                candidates.add(runCandidate(selection, conversation, runsByConversation.get(conversation)));
            }
        }
        candidates = applyRunPolicy(candidates, runPolicy);
        var groups = new LinkedHashMap<String, Group>();
        for (var candidate : candidates) {
            var selection = candidate.selection();
            var group = groups.computeIfAbsent(selection.key(), ignored -> new Group(selection));
            group.taskIds.add(selection.task().getId());
            group.conversations++;
            var result = candidate.result();
            if (result == null) { group.missingResult++; group.missingReview++; continue; }
            if (result.getExecutionId() != null) group.executionIds.add(result.getExecutionId());
            var machine = reviews.validatedMachineEvaluation(result, selection.task());
            group.machine.add(machine.scoring());
            group.addItems(machine, false);
            var completed = reviews.latest(result.getId(), true);
            var latest = reviews.latest(result.getId(), false);
            if (latest != null && "PENDING".equals(latest.getStatus())) group.pendingReview++;
            if (completed == null) group.missingReview++;
            else {
                var reviewed = reviews.validatedProjection(completed, result, selection.task()).reviewed();
                group.reviewed.add(reviewed.scoring());
                group.addItems(reviewed, true);
            }
        }
        return new Report("SELECTED_TASK_RUNS", runPolicy.name(), selected.size(), candidates.size(), groups.values().stream().map(Group::view).toList());
    }

    private Map<String, String> readExecutionSelections(String json, List<String> taskIds) {
        if (json == null || json.isBlank()) return java.util.Map.of();
        try {
            var values = mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, String>>() { });
            if (values.size() > 20 || !taskIds.containsAll(values.keySet())
                    || values.values().stream().anyMatch(value -> value == null
                    || !("LATEST".equals(value) || value.matches("[A-Za-z0-9_-]{1,64}"))))
                throw IqcException.invalidArgument("任务执行选择无效");
            return values;
        } catch (com.fasterxml.jackson.core.JsonProcessingException | NullPointerException invalid) {
            throw IqcException.invalidArgument("任务执行选择格式无效");
        }
    }

    private ArrayList<RunCandidate> applyRunPolicy(List<RunCandidate> candidates, RunPolicy policy) {
        if (policy == RunPolicy.SELECTED_RUNS) return new ArrayList<>(candidates);
        var byConversation = new LinkedHashMap<String, List<RunCandidate>>();
        for (var candidate : candidates) {
            String key = candidate.selection().key() + "\u0000" + candidate.conversationId();
            byConversation.computeIfAbsent(key, ignored -> new ArrayList<>()).add(candidate);
        }
        var chosen = new ArrayList<RunCandidate>();
        for (var values : byConversation.values()) {
            var withResults = values.stream().filter(candidate -> candidate.result() != null).toList();
            if (withResults.isEmpty()) {
                chosen.add(values.stream().min(java.util.Comparator.comparing(candidate -> candidate.selection().task().getId())).orElseThrow());
                continue;
            }
            var selected = withResults.stream().min(this::compareRunChronology).orElseThrow();
            if (policy == RunPolicy.LATEST_PER_CONVERSATION)
                selected = withResults.stream().max(this::compareRunChronology).orElseThrow();
            chosen.add(selected);
        }
        return chosen;
    }

    private RunCandidate runCandidate(Selection selection, String conversation, ConversationInspectionResult result) {
        java.util.Date time = result != null && result.getCreatedTime() != null ? result.getCreatedTime()
                : selection.task().getCreatedTime();
        return new RunCandidate(selection, conversation, result, time);
    }

    /** Each task contributes at most one run per conversation; timestamps order runs across tasks. */
    private int compareRunChronology(RunCandidate left, RunCandidate right) {
        String leftTaskId = left.selection().task().getId(), rightTaskId = right.selection().task().getId();
        int compared;
        compared = java.util.Comparator.nullsFirst(java.util.Date::compareTo).compare(left.effectiveTime(), right.effectiveTime());
        if (compared != 0) return compared;
        compared = leftTaskId.compareTo(rightTaskId);
        if (compared != 0) return compared;
        return java.util.Comparator.nullsFirst(String::compareTo).compare(
                left.result() == null ? null : left.result().getId(), right.result() == null ? null : right.result().getId());
    }

    private record Selection(InspectionTask task, List<String> conversations, SchemeDefinition definition,
                             JsonNode marker, String key, String executionId) { }
    private record RunCandidate(Selection selection, String conversationId, ConversationInspectionResult result,
                                java.util.Date effectiveTime) { }
    private static final class Group {
        final Selection selection; final List<String> taskIds = new ArrayList<>();
        final java.util.Set<String> executionIds = new java.util.LinkedHashSet<>();
        final Scores machine = new Scores(), reviewed = new Scores();
        final java.util.Map<String, ItemCounts> machineItems = new LinkedHashMap<>(), reviewedItems = new LinkedHashMap<>();
        int conversations, missingResult, missingReview, pendingReview;
        Group(Selection selection) {
            this.selection = selection;
            selection.definition().items().forEach(item -> {
                machineItems.put(item.itemCode(), new ItemCounts());
                reviewedItems.put(item.itemCode(), new ItemCounts());
            });
        }
        void addItems(SchemeResultEvaluator.Evaluation evaluation, boolean human) {
            var counts = human ? reviewedItems : machineItems;
            evaluation.items().forEach(item -> counts.get(item.itemCode()).add(item.status()));
        }
        PolicyGroup view() {
            var marker = selection.marker(); var policy = selection.definition().scoring();
            return new PolicyGroup(selection.key(), marker.path("schemeId").asText(""), marker.path("versionNo").asText(""),
                    marker.path("draftRevision").asText(""), marker.path("kind").asText(""), policy.mode().name(),
                    policy.baseScore(), policy.passingScore(),
                    List.copyOf(new java.util.LinkedHashSet<>(taskIds)), conversations, List.copyOf(executionIds),
                    missingResult, missingReview, pendingReview, machine.view(), reviewed.view(),
                    selection.definition().items().stream().map(item -> {
                        var scoring = policy.items().stream().filter(value -> value.itemCode().equals(item.itemCode())).findFirst().orElse(null);
                        return new ItemGroup(item.itemCode(), item.name(), scoring != null,
                                scoring == null ? null : scoring.points(), scoring != null && scoring.veto(),
                                machineItems.get(item.itemCode()).view(), reviewedItems.get(item.itemCode()).view());
                    }).toList());
        }
    }
    private static final class ItemCounts {
        int count, pass, fail, na, notEvaluated, reviewRequired, errors;
        void add(InspectionScoring.ItemStatus status) {
            count++;
            switch (status) {
                case PASS -> pass++;
                case FAIL -> fail++;
                case NOT_APPLICABLE -> na++;
                case NOT_EVALUATED -> notEvaluated++;
                case REVIEW_REQUIRED -> reviewRequired++;
                case ERROR -> errors++;
            }
        }
        ItemSummary view() {
            int resolved = pass + fail;
            return new ItemSummary(count, pass, fail, na, notEvaluated, reviewRequired, errors,
                    resolved == 0 ? null : BigDecimal.valueOf(fail * 100L).divide(BigDecimal.valueOf(resolved), 2, RoundingMode.HALF_UP));
        }
    }
    private static final class Scores {
        int count, finals, pending, na, qualified; BigDecimal sum = BigDecimal.ZERO;
        void add(InspectionScoring.Result score) {
            count++;
            switch (score.scoreStatus()) {
                case FINAL -> { finals++; sum = sum.add(score.finalScore()); if (score.conclusion() == InspectionScoring.Conclusion.QUALIFIED) qualified++; }
                case PENDING -> pending++;
                case NOT_APPLICABLE -> na++;
            }
        }
        ScoreSummary view() {
            return new ScoreSummary(count, finals, pending, na, qualified,
                    finals == 0 ? null : sum.divide(BigDecimal.valueOf(finals), 2, RoundingMode.HALF_UP),
                    finals == 0 ? null : BigDecimal.valueOf(qualified * 100L).divide(BigDecimal.valueOf(finals), 2, RoundingMode.HALF_UP));
        }
    }
}
