package io.github.opensabre.iqc.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.result.BatchResultQueryService;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.scheme.InspectionSchemeService;
import io.github.opensabre.iqc.scheme.SchemeResultEvaluator;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.dao.TaskExecutionMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.task.model.TaskExecution;
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
public class BusinessQualityReportService {
    private final InspectionTaskMapper tasks;
    private final ConversationInspectionResultMapper results;
    private final BusinessItemReviewService reviews;
    private final IqcDataScope scope;
    private final ObjectMapper mapper;
    private final TaskExecutionMapper executions;

    private final io.github.opensabre.iqc.pipelineasset.PipelineRolloutExecutionGuard rolloutGuard;
    private final io.github.opensabre.iqc.result.llm.gateway.GatewayCallService modelCalls;
    private final io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive;
    @org.springframework.beans.factory.annotation.Autowired
    public BusinessQualityReportService(InspectionTaskMapper tasks,ConversationInspectionResultMapper results,
            BusinessItemReviewService reviews,IqcDataScope scope,ObjectMapper mapper,TaskExecutionMapper executions,
            io.github.opensabre.iqc.pipelineasset.PipelineRolloutExecutionGuard rolloutGuard,
            io.github.opensabre.iqc.result.llm.gateway.GatewayCallService modelCalls,io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive){
        this.tasks=tasks;this.results=results;this.reviews=reviews;this.scope=scope;this.mapper=mapper;this.executions=executions;this.rolloutGuard=rolloutGuard;this.modelCalls=modelCalls;this.sensitive=sensitive;
    }
    public BusinessQualityReportService(InspectionTaskMapper tasks,ConversationInspectionResultMapper results,
            BusinessItemReviewService reviews,IqcDataScope scope,ObjectMapper mapper,TaskExecutionMapper executions,
            io.github.opensabre.iqc.pipelineasset.PipelineRolloutExecutionGuard rolloutGuard){
        this(tasks,results,reviews,scope,mapper,executions,rolloutGuard,null,null);
    }
    public BusinessQualityReportService(InspectionTaskMapper tasks,ConversationInspectionResultMapper results,
            BusinessItemReviewService reviews,IqcDataScope scope,ObjectMapper mapper,TaskExecutionMapper executions){
        this(tasks,results,reviews,scope,mapper,executions,null);
    }
    /** Public provenance only; no cohort hash, actor identity, selector definition or raw request key. */
    public record RolloutAttribution(String schemaVersion,String revisionId,int revisionNo,String revisionHash,String policyHash,
            String side,String state,int candidateBasisPoints,int baselineVersionNo,int candidateVersionNo,
            int selectedVersionNo,String selectedReleaseHash) { }
    public record ModelUsageSummary(String schemaVersion,int selectedResults,int boundResults,int unboundResults,
            io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.CostSummary costs,
            io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.CallLatencySummary latency,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.CallTokenSummary tokens) {
        public ModelUsageSummary(String schemaVersion,int selectedResults,int boundResults,int unboundResults,
                io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.CostSummary costs,
                io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.CallLatencySummary latency){
            this(schemaVersion,selectedResults,boundResults,unboundResults,costs,latency,null);
        }
    }
    public record ManualScoreSummary(int appliedCount,int staleCount,BigDecimal averageOverrideScore,
                                     int effectiveNumericCount,BigDecimal averageEffectiveScore) { }
    public record ScoreSummary(int resultCount, int finalCount, int pendingCount, int notApplicableCount,
                               int qualifiedCount, BigDecimal averageScore, BigDecimal qualifiedRate,
                               @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                               ManualScoreSummary manualOverrides) {
        public ScoreSummary(int resultCount,int finalCount,int pendingCount,int notApplicableCount,int qualifiedCount,
                BigDecimal averageScore,BigDecimal qualifiedRate){this(resultCount,finalCount,pendingCount,notApplicableCount,qualifiedCount,averageScore,qualifiedRate,null);}
    }
    /** Failure rate uses only resolved applicable decisions; unresolved and absent results are never passes. */
    public record ItemSummary(int resultCount, int passCount, int failCount, int notApplicableCount,
                              int notEvaluatedCount, int reviewRequiredCount, int errorCount, BigDecimal failureRate) { }
    public record ItemGroup(String itemCode, String name, boolean scored, Integer points, boolean veto,
                            ItemSummary machine, ItemSummary reviewed) { }
    public record ReviewChangeSummary(String schemaVersion,String scope,int reviewedResults,int modifiedResults,
                                      int itemOrScoreChangedResults,int findingChangedResults,int effectiveNumericScoreChangedResults,
                                      BigDecimal modificationRate,BigDecimal reviewCoverage) { }
    public record PolicyGroup(String groupKey, String schemeId, String versionNo, String draftRevision, String kind,
                              String mode, int baseScore, int passingScore, List<String> taskIds, int conversationCount,
                              List<String> executionIds, int missingResultCount, int missingReviewCount, int pendingReviewCount,
                              ScoreSummary machine, ScoreSummary reviewed, List<ItemGroup> items,
                              @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                              String pipelineId,
                              @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                              ReviewChangeSummary reviewChanges,
                              @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                              RolloutAttribution rollout,
                              @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                              ModelUsageSummary modelUsage) {
        public PolicyGroup(String groupKey,String schemeId,String versionNo,String draftRevision,String kind,String mode,int baseScore,int passingScore,
                List<String> taskIds,int conversationCount,List<String> executionIds,int missingResultCount,int missingReviewCount,int pendingReviewCount,
                ScoreSummary machine,ScoreSummary reviewed,List<ItemGroup> items,String pipelineId,ReviewChangeSummary reviewChanges,RolloutAttribution rollout){
            this(groupKey,schemeId,versionNo,draftRevision,kind,mode,baseScore,passingScore,taskIds,conversationCount,executionIds,
                    missingResultCount,missingReviewCount,pendingReviewCount,machine,reviewed,items,pipelineId,reviewChanges,rollout,null);
        }
        public PolicyGroup(String groupKey,String schemeId,String versionNo,String draftRevision,String kind,String mode,int baseScore,int passingScore,
                List<String> taskIds,int conversationCount,List<String> executionIds,int missingResultCount,int missingReviewCount,int pendingReviewCount,
                ScoreSummary machine,ScoreSummary reviewed,List<ItemGroup> items,String pipelineId,ReviewChangeSummary reviewChanges){
            this(groupKey,schemeId,versionNo,draftRevision,kind,mode,baseScore,passingScore,taskIds,conversationCount,executionIds,
                    missingResultCount,missingReviewCount,pendingReviewCount,machine,reviewed,items,pipelineId,reviewChanges,null);
        }
        public PolicyGroup(String groupKey,String schemeId,String versionNo,String draftRevision,String kind,String mode,int baseScore,int passingScore,
                List<String> taskIds,int conversationCount,List<String> executionIds,int missingResultCount,int missingReviewCount,int pendingReviewCount,
                ScoreSummary machine,ScoreSummary reviewed,List<ItemGroup> items,String pipelineId){
            this(groupKey,schemeId,versionNo,draftRevision,kind,mode,baseScore,passingScore,taskIds,conversationCount,executionIds,
                    missingResultCount,missingReviewCount,pendingReviewCount,machine,reviewed,items,pipelineId,null);
        }
        public PolicyGroup(String groupKey,String schemeId,String versionNo,String draftRevision,String kind,String mode,int baseScore,int passingScore,
                List<String> taskIds,int conversationCount,List<String> executionIds,int missingResultCount,int missingReviewCount,int pendingReviewCount,
                ScoreSummary machine,ScoreSummary reviewed,List<ItemGroup> items){
            this(groupKey,schemeId,versionNo,draftRevision,kind,mode,baseScore,passingScore,taskIds,conversationCount,executionIds,
                    missingResultCount,missingReviewCount,pendingReviewCount,machine,reviewed,items,null,null);
        }
    }
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
        return report(taskIds,executionSelectionJson,requestedRunPolicy,false);
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report report(List<String> taskIds,String executionSelectionJson,String requestedRunPolicy,boolean includeModelUsage){
        if(includeModelUsage){
            if(sensitive==null||modelCalls==null)throw IqcException.invalidState("调用统计授权/汇总依赖不可用");
            sensitive.current(java.util.Set.of("iqc:report:view","iqc:task:model-calls:view"));
        }
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
            if("PIPELINE_EVALUATION".equals(task.getTaskType())) throw IqcException.invalidArgument("评测任务不能进入正式业务评分统计");
            String executionId = runSelections.get(id);
            if (executionId != null && !"LATEST".equals(executionId)) {
                TaskExecution execution = executions.selectById(executionId);
                if (execution == null || !id.equals(execution.getTaskId())) throw IqcException.invalidArgument("所选执行实例不属于对应任务");
            } else executionId = null;
            try {
                var snapshot = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(task.getRuleSnapshotJson());
                io.github.opensabre.iqc.scoring.InspectionScoring.Policy scoring;
                List<ReportItem> items;JsonNode marker;String key;RolloutAttribution rollout=null;
                if(snapshot.has("pipelineSnapshot")){
                    var release=reviews.pipelineRelease(snapshot);scoring=release.scoring();
                    items=release.items().stream().map(item->new ReportItem(item.itemCode(),item.itemCode())).toList();marker=snapshot.path("pipelineSnapshot");
                    key=io.github.opensabre.iqc.selector.SelectorAssetService.hash(mapper.writeValueAsString(List.of("PIPELINE",marker.path("pipelineId"),
                            marker.path("versionNo"),marker.path("draftRevision"),marker.path("trial"),marker.path("release"))));
                }else{
                    var definition=SchemeResultEvaluator.definition(snapshot,mapper);
                    if(definition==null)throw IqcException.invalidArgument("业务统计不支持旧消息任务");
                    scoring=definition.scoring();items=definition.items().stream().map(item->new ReportItem(item.itemCode(),item.name())).toList();
                    marker=snapshot.path("schemeSnapshot");
                    key=InspectionSchemeService.contentHash(mapper.writeValueAsString(List.of(marker.path("schemeId"),marker.path("versionNo"),
                            marker.path("draftRevision"),marker.path("kind"),marker.path("release"))));
                }
                if(snapshot.has("rolloutSnapshot") || io.github.opensabre.iqc.pipelineasset.PipelineRolloutTaskEvidence.requiresEvidence(task.getId())
                        || (task.getId()!=null && task.getId().matches("t[a-f0-9]{63}"))){
                    if(rolloutGuard==null)throw IqcException.invalidState("灰度统计来源校验依赖不可用");
                    rolloutGuard.validate(snapshot,task.getId());
                    if(snapshot.has("rolloutSnapshot")){
                        var evidence=io.github.opensabre.iqc.pipelineasset.PipelineRolloutTaskEvidence.read(snapshot.path("rolloutSnapshot"),mapper);
                        var decision=evidence.decision();var revision=evidence.revision();
                        rollout=new RolloutAttribution("iqc-business-rollout-attribution-v1",revision.id(),revision.revisionNo(),revision.contentHash(),
                                decision.policyHash(),decision.side().name(),decision.state().name(),decision.candidateBasisPoints(),
                                decision.baselineVersionNo(),decision.candidateVersionNo(),decision.selectedVersionNo(),decision.selectedReleaseHash());
                        key=io.github.opensabre.iqc.selector.SelectorAssetService.hash(mapper.writeValueAsString(List.of(key,rollout)));
                    }
                }
                var conversations=BatchResultQueryService.exportConversationIds(task,mapper);
                count+=conversations.size();itemCount+=(long)conversations.size()*items.size();
                if(count>500||itemCount>50_000)throw IqcException.invalidArgument("业务统计最多 500 个任务会话、50000 个质检项");
                selected.add(new Selection(task,conversations,scoring,items,marker,key,executionId,rollout));
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
            var group = groups.computeIfAbsent(selection.key(), ignored -> new Group(selection,mapper));
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
                var projection=reviews.validatedProjection(completed,result,selection.task());
                var reviewed=projection.reviewed();group.addManualScores(projection);group.addReviewChanges(projection);
                group.reviewed.add(reviewed.scoring());
                group.addItems(reviewed, true);
            }
        }
        var reportGroups=new ArrayList<PolicyGroup>();
        for(var group:groups.values()){
            ModelUsageSummary usage=null;
            if(includeModelUsage){
                var references=new ArrayList<io.github.opensabre.iqc.result.llm.gateway.GatewayCallMapper.RunReference>();int expected=0;
                for(var candidate:candidates)if(candidate.selection().key().equals(group.selection.key())){
                    expected++;var result=candidate.result();
                    if(result!=null && result.getExecutionId()!=null && !result.getExecutionId().isBlank())
                        references.add(new io.github.opensabre.iqc.result.llm.gateway.GatewayCallMapper.RunReference(
                                candidate.selection().task().getId(),result.getExecutionId(),candidate.conversationId()));
                }
                var summary=modelCalls.taskRunUsage(references);int bound=(int)references.stream().distinct().count();
                usage=new ModelUsageSummary("iqc-business-model-usage-v1",expected,bound,expected-bound,summary.costs(),summary.latency(),summary.tokens());
            }
            reportGroups.add(group.view(usage));
        }
        return new Report("SELECTED_TASK_RUNS", runPolicy.name(), selected.size(), candidates.size(),List.copyOf(reportGroups));
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

    private record ReportItem(String itemCode,String name) { }
    private record Selection(InspectionTask task,List<String> conversations,InspectionScoring.Policy scoring,List<ReportItem> items,
                             JsonNode marker,String key,String executionId,RolloutAttribution rollout) { }
    private record RunCandidate(Selection selection, String conversationId, ConversationInspectionResult result,
                                java.util.Date effectiveTime) { }
    private static final class Group {
        final ObjectMapper json; final Selection selection; final List<String> taskIds = new ArrayList<>();
        final java.util.Set<String> executionIds = new java.util.LinkedHashSet<>();
        final Scores machine = new Scores(), reviewed = new Scores();
        final java.util.Map<String, ItemCounts> machineItems = new LinkedHashMap<>(), reviewedItems = new LinkedHashMap<>();
        int conversations, missingResult, missingReview, pendingReview;
        int manualApplied,manualStale,effectiveNumericCount;BigDecimal manualSum=BigDecimal.ZERO,effectiveSum=BigDecimal.ZERO;
        int reviewedResults,modifiedResults,itemOrScoreChanged,findingChanged,effectiveScoreChanged;
        private String digest(Object value){return io.github.opensabre.iqc.selector.SelectorAssetService.hash(json.valueToTree(value).toString());}
        private Object businessMeaning(SchemeResultEvaluator.Evaluation evaluation){
            var items=evaluation.items().stream().sorted(java.util.Comparator.comparing(SchemeResultEvaluator.ItemResult::itemCode))
                    .map(item->java.util.Map.of("code",item.itemCode(),"status",item.status(),"evidence",new java.util.TreeSet<>(item.matchedMessageIds()))).toList();
            return List.of(items,evaluation.scoring());
        }
        private Object findingMeaning(List<io.github.opensabre.iqc.fusion.FindingFusion.Result> facts){
            return facts.stream().sorted(java.util.Comparator.comparing(io.github.opensabre.iqc.fusion.FindingFusion.Result::code)).map(fact->{
                var value=new LinkedHashMap<String,Object>();value.put("code",fact.code());value.put("type",fact.valueType());value.put("state",fact.state());
                value.put("value",fact.value());value.put("severity",fact.severity());
                value.put("evidence",fact.evidence().stream().map(e->e.type().name()+":"+e.reference()).collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new)));
                return value;
            }).toList();
        }
        void addReviewChanges(BusinessItemReviewEvaluator.Projection projection){
            reviewedResults++;
            boolean item=!digest(businessMeaning(projection.original())).equals(digest(businessMeaning(projection.reviewed())));
            boolean finding=projection.findingReview()!=null&&!digest(findingMeaning(projection.findingReview().findings().original()))
                    .equals(digest(findingMeaning(projection.findingReview().findings().reviewed())));
            var manual=projection.manualScore();var machineScore=projection.original().scoring().finalScore();
            boolean numeric=manual!=null&&"APPLIED".equals(manual.status())
                    &&(machineScore==null||manual.effectiveScore().compareTo(machineScore)!=0);
            if(item)itemOrScoreChanged++;if(finding)findingChanged++;if(numeric)effectiveScoreChanged++;
            if(item||finding||numeric)modifiedResults++;
        }
        ReviewChangeSummary reviewChanges(){
            return new ReviewChangeSummary("iqc-business-review-changes-v1","LATEST_COMPLETED_BUSINESS_REVIEW_VS_MACHINE",reviewedResults,modifiedResults,
                    itemOrScoreChanged,findingChanged,effectiveScoreChanged,
                    reviewedResults==0?null:BigDecimal.valueOf(modifiedResults).divide(BigDecimal.valueOf(reviewedResults),6,RoundingMode.HALF_UP),
                    conversations==0?null:BigDecimal.valueOf(reviewedResults).divide(BigDecimal.valueOf(conversations),6,RoundingMode.HALF_UP));
        }
        void addManualScores(BusinessItemReviewEvaluator.Projection projection){
            var manual=projection.manualScore();BigDecimal numeric=null;
            if(manual!=null&&"APPLIED".equals(manual.status())){manualApplied++;numeric=manual.effectiveScore();manualSum=manualSum.add(numeric);}
            else {if(manual!=null)manualStale++;if(projection.reviewed().scoring().scoreStatus()==InspectionScoring.ScoreStatus.FINAL)numeric=projection.reviewed().scoring().finalScore();}
            if(numeric!=null){effectiveNumericCount++;effectiveSum=effectiveSum.add(numeric);}
        }
        ScoreSummary reviewedSummary(){
            var calculated=reviewed.view();if(manualApplied+manualStale==0)return calculated;
            var manual=new ManualScoreSummary(manualApplied,manualStale,manualApplied==0?null:manualSum.divide(BigDecimal.valueOf(manualApplied),2,RoundingMode.HALF_UP),
                    effectiveNumericCount,effectiveNumericCount==0?null:effectiveSum.divide(BigDecimal.valueOf(effectiveNumericCount),2,RoundingMode.HALF_UP));
            return new ScoreSummary(calculated.resultCount(),calculated.finalCount(),calculated.pendingCount(),calculated.notApplicableCount(),calculated.qualifiedCount(),
                    calculated.averageScore(),calculated.qualifiedRate(),manual);
        }
        Group(Selection selection,ObjectMapper json) {
            this.json=json;this.selection = selection;
            selection.items().forEach(item -> {
                machineItems.put(item.itemCode(), new ItemCounts());
                reviewedItems.put(item.itemCode(), new ItemCounts());
            });
        }
        void addItems(SchemeResultEvaluator.Evaluation evaluation, boolean human) {
            var counts = human ? reviewedItems : machineItems;
            evaluation.items().forEach(item -> counts.get(item.itemCode()).add(item.status()));
        }
        PolicyGroup view(ModelUsageSummary usage) {
            var marker = selection.marker(); var policy = selection.scoring();
            return new PolicyGroup(selection.key(), marker.path("schemeId").asText(""), marker.path("versionNo").asText(""),
                    marker.path("draftRevision").asText(""),marker.has("pipelineId")?(marker.path("trial").asBoolean()?"DRAFT_TRIAL":"PUBLISHED"):marker.path("kind").asText(""),policy.mode().name(),
                    policy.baseScore(), policy.passingScore(),
                    List.copyOf(new java.util.LinkedHashSet<>(taskIds)), conversations, List.copyOf(executionIds),
                    missingResult, missingReview, pendingReview, machine.view(),reviewedSummary(),
                    selection.items().stream().map(item -> {
                        var scoring = policy.items().stream().filter(value -> value.itemCode().equals(item.itemCode())).findFirst().orElse(null);
                        return new ItemGroup(item.itemCode(), item.name(), scoring != null,
                                scoring == null ? null : scoring.points(), scoring != null && scoring.veto(),
                                machineItems.get(item.itemCode()).view(), reviewedItems.get(item.itemCode()).view());
                    }).toList(),marker.has("pipelineId")?marker.path("pipelineId").asText():null,reviewChanges(),selection.rollout(),usage);
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
