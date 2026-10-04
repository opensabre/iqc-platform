package io.github.opensabre.iqc.result;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.dao.InspectionEvidenceMapper;
import io.github.opensabre.iqc.result.dao.RuleInspectionResultMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.result.model.InspectionEvidence;
import io.github.opensabre.iqc.result.model.InspectionResult;
import io.github.opensabre.iqc.result.model.RuleInspectionResult;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.label.LabelResultService;
import io.github.opensabre.iqc.scheme.SchemeResultEvaluator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Materializes task -> conversation -> top-level rule -> message evidence results. */
@Service
@RequiredArgsConstructor
public class HierarchicalResultService {
    private final ConversationInspectionResultMapper conversationMapper;
    private final RuleInspectionResultMapper ruleMapper;
    private final InspectionEvidenceMapper evidenceMapper;
    private final ObjectMapper objectMapper;
    private final LabelResultService labelResultService;
    private final io.github.opensabre.iqc.task.dao.InspectionTaskMapper taskMapper;
    private final io.github.opensabre.iqc.result.dao.InspectionResultMapper observationMapper;
    private final io.github.opensabre.iqc.task.dao.TaskItemMapper taskItemMapper;

    /** Commits conversation facts, message observation projections and work progress atomically under the task lock. */
    @Transactional
    public ConversationInspectionResult materializeItemRouteBatch(InspectionTask task, String executionId, JsonNode snapshot,
            List<ConversationMessage> messages, io.github.opensabre.iqc.scheme.SchemeDependencyResolver.RoutePlan plan,
            ItemRouteRunner.Run run, SchemeResultEvaluator.Evaluation business,
            List<InspectionResult> observations, List<io.github.opensabre.iqc.task.model.TaskItem> batch) {
        var conversation = materializeItemRoutes(task, executionId, snapshot, messages, plan, run, business);
        if (conversation == null) return null;
        var byMessage = observations.stream().collect(Collectors.toMap(InspectionResult::getMessageId, observation -> observation));
        var ids = new java.util.HashSet<String>();
        for (var requested : batch) {
            if (requested.getId() == null || !ids.add(requested.getId())) throw new IllegalArgumentException("逐项处理进度身份缺失或重复");
            var item = taskItemMapper.selectOne(Wrappers.<io.github.opensabre.iqc.task.model.TaskItem>lambdaQuery()
                    .eq(io.github.opensabre.iqc.task.model.TaskItem::getId, requested.getId()).last("FOR UPDATE"));
            if (item == null || !task.getId().equals(item.getTaskId()) || !executionId.equals(item.getExecutionId())
                    || !conversation.getConversationId().equals(item.getConversationId())
                    || !java.util.Objects.equals(requested.getMessageId(), item.getMessageId()))
                throw new IllegalArgumentException("逐项处理进度不属于本次会话执行");
            if ("SUCCEEDED".equals(item.getStatus())) continue;
            if (!List.of("PENDING", "RUNNING", "FAILED").contains(item.getStatus()))
                throw new IllegalStateException("逐项处理进度已失效");
            var observation = byMessage.get(item.getMessageId());
            if (observation == null || !task.getId().equals(observation.getTaskId())
                    || !executionId.equals(observation.getExecutionId())
                    || !conversation.getConversationId().equals(observation.getConversationId()))
                throw new IllegalArgumentException("逐项消息观察与处理进度不一致");
            if (observationMapper.insert(observation) != 1 || observation.getId() == null)
                throw new IllegalStateException("逐项消息观察提交失败");
            item.setResultId(observation.getId());
            item.setStatus("ERROR".equals(observation.getResultStatus()) ? "FAILED" : "SUCCEEDED");
            item.setAttemptCount((item.getAttemptCount() == null ? 0 : item.getAttemptCount()) + 1);
            item.setErrorMessage("FAILED".equals(item.getStatus()) ? "逐项检测阶段失败" : null);
            if (taskItemMapper.updateById(item) != 1) throw new IllegalStateException("逐项处理进度提交失败");
        }
        return conversation;
    }

    /** Builds one conversation decision from legacy message evaluations without charging a rule more than once. */
    @Transactional
    public ConversationInspectionResult materialize(InspectionTask task, String executionId, JsonNode snapshot,
                                                     List<ConversationMessage> messages, List<InspectionResult> messageResults) {
        return materializeInternal(task, executionId, snapshot, messages, messageResults, null);
    }

    /** Retains per-context facts in the existing rule payload; business scores come only from terminal projection. */
    @Transactional
    public ConversationInspectionResult materializeItemRoutes(InspectionTask task, String executionId, JsonNode snapshot,
            List<ConversationMessage> messages, io.github.opensabre.iqc.scheme.SchemeDependencyResolver.RoutePlan plan,
            ItemRouteRunner.Run run, SchemeResultEvaluator.Evaluation business) {
        if (business == null || plan == null || run == null) throw new IllegalArgumentException("逐项结果投影不能为空");
        // Model calls run outside this transaction. Lock only at commit to serialize pause/cancel and execution changes.
        var current = taskMapper.selectOne(Wrappers.<InspectionTask>lambdaQuery()
                .eq(InspectionTask::getId, task.getId()).last("FOR UPDATE"));
        if (current == null || !"RUNNING".equals(current.getStatus())
                || !java.util.Objects.equals(executionId, current.getCurrentExecutionId())) return null;
        try {
            if (!snapshot.equals(objectMapper.readTree(current.getRuleSnapshotJson()))
                    || !java.util.Objects.equals(task.getAgentSnapshotJson(), current.getAgentSnapshotJson())
                    || !java.util.Objects.equals(task.getLabelScopeSnapshotJson(), current.getLabelScopeSnapshotJson()))
                throw new IllegalStateException("任务冻结输入已变化，禁止写入旧模型结果");
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("任务冻结输入无效，禁止写入模型结果", exception);
        }
        return materializeInternal(task, executionId, snapshot, messages, List.of(), new RouteMaterialization(plan, run, business));
    }

    private record RouteMaterialization(io.github.opensabre.iqc.scheme.SchemeDependencyResolver.RoutePlan plan,
                                        ItemRouteRunner.Run run, SchemeResultEvaluator.Evaluation business) { }

    private ConversationInspectionResult materializeInternal(InspectionTask task, String executionId, JsonNode snapshot,
            List<ConversationMessage> messages, List<InspectionResult> messageResults, RouteMaterialization routed) {
        String conversationId = messages.isEmpty() ? null : messages.get(0).getConversationId();
        if (conversationId == null) return null;
        ConversationInspectionResult existing = conversationMapper.selectOne(Wrappers.<ConversationInspectionResult>lambdaQuery()
                .eq(ConversationInspectionResult::getExecutionId, executionId)
                .eq(ConversationInspectionResult::getConversationId, conversationId).last("LIMIT 1"));
        if (existing != null) return existing;

        var scheme = SchemeResultEvaluator.definition(snapshot, objectMapper);
        if (routed != null && scheme == null)
            throw new IllegalArgumentException("逐项结果缺少冻结方案，禁止省略业务结果");
        var business = routed != null ? routed.business() : scheme == null ? null
                : SchemeResultEvaluator.evaluate(scheme, snapshot, messages, messageResults, objectMapper);

        List<JsonNode> rules = rules(snapshot);
        String aggregationMode = snapshot != null && snapshot.has("aggregationMode")
                ? snapshot.path("aggregationMode").asText("ANY").toUpperCase() : "ANY";
        ConversationInspectionResult conversation = new ConversationInspectionResult();
        conversation.setTaskId(task.getId()); conversation.setExecutionId(executionId); conversation.setConversationId(conversationId);
        conversation.setAggregationMode(aggregationMode); conversation.setResultStatus("NOT_EVALUATED");
        conversation.setScore(100); conversation.setRiskLevel("LOW"); conversation.setDeduction(0); conversation.setReason("没有可执行规则");
        conversationMapper.insert(conversation);

        List<RuleInspectionResult> ruleResults = new ArrayList<>();
        for (JsonNode rule : rules) ruleResults.add(routed == null
                ? materializeRule(conversation, rule, messages, messageResults, business != null)
                : materializeContexts(conversation, rule, routed));
        boolean anyHit = ruleResults.stream().anyMatch(item -> "HIT".equals(item.getResultStatus()));
        boolean anyReview = ruleResults.stream().anyMatch(item -> "REVIEW_REQUIRED".equals(item.getResultStatus()));
        boolean allHit = !ruleResults.isEmpty() && ruleResults.stream().allMatch(item -> "HIT".equals(item.getResultStatus()));
        boolean hit = "ALL".equals(aggregationMode) ? allHit : anyHit;
        boolean anyError = ruleResults.stream().anyMatch(item -> item.getResultStatus().endsWith("ERROR"));
        int deduction = ruleResults.stream().filter(item -> "HIT".equals(item.getResultStatus()))
                .mapToInt(item -> item.getDeduction() == null ? 0 : item.getDeduction()).sum();
        boolean veto = rules.stream().anyMatch(rule -> rule.path("veto").asBoolean(false)
                && ruleResults.stream().anyMatch(item -> rule.path("id").asText().equals(item.getRuleId()) && "HIT".equals(item.getResultStatus())));
        conversation.setResultStatus(anyReview ? "REVIEW_REQUIRED" : anyError ? (hit ? "PARTIAL_ERROR" : "ERROR") : hit ? "HIT" : "NOT_HIT");
        conversation.setDeduction(hit ? Math.min(100, deduction) : 0);
        conversation.setScore(anyError ? 0 : hit ? (veto ? 0 : Math.max(0, 100 - conversation.getDeduction())) : 100);
        conversation.setRiskLevel(ruleResults.stream().filter(item -> "HIT".equals(item.getResultStatus()))
                .map(RuleInspectionResult::getRiskLevel).max(Comparator.comparingInt(this::riskOrder)).orElse("LOW"));
        conversation.setReason(hit ? "会话命中 " + ruleResults.stream().filter(item -> "HIT".equals(item.getResultStatus())).count() + " 条规则" : "会话未命中规则");
        if (business != null) {
            // Only business items own V2 scoring. Never expose the detector's compatibility score as a final score.
            var score = business.scoring();
            conversation.setScore(null); conversation.setDeduction(0);
            conversation.setFinalScore(score.finalScore()); conversation.setScoreStatus(score.scoreStatus().name());
            conversation.setScoringResultJson(write(score)); conversation.setBusinessItemResultsJson(write(business.items()));
            boolean incomplete = business.items().stream().anyMatch(item -> switch (item.status()) {
                case ERROR, REVIEW_REQUIRED, NOT_EVALUATED -> true;
                default -> false;
            });
            conversation.setResultStatus(incomplete ? "PENDING" : score.conclusion().name());
            var failedRuleIds = business.items().stream().filter(item -> item.status() == io.github.opensabre.iqc.scoring.InspectionScoring.ItemStatus.FAIL)
                    .map(SchemeResultEvaluator.ItemResult::ruleId).collect(java.util.stream.Collectors.toSet());
            conversation.setRiskLevel(rules.stream().filter(rule -> failedRuleIds.contains(rule.path("id").asText()))
                    .map(rule -> rule.path("riskLevel").asText("MEDIUM")).max(Comparator.comparingInt(this::riskOrder)).orElse("LOW"));
            conversation.setReason(incomplete ? "部分业务质检项尚未形成确定结论" : "已按方案版本中的独立评分政策计算");
        }
        conversationMapper.updateById(conversation);
        labelResultService.materialize(conversation, task.getLabelScopeSnapshotJson(), ruleResults, messages);
        return conversation;
    }

    private RuleInspectionResult materializeContexts(ConversationInspectionResult conversation, JsonNode rule,
                                                     RouteMaterialization routed) {
        String ruleId = rule.path("id").asText();
        var contexts = routed.plan().contexts().stream().filter(context -> ruleId.equals(context.ruleId())).toList();
        var stages = routed.run().stages().stream().collect(Collectors.toMap(ItemRouteRunner.Stage::contextKey, stage -> stage));
        boolean labelFacts = rule.path("labelFactTargets").isArray() && !rule.path("labelFactTargets").isEmpty();
        var payload = objectMapper.createObjectNode().put("schemaVersion",
                labelFacts ? "iqc-rule-observations-v2" : "iqc-rule-contexts-v1");
        var observations = payload.putArray(labelFacts ? "observations" : "contexts"); var outcomes = new ArrayList<String>();
        var labelContextKeys = new java.util.HashSet<>(routed.plan().labelContextKeys());
        for (var context : contexts) {
            var stage = stages.get(context.contextKey());
            String status = stage == null ? "NOT_EVALUATED" : stage.result().getResultStatus(); outcomes.add(status);
            if (labelFacts && labelContextKeys.contains(context.contextKey())) {
                var value = mapperNode(stage == null ? null : stage.result().getFindingJson());
                if (value != null && "iqc-rule-observations-v2".equals(value.path("schemaVersion").asText())
                        && value.path("observations").isArray()) value.path("observations").forEach(item -> observations.add(item.deepCopy()));
            } else if (!labelFacts) {
                var value = observations.addObject().put("contextKey", context.contextKey()).put("phase", context.phase())
                        .put("inputScope", context.inputScope()).put("upstreamContextKey", context.upstreamContextKey())
                        .put("executed", stage != null && stage.executed()).put("status", status);
                if (stage != null) value.set("result", objectMapper.valueToTree(stage.result()));
            }
        }
        if (!labelFacts) {
            var keys = contexts.stream().map(io.github.opensabre.iqc.scheme.SchemeDependencyResolver.DetectionContext::contextKey).collect(Collectors.toSet());
            payload.set("itemRoutes", objectMapper.valueToTree(routed.plan().items().stream()
                    .filter(item -> keys.contains(item.finalContextKey()) || keys.contains(item.stageContextKey())).toList()));
        }
        var result = new RuleInspectionResult(); result.setConversationResultId(conversation.getId()); result.setRuleId(ruleId);
        result.setRuleVersionNo(rule.path("versionNo").asInt()); result.setRuleType(rule.path("ruleType").asText());
        result.setEvaluationScope("CONTEXT"); result.setScore(null); result.setDeduction(0);
        result.setResultStatus(outcomes.isEmpty() || outcomes.contains("NOT_EVALUATED") ? "NOT_EVALUATED"
                : outcomes.contains("ERROR") ? "ERROR" : outcomes.contains("REVIEW_REQUIRED") ? "REVIEW_REQUIRED"
                : outcomes.contains("HIT") ? "HIT" : "NOT_HIT");
        result.setRiskLevel("HIT".equals(result.getResultStatus()) ? rule.path("riskLevel").asText("MEDIUM") : "LOW");
        result.setReason("检测上下文汇总仅用于展示，不代表业务项目终态或计分"); result.setFindingJson(payload.toString());
        ruleMapper.insert(result);
        // Each evidence row belongs to its context via the existing internal-definition field.
        for (var context : contexts) {
            var stage = stages.get(context.contextKey());
            boolean labelConsumer = labelContextKeys.contains(context.contextKey());
            if (stage == null || (labelConsumer
                    ? java.util.Set.of("ERROR", "NOT_EVALUATED").contains(stage.result().getResultStatus())
                    : !"HIT".equals(stage.result().getResultStatus()))) continue;
            try {
                var evidence = objectMapper.readTree(stage.result().getEvidenceJson());
                if (evidence == null || !evidence.isArray()) throw new IllegalArgumentException("逐项证据格式无效");
                var owned = new InspectionResult(); org.springframework.beans.BeanUtils.copyProperties(stage.result(), owned);
                evidence.forEach(value -> { if (value instanceof com.fasterxml.jackson.databind.node.ObjectNode object)
                    object.put("definition", context.contextKey()); });
                if (evidence.isEmpty()) continue;
                owned.setEvidenceJson(evidence.toString()); insertEvidence(result, new ResultSlice("HIT", owned), labelConsumer);
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw new IllegalArgumentException("逐项证据格式无效", exception);
            }
        }
        return result;
    }

    private JsonNode mapperNode(String json) {
        if (json == null || json.isBlank()) return null;
        try { return objectMapper.readTree(json); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { return null; }
    }

    private RuleInspectionResult materializeRule(ConversationInspectionResult conversation, JsonNode rule,
                                                  List<ConversationMessage> messages, List<InspectionResult> messageResults,
                                                  boolean schemeTask) {
        String ruleId = rule.path("id").asText();
        List<ResultSlice> slices = slices(ruleId, messageResults);
        Set<String> observedIds = slices.stream().map(slice -> slice.result().getMessageId()).collect(Collectors.toSet());
        List<String> missingApplicableIds = schemeTask && rule.path("labelFactTargets").isArray()
                ? messages.stream().filter(message -> {
                    String role = rule.path("targetRole").asText("all");
                    return role.isBlank() || "all".equalsIgnoreCase(role) || role.equalsIgnoreCase(message.getSpeakerRole());
                }).map(ConversationMessage::getId)
                .filter(id -> !observedIds.contains(id)).toList()
                : List.of();
        boolean hit = slices.stream().anyMatch(slice -> "HIT".equals(slice.status()));
        boolean review = slices.stream().anyMatch(slice -> "REVIEW_REQUIRED".equals(slice.status()));
        boolean error = slices.stream().anyMatch(slice -> slice.status().endsWith("ERROR"));
        RuleInspectionResult result = new RuleInspectionResult();
        result.setConversationResultId(conversation.getId()); result.setRuleId(ruleId);
        result.setRuleVersionNo(rule.path("versionNo").isNumber() ? rule.path("versionNo").asInt() : null);
        String type = rule.path("ruleType").asText("UNKNOWN"); result.setRuleType(type);
        result.setEvaluationScope("DLS".equalsIgnoreCase(type) || rule.path("labelFactTargets").isArray() ? "CONVERSATION" : "MESSAGE");
        result.setResultStatus(review ? "REVIEW_REQUIRED" : hit ? "HIT" : error ? "ERROR" : "NOT_HIT");
        int deduction = hit ? Math.max(0, Math.min(100, rule.path("deduction").asInt(10))) : 0;
        result.setDeduction(deduction); result.setScore(error && !hit ? 0 : hit ? (rule.path("veto").asBoolean(false) ? 0 : 100 - deduction) : review ? 0 : 100);
        result.setRiskLevel(hit ? rule.path("riskLevel").asText("MEDIUM") : error || review ? "HIGH" : "LOW");
        result.setReason(review ? "多轮判断平票，需要人工复核" : hit ? "规则在当前会话命中" : error ? "规则执行异常" : "规则在当前会话未命中");
        result.setConfidence(confidence(slices));
        result.setFindingJson(schemeTask ? rule.path("labelFactTargets").isArray()
                ? labelFindings(slices, ruleId, missingApplicableIds) : schemeFindings(slices) : findingJson(slices));
        if (schemeTask) {
            result.setScore(null); result.setDeduction(0);
            if (slices.isEmpty() || !missingApplicableIds.isEmpty()
                    || slices.stream().anyMatch(slice -> "NOT_EVALUATED".equals(slice.status())))
                result.setResultStatus("NOT_EVALUATED");
        }
        ruleMapper.insert(result);
        var hitSlices = slices.stream().filter(slice -> rule.path("labelFactTargets").isArray()
                ? "HIT".equals(slice.status()) || "NOT_HIT".equals(slice.status()) : "HIT".equals(slice.status()));
        if (schemeTask && "DLS".equalsIgnoreCase(type)) hitSlices = hitSlices.limit(1);
        boolean strictEvidence = rule.path("labelFactTargets").isArray();
        hitSlices.forEach(slice -> insertEvidence(result, slice, strictEvidence));
        return result;
    }

    private String write(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw new IllegalStateException("业务评分结果无法序列化", exception); }
    }

    private java.math.BigDecimal confidence(List<ResultSlice> slices) {
        return slices.stream().map(ResultSlice::result).map(InspectionResult::getFindingJson)
                .filter(java.util.Objects::nonNull).map(value -> {
                    try { return findConfidence(objectMapper.readTree(value)); }
                    catch (Exception ignored) { return null; }
                }).filter(java.util.Objects::nonNull).min(java.math.BigDecimal::compareTo).orElse(null);
    }

    private java.math.BigDecimal findConfidence(JsonNode node) {
        if (node == null) return null;
        if (node.isObject() && node.has("confidence") && node.path("confidence").isNumber()) return node.path("confidence").decimalValue();
        if (node.isContainerNode()) for (JsonNode child : node) { var value = findConfidence(child); if (value != null) return value; }
        return null;
    }

    private String findingJson(List<ResultSlice> slices) {
        return slices.stream().filter(slice -> "HIT".equals(slice.status()))
                .map(ResultSlice::result).map(InspectionResult::getFindingJson)
                .filter(value -> value != null && !value.isBlank()).findFirst().orElse(null);
    }

    /** Retains every source observation for later conflict/coverage evaluation, not just the first hit.
     * Findings are raw detector payloads; consumers must still validate rule ownership, subject and evidence.
     */
    private String schemeFindings(List<ResultSlice> slices) {
        var payload = objectMapper.createObjectNode().put("schemaVersion", "iqc-rule-observations-v2");
        var observations = payload.putArray("observations");
        for (var slice : slices) {
            var observation = observations.addObject();
            observation.put("messageId", slice.result().getMessageId());
            observation.put("status", slice.status());
            String raw = slice.result().getFindingJson();
            if (raw == null || raw.isBlank()) continue;
            try {
                var finding = objectMapper.readTree(raw);
                if (finding != null && !finding.isNull()) observation.set("finding", finding);
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                // Extraction failure is explicit; never invent a value or discard other valid observations.
                observation.put("findingError", "INVALID_JSON");
            }
        }
        return payload.toString();
    }

    /** Read only this rule's finding from the aggregate; other rules cannot assert facts for it. */
    private String labelFindings(List<ResultSlice> slices, String ruleId, List<String> missingApplicableIds) {
        var payload = objectMapper.createObjectNode().put("schemaVersion", "iqc-rule-observations-v2");
        var observations = payload.putArray("observations");
        for (var slice : slices) {
            var observation = observations.addObject().put("messageId", slice.result().getMessageId()).put("status", slice.status());
            String raw = slice.result().getFindingJson();
            if (raw == null || raw.isBlank()) {
                observation.put("findingError", "MISSING_FINDING");
                continue;
            }
            try {
                var aggregate = objectMapper.readTree(raw);
                var finding = aggregate == null ? null : aggregate.path("ruleFindings").path(ruleId);
                if (finding != null && finding.isObject() && "iqc-label-facts-v2".equals(finding.path("schemaVersion").asText()))
                    observation.set("finding", finding);
                else observation.put("findingError", "INVALID_FACT_SOURCE");
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                observation.put("findingError", "INVALID_JSON");
            }
        }
        // Missing coverage is an explicit failed observation, never an implicit empty fact list.
        for (String messageId : missingApplicableIds)
            observations.addObject().put("messageId", messageId).put("status", "NOT_EVALUATED")
                    .put("findingError", "MISSING_OBSERVATION");
        return payload.toString();
    }

    private List<ResultSlice> slices(String ruleId, List<InspectionResult> results) {
        List<ResultSlice> slices = new ArrayList<>();
        for (InspectionResult result : results) {
            try {
                JsonNode breakdown = objectMapper.readTree(result.getRuleBreakdownJson());
                if (breakdown == null || !breakdown.isArray()) continue;
                for (JsonNode item : breakdown) if (ruleId.equals(item.path("ruleId").asText()))
                    slices.add(new ResultSlice(item.path("status").asText("NOT_HIT"), result));
            } catch (Exception ignored) { /* Legacy malformed explanations remain queryable in the legacy result. */ }
        }
        return slices;
    }

    private void insertEvidence(RuleInspectionResult ruleResult, ResultSlice slice, boolean strict) {
        try {
            JsonNode values = objectMapper.readTree(slice.result().getEvidenceJson());
            if (values == null || !values.isArray()) {
                if (strict) throw new IllegalStateException("标签证据列表格式无效");
                return;
            }
            for (JsonNode value : values) {
                if (strict && (!value.path("ruleId").isTextual() || value.path("ruleId").asText().isBlank()))
                    throw new IllegalStateException("标签证据来源或引用无效");
                // A message observation aggregates evidence from every detector. Each rule result owns only its slice.
                if (value.has("ruleId") && !ruleResult.getRuleId().equals(value.path("ruleId").asText())) continue;
                if (strict && (!value.path("messageId").isTextual() || value.path("messageId").asText().isBlank()
                        || !value.path("text").isTextual() || value.path("text").asText().isBlank()))
                    throw new IllegalStateException("标签证据来源或引用无效");
                InspectionEvidence evidence = new InspectionEvidence();
                evidence.setRuleResultId(ruleResult.getId());
                evidence.setMessageId(value.path("messageId").asText(slice.result().getMessageId()));
                evidence.setSequenceNo(value.path("sequenceNo").isNumber() ? value.path("sequenceNo").asInt() : null);
                evidence.setInternalDefinition(value.path("definition").asText(null));
                evidence.setEvidenceType("DLS".equals(ruleResult.getRuleType()) ? "DLS_RULE_HIT" : "MESSAGE_MATCH");
                evidence.setMatchedText(value.path("text").asText(null));
                evidence.setStartOffset(value.path("start").isNumber() ? value.path("start").asInt() : null);
                evidence.setEndOffset(value.path("end").isNumber() ? value.path("end").asInt() : null);
                evidenceMapper.insert(evidence);
            }
        } catch (Exception exception) {
            if (strict) throw new IllegalStateException("标签证据持久化失败", exception);
            // Legacy optional evidence remains compatible with its historical tolerant behavior.
        }
    }

    /** Returns the canonical conversation decision with rule decisions and message evidence. */
    public ResultHierarchy hierarchy(String taskId, String conversationId) {
        ConversationInspectionResult conversation = conversationMapper.selectLatestForTaskConversation(taskId, conversationId);
        if (conversation == null) return null;
        List<RuleInspectionResult> rules = ruleMapper.selectList(Wrappers.<RuleInspectionResult>lambdaQuery()
                .eq(RuleInspectionResult::getConversationResultId, conversation.getId()));
        Map<String, List<InspectionEvidence>> evidence = new LinkedHashMap<>();
        for (RuleInspectionResult rule : rules) evidence.put(rule.getId(), evidenceMapper.selectList(Wrappers.<InspectionEvidence>lambdaQuery()
                .eq(InspectionEvidence::getRuleResultId, rule.getId()).orderByAsc(InspectionEvidence::getSequenceNo)));
        return new ResultHierarchy(conversation, rules, evidence);
    }

    private List<JsonNode> rules(JsonNode snapshot) {
        if (snapshot == null) return List.of();
        List<JsonNode> values = new ArrayList<>();
        JsonNode source = snapshot.has("rules") ? snapshot.path("rules") : snapshot;
        if (source.isArray()) source.forEach(values::add); else if (source.isObject()) values.add(source);
        return values;
    }

    private int riskOrder(String risk) { return switch (risk == null ? "" : risk.toUpperCase()) { case "HIGH" -> 3; case "MEDIUM" -> 2; default -> 1; }; }
    private record ResultSlice(String status, InspectionResult result) { }
    public record ResultHierarchy(ConversationInspectionResult conversation, List<RuleInspectionResult> rules,
                                  Map<String, List<InspectionEvidence>> evidenceByRuleResult) { }
}
