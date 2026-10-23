package io.github.opensabre.iqc.pipelinebridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.result.InspectionExecutionService;
import io.github.opensabre.iqc.result.model.InspectionResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Failed detectors retain raw citation diagnostics before the bridge returns their error verdict. */
class FrozenDetectorCitationMetricsTest {
    private final ObjectMapper json=new ObjectMapper();
    private final InspectionExecutionService executor=mock(InspectionExecutionService.class);
    private NodeResult execute(String raw,String type) throws Exception {
        var outcome=new InspectionResult();outcome.setResultStatus("ERROR");outcome.setFindingJson(raw);
        when(executor.evaluateFrozenPipelineNode(any(),any(),any(),anyList(),any(),anyString(),anyString(),anyString(),anyInt())).thenReturn(outcome);
        ObjectProvider<InspectionExecutionService> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(executor);
        var config=json.createObjectNode();config.putObject("ruleSnapshot").put("targetRole","all");
        var node=new PipelineDefinition.Node("llm",type,"Model","1",List.of(),Map.of(),config,null,1000,0,PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
        var messages=json.createArrayNode();messages.addObject().put("id","m").put("conversationId","c").put("speakerRole","agent").put("content","processed text");
        var task=json.createObjectNode().put("id","task").put("currentExecutionId","run");
        var result=new FrozenDetectorEngine(type,json,provider).execute(new NodeContext(node,json.createObjectNode(),
                Map.of("__task",task,"__messages",messages),Map.of(),PipelineRuntime.Mode.EVALUATION,1));
        assertThat(outcome.getFindingJson()).isEqualTo(raw);assertThat(outcome.getResultStatus()).isEqualTo("ERROR");
        return result;
    }
    @Test void errorVerdictKeepsInvalidRawQuoteCountsWithoutPublishingFindings() throws Exception {
        var result=execute("{\"hit\":true,\"reason\":\"invalid quote\",\"evidence\":[{\"messageId\":\"missing\",\"text\":\"invented\"}]}","LLM");
        assertThat(result.status()).isEqualTo(NodeResult.Status.ERROR);assertThat(result.findings()).isEmpty();
        var metric=result.metrics().get("llmCitationGrounding");assertThat(metric.path("state").asText()).isEqualTo("MEASURED");
        assertThat(metric.path("claimedQuotes").longValue()).isEqualTo(1);assertThat(metric.path("invalidQuotes").longValue()).isEqualTo(1);
        assertThat(metric.toString()).doesNotContain("invented","missing","processed text");
    }
    @Test void unparsableObservationCannotChangeTheErrorVerdict() throws Exception {
        var result=execute("{unparsable","LLM");assertThat(result.status()).isEqualTo(NodeResult.Status.ERROR);assertThat(result.metrics()).doesNotContainKey("llmCitationGrounding");
    }
    @Test void ruleDetectorsDoNotInventLlmCitationMeasurements() throws Exception {
        var result=execute("{}","RULE");assertThat(result.status()).isEqualTo(NodeResult.Status.ERROR);assertThat(result.metrics()).doesNotContainKey("llmCitationGrounding");
    }
}
