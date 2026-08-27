package io.github.opensabre.iqc.result;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.governance.usage.UsageCounterRecorder;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.result.dao.InspectionResultMapper;
import io.github.opensabre.iqc.result.llm.LlmQualityProvider;
import io.github.opensabre.iqc.result.model.InspectionResult;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.dao.TaskExecutionMapper;
import io.github.opensabre.iqc.task.dao.TaskItemMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.util.ReflectionTestUtils;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import io.github.opensabre.iqc.task.model.TaskExecution;
import io.github.opensabre.iqc.task.model.TaskItem;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InspectionExecutionServiceTest {
    @BeforeEach
    void initializeMybatisLambdaMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "iqc-execution-test"), TaskItem.class);
    }
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmQualityProvider llmProvider = mock(LlmQualityProvider.class);
    private final InspectionExecutionService service = new InspectionExecutionService(
            mock(InspectionTaskMapper.class), mock(ConversationMessageMapper.class), mock(InspectionResultMapper.class),
            objectMapper, mock(TaskExecutionMapper.class), mock(TaskItemMapper.class), mock(IqcDataScope.class), llmProvider, mock(UsageCounterRecorder.class));

    @Test
    void keywordRuleOnlyAppliesToItsTargetSpeaker() {
        InspectionResult hit = evaluate(rule("r-1", "KEYWORD", "优惠", "agent"), message("agent", "今天有优惠"));
        InspectionResult skipped = evaluate(rule("r-1", "KEYWORD", "优惠", "user"), message("agent", "今天有优惠"));

        assertThat(hit.getResultStatus()).isEqualTo("HIT");
        assertThat(hit.getScore()).isEqualTo(90);
        assertThat(skipped.getResultStatus()).isEqualTo("NOT_HIT");
        assertThat(skipped.getReason()).contains("不适用");
    }

    @Test
    void unsupportedLlmRuleIsDiagnosticError() {
        JsonNode rule = rule("r-llm", "LLM", "识别违规承诺", "all");
        when(llmProvider.evaluate(org.mockito.ArgumentMatchers.eq("今天有优惠"), org.mockito.ArgumentMatchers.eq(rule),
                org.mockito.ArgumentMatchers.nullable(JsonNode.class), org.mockito.ArgumentMatchers.eq("inspection-message:task-1:message-1:rule:r-llm")))
                .thenReturn(new LlmQualityProvider.LlmEvaluation(false, false, "LLM 规则未配置可用适配器"));

        InspectionResult result = evaluate(rule, message("agent", "今天有优惠"));

        assertThat(result.getResultStatus()).isEqualTo("ERROR");
        assertThat(result.getRiskLevel()).isEqualTo("HIGH");
        assertThat(result.getReason()).contains("未配置可用适配器");
        assertThat(result.getRuleBreakdownJson()).contains("ERROR");
    }

    @Test
    void mixedRulesExposePartialErrorAndBreakdown() throws Exception {
        JsonNode keyword = rule("r-1", "KEYWORD", "优惠", "all");
        JsonNode llm = rule("r-2", "LLM", "判断语义", "all");
        when(llmProvider.evaluate(org.mockito.ArgumentMatchers.eq("今天有优惠"), org.mockito.ArgumentMatchers.eq(llm),
                org.mockito.ArgumentMatchers.nullable(JsonNode.class), org.mockito.ArgumentMatchers.eq("inspection-message:task-1:message-1:rule:r-2")))
                .thenReturn(new LlmQualityProvider.LlmEvaluation(false, false, "LLM 规则未配置可用适配器"));
        JsonNode snapshot = objectMapper.createArrayNode().add(keyword).add(llm);

        InspectionResult result = ReflectionTestUtils.invokeMethod(service, "evaluate", task(), message("agent", "今天有优惠"), snapshot);

        assertThat(result).isNotNull();
        assertThat(result.getResultStatus()).isEqualTo("PARTIAL_ERROR");
        assertThat(result.getScore()).isZero();
        assertThat(result.getRuleId()).isEqualTo("r-1,r-2");
        assertThat(objectMapper.readTree(result.getRuleBreakdownJson())).hasSize(2);
    }

    @Test
    void breakdownKeepsRuleIdentityAndScoringDecision() throws Exception {
        JsonNode configuredRule = ((com.fasterxml.jackson.databind.node.ObjectNode) rule("r-1", "KEYWORD", "优惠", "all"))
                .put("name", "违规优惠承诺").put("code", "illegal_discount").put("category", "COMPLIANCE")
                .put("deduction", 25).put("riskLevel", "HIGH").put("veto", true);

        InspectionResult result = evaluate(configuredRule, message("agent", "今天有优惠"));
        JsonNode breakdown = objectMapper.readTree(result.getRuleBreakdownJson()).get(0);

        assertThat(breakdown.path("ruleName").asText()).isEqualTo("违规优惠承诺");
        assertThat(breakdown.path("ruleCode").asText()).isEqualTo("illegal_discount");
        assertThat(breakdown.path("category").asText()).isEqualTo("COMPLIANCE");
        assertThat(breakdown.path("deduction").asInt()).isEqualTo(25);
        assertThat(breakdown.path("veto").asBoolean()).isTrue();
        assertThat(result.getScore()).isZero();
    }

    @Test
    void structuredConditionIsEvaluatedInTaskExecution() {
        JsonNode structured = rule("r-structured", "STRUCTURED",
                "{\"all\":[{\"field\":\"content\",\"operator\":\"contains\",\"value\":\"优惠\"},{\"field\":\"speakerRole\",\"operator\":\"equals\",\"value\":\"agent\"}]}", "all");

        InspectionResult result = evaluate(structured, message("agent", "今天有优惠"));

        assertThat(result.getResultStatus()).isEqualTo("HIT");
        assertThat(result.getEvidenceJson()).contains("优惠");
    }

    @Test
    void configuredConcurrencyProcessesDifferentConversationsInParallel() {
        InspectionTaskMapper tasks = mock(InspectionTaskMapper.class);
        ConversationMessageMapper messages = mock(ConversationMessageMapper.class);
        InspectionResultMapper results = mock(InspectionResultMapper.class);
        TaskExecutionMapper executions = mock(TaskExecutionMapper.class);
        TaskItemMapper items = mock(TaskItemMapper.class);
        InspectionTask task = task(); task.setStatus("QUEUED"); task.setConcurrencyLimit(2);
        task.setProcessedMessages(0); task.setFailedMessages(0); task.setRuleSnapshotJson("[]");
        TaskExecution execution = new TaskExecution(); execution.setId("execution-1");
        TaskItem first = taskItem("item-1", "message-1", "conversation-1", 1);
        TaskItem second = taskItem("item-2", "message-2", "conversation-2", 2);
        when(tasks.selectById("task-1")).thenReturn(task);
        when(executions.selectById("execution-1")).thenReturn(execution);
        when(items.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(first, second));
        AtomicInteger active = new AtomicInteger(); AtomicInteger maximum = new AtomicInteger();
        when(messages.selectById(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
            int current = active.incrementAndGet(); maximum.accumulateAndGet(current, Math::max);
            try { Thread.sleep(120); } finally { active.decrementAndGet(); }
            ConversationMessage message = message("agent", "正常话术");
            message.setId(invocation.getArgument(0));
            message.setConversationId("message-1".equals(message.getId()) ? "conversation-1" : "conversation-2");
            return message;
        });
        InspectionExecutionService concurrentService = new InspectionExecutionService(tasks, messages, results, objectMapper,
                executions, items, mock(IqcDataScope.class), llmProvider, mock(UsageCounterRecorder.class));

        InspectionTask completed = concurrentService.run("task-1", "execution-1");

        assertThat(maximum.get()).isEqualTo(2);
        assertThat(completed.getProcessedMessages()).isEqualTo(2);
        assertThat(completed.getStatus()).isEqualTo("SUCCEEDED");
    }

    private InspectionResult evaluate(JsonNode rule, ConversationMessage message) {
        return ReflectionTestUtils.invokeMethod(service, "evaluateSingle", task(), message, rule);
    }

    private InspectionTask task() {
        InspectionTask task = new InspectionTask();
        task.setId("task-1");
        task.setConversationId("conversation-1");
        return task;
    }

    private ConversationMessage message(String role, String content) {
        ConversationMessage message = new ConversationMessage();
        message.setId("message-1");
        message.setConversationId("conversation-1");
        message.setSequenceNo(1);
        message.setSpeakerRole(role);
        message.setContent(content);
        return message;
    }

    private TaskItem taskItem(String id, String messageId, String conversationId, int sequence) {
        TaskItem item = new TaskItem(); item.setId(id); item.setTaskId("task-1"); item.setExecutionId("execution-1");
        item.setMessageId(messageId); item.setConversationId(conversationId); item.setSequenceNo(sequence); item.setStatus("PENDING"); item.setAttemptCount(0);
        return item;
    }

    private JsonNode rule(String id, String type, String expression, String targetRole) {
        return objectMapper.createObjectNode()
                .put("id", id).put("ruleType", type).put("expression", expression)
                .put("targetRole", targetRole).put("deduction", 10).put("riskLevel", "MEDIUM");
    }
}
