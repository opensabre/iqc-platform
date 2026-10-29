package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.pipelineasset.PipelineRelease;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Standalone policy decoding uses the frozen source protocol; request hashes are verified by the existing contract. */
class ExactActionPolicyReplayTest {
    private final ObjectMapper json=new ObjectMapper();
    private String policy(String decimal) throws Exception {
        var value=json.getNodeFactory().numberNode(new BigDecimal(decimal));
        var action=new ActionPolicy.Action("save",ActionExecutionContract.Type.SAVE_RESULT,
                new ActionPolicy.Condition(ActionPolicy.Kind.BUSINESS_EQUALS,"/amount",value,List.of()),
                ActionPolicy.Idempotency.PRODUCTION_SOURCE_V1,null,ActionPolicy.FailureHandling.RECORD_ONLY,
                json.createObjectNode());
        return json.writeValueAsString(new ActionPolicy(ActionPolicy.SCHEMA,List.of(action)));
    }
    private String input(String schema) {
        var task=json.createObjectNode(); task.putObject("pipelineSnapshot").putObject("release").put("schemaVersion",schema);
        return json.createObjectNode().put("schemaVersion","iqc-action-production-input-v1").put("taskSnapshotJson",task.toString()).toString();
    }
    @Test void exactStandalonePolicyKeepsConditionAndAdjacentValuesDistinct() throws Exception {
        String body=policy("0.900000000000000001");
        var restored=ActionFrozenPolicy.readFrozen(json,body,input(PipelineRelease.EXACT_SCHEMA));
        var condition=restored.actions().getFirst().condition();
        assertThat(condition.value().decimalValue()).isEqualByComparingTo("0.900000000000000001");
        var context=new ActionPolicy.Context(Map.of(),Map.of(),InspectionScoring.ScoreStatus.FINAL,BigDecimal.TEN,
                "NONE","QUALIFIED",Map.of(),json.createObjectNode().put("amount",new BigDecimal("0.900000000000000001")));
        assertThat(ActionPolicy.evaluate(condition,context)).isEqualTo(ActionPolicy.Match.MATCH);
        var adjacent=ActionFrozenPolicy.readFrozen(json,policy("0.900000000000000002"),input(PipelineRelease.EXACT_SCHEMA));
        assertThat(ActionPolicy.evaluate(adjacent.actions().getFirst().condition(),context)).isEqualTo(ActionPolicy.Match.NO_MATCH);
        assertThat(SelectorAssetService.hash(json.writeValueAsString(restored))).isEqualTo(SelectorAssetService.hash(body));
    }
    @Test void legacyAndNestedLookalikesKeepLegacyInterpretationWhileUnknownIsRejected() throws Exception {
        String body=policy("0.900000000000000001");
        assertThat(ActionFrozenPolicy.readFrozen(json,body,input(PipelineRelease.SCHEMA)).actions().getFirst().condition().value().decimalValue())
                .isEqualByComparingTo("0.9");
        var task=json.createObjectNode(); task.putObject("userConfiguration").putObject("release").put("schemaVersion",PipelineRelease.EXACT_SCHEMA);
        String lookalike=json.createObjectNode().put("taskSnapshotJson",task.toString()).toString();
        assertThat(ActionFrozenPolicy.readFrozen(json,body,lookalike).actions().getFirst().condition().value().decimalValue()).isEqualByComparingTo("0.9");
        assertThatThrownBy(() -> ActionFrozenPolicy.readFrozen(json,body,input("iqc-pipeline-release-v99"))).hasMessageContaining("Unsupported");
    }
}
