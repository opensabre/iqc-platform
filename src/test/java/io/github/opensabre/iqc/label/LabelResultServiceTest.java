package io.github.opensabre.iqc.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.label.dao.InspectionLabelResultMapper;
import io.github.opensabre.iqc.label.model.InspectionLabelResult;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.result.model.RuleInspectionResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LabelResultServiceTest {
    private final InspectionLabelResultMapper mapper = mock(InspectionLabelResultMapper.class);
    private final LabelResultService service = new LabelResultService(mapper, new ObjectMapper());

    @Test
    void projectsHitRuleOnceAndKeepsCanonicalRuleResultReference() {
        ConversationInspectionResult conversation = new ConversationInspectionResult(); conversation.setId("conversation-result-1");
        RuleInspectionResult hit = rule("rule-1", "rule-result-1", "HIT");
        hit.setFindingJson("{\"labelValues\":{\"intent\":{\"detected\":\"renewal\"}}}");
        String snapshot = """
                {"schemaVersion":"1.0","labels":[{"id":"label-1","versionNo":2,
                "code":"intent",
                "bindings":[{"ruleId":"rule-1"},{"ruleId":"rule-2"}],
                "values":[{"valueCode":"detected","valueType":"FIXED"}]}]}
                """;

        service.materialize(conversation, snapshot, List.of(hit, rule("rule-2", "rule-result-2", "HIT")));

        ArgumentCaptor<InspectionLabelResult> result = ArgumentCaptor.forClass(InspectionLabelResult.class);
        verify(mapper).insert(result.capture());
        assertThat(result.getValue().getLabelId()).isEqualTo("label-1");
        assertThat(result.getValue().getLabelVersionNo()).isEqualTo(2);
        assertThat(result.getValue().getValueCode()).isEqualTo("detected");
        assertThat(result.getValue().getValueJson()).contains("renewal");
        assertThat(result.getValue().getSourceRuleResultId()).isEqualTo("rule-result-1");
    }

    @Test
    void invalidTypedValueDoesNotBecomeAResultValue() {
        ConversationInspectionResult conversation = new ConversationInspectionResult(); conversation.setId("conversation-result-1");
        RuleInspectionResult hit = rule("rule-1", "rule-result-1", "HIT");
        hit.setFindingJson("{\"labelValues\":{\"ratio\":101}}");
        String snapshot = """
                {"labels":[{"id":"label-1","bindings":[{"ruleId":"rule-1"}],
                "values":[{"valueCode":"ratio","valueType":"PERCENTAGE"}]}]}
                """;

        service.materialize(conversation, snapshot, List.of(hit));

        ArgumentCaptor<InspectionLabelResult> result = ArgumentCaptor.forClass(InspectionLabelResult.class);
        verify(mapper).insert(result.capture());
        assertThat(result.getValue().getValueCode()).isEmpty();
        assertThat(result.getValue().getValueJson()).isNull();
    }

    @Test
    void doesNotProjectLabelsForRulesThatDidNotHit() {
        ConversationInspectionResult conversation = new ConversationInspectionResult(); conversation.setId("conversation-result-1");
        String snapshot = "{\"labels\":[{\"id\":\"label-1\",\"bindings\":[{\"ruleId\":\"rule-1\"}]}]}";
        service.materialize(conversation, snapshot, List.of(rule("rule-1", "rule-result-1", "NOT_HIT")));
        verifyNoInteractions(mapper);
    }

    private RuleInspectionResult rule(String ruleId, String id, String status) {
        RuleInspectionResult result = new RuleInspectionResult(); result.setId(id); result.setRuleId(ruleId); result.setResultStatus(status); return result;
    }
}
