package io.github.opensabre.iqc.result;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HierarchicalResultServiceTest {
    private final ConversationInspectionResultMapper conversations = mock(ConversationInspectionResultMapper.class);
    private final RuleInspectionResultMapper rules = mock(RuleInspectionResultMapper.class);
    private final InspectionEvidenceMapper evidence = mock(InspectionEvidenceMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HierarchicalResultService service = new HierarchicalResultService(conversations, rules, evidence, objectMapper);

    @BeforeEach
    void initializeMybatisMetadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "hierarchical-result-test");
        TableInfoHelper.initTableInfo(assistant, ConversationInspectionResult.class);
    }

    @Test
    void repeatedMessageHitsProduceOneRuleDeductionAndMultipleEvidenceRows() throws Exception {
        when(conversations.selectOne(any())).thenReturn(null);
        when(conversations.insert(any(ConversationInspectionResult.class))).thenAnswer(invocation -> { ((ConversationInspectionResult) invocation.getArgument(0)).setId("conversation-result-1"); return 1; });
        when(rules.insert(any(RuleInspectionResult.class))).thenAnswer(invocation -> { ((RuleInspectionResult) invocation.getArgument(0)).setId("rule-result-1"); return 1; });
        var snapshot = objectMapper.readTree("{\"aggregationMode\":\"ANY\",\"rules\":[{\"id\":\"rule-1\",\"versionNo\":3,\"ruleType\":\"REGEX\",\"deduction\":10,\"riskLevel\":\"MEDIUM\"}]}");
        InspectionTask task = new InspectionTask(); task.setId("task-1");

        ConversationInspectionResult result = service.materialize(task, "execution-1", snapshot,
                List.of(message("m1", 1), message("m2", 2)), List.of(hit("m1", 1), hit("m2", 2)));

        assertThat(result.getResultStatus()).isEqualTo("HIT");
        assertThat(result.getDeduction()).isEqualTo(10);
        ArgumentCaptor<RuleInspectionResult> rule = ArgumentCaptor.forClass(RuleInspectionResult.class);
        verify(rules).insert(rule.capture());
        assertThat(rule.getValue().getEvaluationScope()).isEqualTo("MESSAGE");
        assertThat(rule.getValue().getDeduction()).isEqualTo(10);
        verify(evidence, org.mockito.Mockito.times(2)).insert(any(io.github.opensabre.iqc.result.model.InspectionEvidence.class));
    }

    @Test
    void dlsResultIsConversationScopedAndKeepsInternalRuleEvidence() throws Exception {
        when(conversations.selectOne(any())).thenReturn(null);
        when(conversations.insert(any(ConversationInspectionResult.class))).thenAnswer(invocation -> {
            ((ConversationInspectionResult) invocation.getArgument(0)).setId("conversation-result-1");
            return 1;
        });
        when(rules.insert(any(RuleInspectionResult.class))).thenAnswer(invocation -> {
            ((RuleInspectionResult) invocation.getArgument(0)).setId("rule-result-1");
            return 1;
        });
        var snapshot = objectMapper.readTree("{\"rules\":[{\"id\":\"dls-1\",\"versionNo\":2,\"ruleType\":\"DLS\",\"deduction\":20,\"riskLevel\":\"HIGH\"}]}");
        InspectionTask task = new InspectionTask(); task.setId("task-1");
        InspectionResult result = hit("m2", 2);
        result.setRuleBreakdownJson("[{\"ruleId\":\"dls-1\",\"status\":\"HIT\"}]");
        result.setEvidenceJson("[{\"definition\":\"rule_insult\",\"messageId\":\"m2\",\"sequenceNo\":2,\"text\":\"命中\",\"start\":0,\"end\":2}]");

        service.materialize(task, "execution-1", snapshot,
                List.of(message("m1", 1), message("m2", 2)), List.of(result));

        ArgumentCaptor<RuleInspectionResult> rule = ArgumentCaptor.forClass(RuleInspectionResult.class);
        verify(rules).insert(rule.capture());
        assertThat(rule.getValue().getEvaluationScope()).isEqualTo("CONVERSATION");
        assertThat(rule.getValue().getDeduction()).isEqualTo(20);
        ArgumentCaptor<InspectionEvidence> capturedEvidence = ArgumentCaptor.forClass(InspectionEvidence.class);
        verify(evidence).insert(capturedEvidence.capture());
        assertThat(capturedEvidence.getValue().getInternalDefinition()).isEqualTo("rule_insult");
        assertThat(capturedEvidence.getValue().getEvidenceType()).isEqualTo("DLS_RULE_HIT");
    }

    private ConversationMessage message(String id, int sequence) {
        ConversationMessage message = new ConversationMessage();
        message.setId(id); message.setConversationId("conversation-1"); message.setSequenceNo(sequence);
        return message;
    }

    private InspectionResult hit(String messageId, int sequence) {
        InspectionResult result = new InspectionResult();
        result.setMessageId(messageId); result.setResultStatus("HIT");
        result.setRuleBreakdownJson("[{\"ruleId\":\"rule-1\",\"status\":\"HIT\"}]");
        result.setEvidenceJson("[{\"ruleId\":\"rule-1\",\"messageId\":\"" + messageId + "\",\"sequenceNo\":" + sequence + ",\"text\":\"命中\",\"start\":0,\"end\":2}]");
        return result;
    }
}
