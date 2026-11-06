package io.github.opensabre.iqc.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.agent.dao.QualityAgentMapper;
import io.github.opensabre.iqc.agent.model.QualityAgent;
import io.github.opensabre.iqc.rule.dao.QualityRuleMapper;
import io.github.opensabre.iqc.rule.model.QualityRule;
import io.github.opensabre.iqc.result.llm.LlmQualityProvider;
import io.github.opensabre.iqc.task.processing.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import static io.github.opensabre.iqc.task.processing.EngineExecutionResultSet.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskEnginePlanServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final QualityRuleMapper rules = mock(QualityRuleMapper.class);
    private final QualityAgentMapper agents = mock(QualityAgentMapper.class);
    private final LlmQualityProvider provider = new LlmQualityProvider() {
        public LlmEvaluation evaluate(String content, com.fasterxml.jackson.databind.JsonNode rule, String id) {
            throw new AssertionError("conversation required");
        }
        @Override public LlmEvaluation evaluateConversationTraced(
                List<io.github.opensabre.iqc.conversation.model.ConversationMessage> messages,
                com.fasterxml.jackson.databind.JsonNode rule, com.fasterxml.jackson.databind.JsonNode agent,
                String id, java.util.function.Consumer<String> raw) {
            assertThat(messages.getFirst().getConversationId()).isEqualTo("conversation-1");
            raw.accept("{\"hit\":false,\"reason\":\"not matched\"}");
            return new LlmEvaluation(true, false, "not matched");
        }
    };
    private final TaskEnginePlanService service = new TaskEnginePlanService(rules, agents, mapper,
            new RegexEngineAdapter(mapper), new DlsEngineAdapter(mapper), new LlmEngineAdapter(provider, mapper));

    @Test void freezesReadableNameWithoutChangingEngineIdentityOrOrder() throws Exception {
        when(rules.selectById("r1")).thenReturn(rule("r1", "REGEX", "退款"));
        String json = single("REGEX", "\"ruleId\":\"r1\"");
        var request = request(json.replace("\"id\":\"engine1\"", "\"id\":\"engine1\",\"name\":\"  退款用语检查  \""));
        var plan = service.freeze(request);
        var step = service.steps(plan, "c1").getFirst();
        assertThat(plan.engines().getFirst().instanceId()).isEqualTo("engine1");
        assertThat(plan.engines().getFirst().sequence()).isEqualTo(1);
        assertThat(step.configuration().path("engineName").asText()).isEqualTo("退款用语检查");
        var roundTrip = mapper.readValue(mapper.writeValueAsString(plan), TaskEnginePlanService.FrozenPlan.class);
        assertThat(roundTrip).isEqualTo(plan);
        assertThat(service.freeze(request(json)).engines().getFirst().configuration().path("engineName").asText()).isEqualTo("engine1");
    }

    @Test void rejectsBlankAndOversizedExplicitNames() {
        var parameters = mapper.createObjectNode();
        assertThatThrownBy(() -> new TaskProcessingConfiguration.Engine("engine1", TaskProcessingConfiguration.EngineType.REGEX, parameters, "  "))
                .hasMessageContaining("引擎名称");
        assertThatThrownBy(() -> new TaskProcessingConfiguration.Engine("engine1", TaskProcessingConfiguration.EngineType.REGEX, parameters, "a".repeat(81)))
                .hasMessageContaining("引擎名称");
    }

    @Test void executesIndependentOrdersAndKeepsConflictingEngineResultsFromFrozenSnapshots() throws Exception {
        var regex = rule("r1", "REGEX", "退款");
        var llm = rule("r2", "LLM", "识别退款");
        when(rules.selectById("r1")).thenReturn(regex); when(rules.selectById("r2")).thenReturn(llm);
        var agent = new QualityAgent(); agent.setId("a1"); agent.setStatus("PUBLISHED"); agent.setVersionNo(3);
        agent.setConfigJson("{\"schemaVersion\":\"3.0\",\"systemPrompt\":\"识别会话\"}");
        when(agents.selectById("a1")).thenReturn(agent);
        var request = request("""
                {"schemaVersion":"iqc-task-processing-v1",
                 "inspection":{"engines":[
                   {"id":"shared","type":"LLM","parameters":{"targetId":"check-1","ruleId":"r2","agentId":"a1"}},
                   {"id":"regex-second","type":"REGEX","parameters":{"targetId":"check-1","ruleId":"r1"}}]},
                 "labeling":{"engines":[
                   {"id":"shared","type":"REGEX","parameters":{"targetId":"label-1","ruleId":"r1"}}]}}
                """);
        var frozen = service.freeze(request);
        regex.setExpression("不得使用新值"); agent.setConfigJson("{}");
        var serialized = mapper.readValue(mapper.writeValueAsString(frozen), TaskEnginePlanService.FrozenPlan.class);
        var steps = service.steps(serialized, "conversation-1");
        assertThat(steps).extracting(MultiEngineExecutor.Step::sequence).containsExactly(1, 2, 1);
        assertThat(steps.get(1).configuration().path("expression").asText()).isEqualTo("退款");
        assertThat(steps.get(0).configuration().path("agentSnapshot").path("versionNo").asInt()).isEqualTo(3);
        var now = Instant.EPOCH;
        var input = new InputSnapshot("snapshot-1", now, List.of(new Message("m1", 1, "customer", null, "申请退款")));
        var initial = new EngineExecutionResultSet(SCHEMA_VERSION, "set", "task", "execution", "conversation-1",
                input, mapper.valueToTree(frozen), List.of());
        var result = new MultiEngineExecutor(Clock.fixed(now, ZoneOffset.UTC)).execute(initial, steps, () -> false, ignored -> {});
        assertThat(result.engines()).extracting(EngineResult::status).containsOnly(ExecutionStatus.SUCCEEDED);
        assertThat(result.engines()).extracting(EngineResult::operation).containsExactly(Operation.INSPECTION, Operation.INSPECTION, Operation.LABELING);
        assertThat(result.engines().get(0).observations().getFirst().value().booleanValue()).isFalse();
        assertThat(result.engines().get(1).observations().getFirst().value().booleanValue()).isTrue();
        assertThat(result.engines().get(2).observations().getFirst().targetId()).isEqualTo("label-1");
        verify(rules, times(2)).selectById("r1"); verify(agents, times(1)).selectById("a1");
    }

    @Test void rejectsClientSnapshotsDraftRulesAndMismatchedTypes() throws Exception {
        var config = request(single("REGEX", "\"ruleId\":\"r1\",\"expression\":\"override\""));
        assertThatThrownBy(() -> service.freeze(config)).hasMessageContaining("不支持的引擎参数");
        verifyNoInteractions(rules, agents);
        var draft = rule("r1", "REGEX", "退款"); draft.setStatus("DRAFT");
        when(rules.selectById("r1")).thenReturn(draft);
        var valid = request(single("REGEX", "\"ruleId\":\"r1\""));
        assertThatThrownBy(() -> service.freeze(valid)).hasMessageContaining("已发布规则");
        draft.setStatus("PUBLISHED"); draft.setRuleType("DLS");
        assertThatThrownBy(() -> service.freeze(valid)).hasMessageContaining("类型不一致");
    }

    @Test void rejectsInvalidExpressionsAndNonModelAgents() throws Exception {
        when(rules.selectById("r1")).thenReturn(rule("r1", "REGEX", "["));
        assertThatThrownBy(() -> service.freeze(request(single("REGEX", "\"ruleId\":\"r1\""))))
                .isInstanceOf(IllegalArgumentException.class);
        when(rules.selectById("r1")).thenReturn(rule("r1", "LLM", "检查"));
        var config = request(single("LLM", "\"ruleId\":\"r1\",\"agentId\":\"a1\""));
        assertThatThrownBy(() -> service.freeze(config)).hasMessageContaining("已发布大模型配置");
        var agent = new QualityAgent(); agent.setStatus("PUBLISHED"); agent.setConfigJson("{\"mode\":\"RULE_ONLY\"}");
        when(agents.selectById("a1")).thenReturn(agent);
        assertThatThrownBy(() -> service.freeze(config)).hasMessageContaining("不具备大模型能力");
    }

    @Test void validatesSelectedOperationsDuplicatesAndConfigurationImmutability() throws Exception {
        assertThatThrownBy(() -> request("{\"schemaVersion\":\"iqc-task-processing-v1\"}"))
                .hasMessageContaining("至少选择");
        assertThatThrownBy(() -> request("{\"schemaVersion\":\"iqc-task-processing-v1\",\"inspection\":{\"engines\":[]}}"))
                .hasMessageContaining("1 到 32");
        var one = request(single("REGEX", "\"ruleId\":\"r1\""));
        var engine = one.inspection().engines().getFirst();
        ((com.fasterxml.jackson.databind.node.ObjectNode) engine.parameters()).put("ruleId", "changed");
        assertThat(engine.parameters().path("ruleId").asText()).isEqualTo("r1");
        assertThatThrownBy(() -> new TaskProcessingConfiguration.Processing(List.of(engine, engine))).hasMessageContaining("重复");
    }

    private TaskProcessingConfiguration request(String json) throws Exception {
        return mapper.readValue(json, TaskProcessingConfiguration.class);
    }
    private String single(String type, String parameters) {
        return "{\"schemaVersion\":\"iqc-task-processing-v1\",\"inspection\":{\"engines\":[{\"id\":\"engine1\",\"type\":\""
                + type + "\",\"parameters\":{\"targetId\":\"t1\"," + parameters + "}}]}}";
    }
    private QualityRule rule(String id, String type, String expression) {
        var rule = new QualityRule(); rule.setId(id); rule.setName(id); rule.setStatus("PUBLISHED");
        rule.setRuleType(type); rule.setExpression(expression); rule.setVersionNo(2); rule.setTargetRole("all"); return rule;
    }
}
