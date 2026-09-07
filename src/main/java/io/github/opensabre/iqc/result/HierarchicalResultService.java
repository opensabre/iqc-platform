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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Materializes task -> conversation -> top-level rule -> message evidence results. */
@Service
@RequiredArgsConstructor
public class HierarchicalResultService {
    private final ConversationInspectionResultMapper conversationMapper;
    private final RuleInspectionResultMapper ruleMapper;
    private final InspectionEvidenceMapper evidenceMapper;
    private final ObjectMapper objectMapper;

    /** Builds one conversation decision from legacy message evaluations without charging a rule more than once. */
    @Transactional
    public ConversationInspectionResult materialize(InspectionTask task, String executionId, JsonNode snapshot,
                                                     List<ConversationMessage> messages, List<InspectionResult> messageResults) {
        String conversationId = messages.isEmpty() ? null : messages.get(0).getConversationId();
        if (conversationId == null) return null;
        ConversationInspectionResult existing = conversationMapper.selectOne(Wrappers.<ConversationInspectionResult>lambdaQuery()
                .eq(ConversationInspectionResult::getExecutionId, executionId)
                .eq(ConversationInspectionResult::getConversationId, conversationId).last("LIMIT 1"));
        if (existing != null) return existing;

        List<JsonNode> rules = rules(snapshot);
        String aggregationMode = snapshot != null && snapshot.has("aggregationMode")
                ? snapshot.path("aggregationMode").asText("ANY").toUpperCase() : "ANY";
        ConversationInspectionResult conversation = new ConversationInspectionResult();
        conversation.setTaskId(task.getId()); conversation.setExecutionId(executionId); conversation.setConversationId(conversationId);
        conversation.setAggregationMode(aggregationMode); conversation.setResultStatus("NOT_EVALUATED");
        conversation.setScore(100); conversation.setRiskLevel("LOW"); conversation.setDeduction(0); conversation.setReason("没有可执行规则");
        conversationMapper.insert(conversation);

        List<RuleInspectionResult> ruleResults = new ArrayList<>();
        for (JsonNode rule : rules) ruleResults.add(materializeRule(conversation, rule, messageResults));
        boolean anyHit = ruleResults.stream().anyMatch(item -> "HIT".equals(item.getResultStatus()));
        boolean allHit = !ruleResults.isEmpty() && ruleResults.stream().allMatch(item -> "HIT".equals(item.getResultStatus()));
        boolean hit = "ALL".equals(aggregationMode) ? allHit : anyHit;
        boolean anyError = ruleResults.stream().anyMatch(item -> item.getResultStatus().endsWith("ERROR"));
        int deduction = ruleResults.stream().filter(item -> "HIT".equals(item.getResultStatus()))
                .mapToInt(item -> item.getDeduction() == null ? 0 : item.getDeduction()).sum();
        boolean veto = rules.stream().anyMatch(rule -> rule.path("veto").asBoolean(false)
                && ruleResults.stream().anyMatch(item -> rule.path("id").asText().equals(item.getRuleId()) && "HIT".equals(item.getResultStatus())));
        conversation.setResultStatus(anyError ? (hit ? "PARTIAL_ERROR" : "ERROR") : hit ? "HIT" : "NOT_HIT");
        conversation.setDeduction(hit ? Math.min(100, deduction) : 0);
        conversation.setScore(anyError ? 0 : hit ? (veto ? 0 : Math.max(0, 100 - conversation.getDeduction())) : 100);
        conversation.setRiskLevel(ruleResults.stream().filter(item -> "HIT".equals(item.getResultStatus()))
                .map(RuleInspectionResult::getRiskLevel).max(Comparator.comparingInt(this::riskOrder)).orElse("LOW"));
        conversation.setReason(hit ? "会话命中 " + ruleResults.stream().filter(item -> "HIT".equals(item.getResultStatus())).count() + " 条规则" : "会话未命中规则");
        conversationMapper.updateById(conversation);
        return conversation;
    }

    private RuleInspectionResult materializeRule(ConversationInspectionResult conversation, JsonNode rule,
                                                  List<InspectionResult> messageResults) {
        String ruleId = rule.path("id").asText();
        List<ResultSlice> slices = slices(ruleId, messageResults);
        boolean hit = slices.stream().anyMatch(slice -> "HIT".equals(slice.status()));
        boolean error = slices.stream().anyMatch(slice -> slice.status().endsWith("ERROR"));
        RuleInspectionResult result = new RuleInspectionResult();
        result.setConversationResultId(conversation.getId()); result.setRuleId(ruleId);
        result.setRuleVersionNo(rule.path("versionNo").isNumber() ? rule.path("versionNo").asInt() : null);
        String type = rule.path("ruleType").asText("UNKNOWN"); result.setRuleType(type);
        result.setEvaluationScope("DLS".equalsIgnoreCase(type) ? "CONVERSATION" : "MESSAGE");
        result.setResultStatus(hit ? "HIT" : error ? "ERROR" : "NOT_HIT");
        int deduction = hit ? Math.max(0, Math.min(100, rule.path("deduction").asInt(10))) : 0;
        result.setDeduction(deduction); result.setScore(error && !hit ? 0 : hit ? (rule.path("veto").asBoolean(false) ? 0 : 100 - deduction) : 100);
        result.setRiskLevel(hit ? rule.path("riskLevel").asText("MEDIUM") : error ? "HIGH" : "LOW");
        result.setReason(hit ? "规则在当前会话命中" : error ? "规则执行异常" : "规则在当前会话未命中");
        ruleMapper.insert(result);
        slices.stream().filter(slice -> "HIT".equals(slice.status())).forEach(slice -> insertEvidence(result, slice));
        return result;
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

    private void insertEvidence(RuleInspectionResult ruleResult, ResultSlice slice) {
        try {
            JsonNode values = objectMapper.readTree(slice.result().getEvidenceJson());
            if (values == null || !values.isArray()) return;
            for (JsonNode value : values) {
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
        } catch (Exception ignored) { /* A decision remains valid when optional evidence JSON is malformed. */ }
    }

    /** Returns the canonical conversation decision with rule decisions and message evidence. */
    public ResultHierarchy hierarchy(String taskId, String conversationId) {
        ConversationInspectionResult conversation = conversationMapper.selectOne(Wrappers.<ConversationInspectionResult>lambdaQuery()
                .eq(ConversationInspectionResult::getTaskId, taskId)
                .eq(ConversationInspectionResult::getConversationId, conversationId)
                .orderByDesc(ConversationInspectionResult::getCreatedTime).last("LIMIT 1"));
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
