package io.github.opensabre.iqc.pipelinebridge;
import com.fasterxml.jackson.databind.*;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.finding.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class TranscriptMetricEngineTest {
 final ObjectMapper json=new ObjectMapper();
 NodeContext context(JsonNode messages,JsonNode input,PipelineRuntime.Mode mode){
 var definition=new FindingDefinition(FindingDefinition.SCHEMA,"GAP_MS","Gap","TIMING",null,Finding.ValueType.NUMBER,List.of(),Finding.Severity.LOW,true,List.of(Finding.EvidenceType.METRIC));
 var config=json.createObjectNode().put("metric","TRANSCRIPT_GAP_MS").put("agentRole","agent").put("customerRole","customer");config.set("findingDefinition",json.valueToTree(definition));
 var node=new PipelineDefinition.Node("timing","METRIC","Timing",TranscriptMetricEngine.VERSION,List.of(),Map.of(),config,null,1000,0,PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
 return new NodeContext(node,input,Map.of("__messages",messages,"__task",json.createObjectNode().put("currentExecutionId","execution")),Map.of(),mode,1);
 }
 JsonNode messages() throws Exception{return json.readTree("[{\"id\":\"a\",\"conversationId\":\"c\",\"speakerRole\":\"customer\",\"startMs\":0,\"endMs\":100},{\"id\":\"b\",\"conversationId\":\"c\",\"speakerRole\":\"agent\",\"startMs\":200,\"endMs\":300}]");}
 @Test void productionAndEvaluationProduceSameSourceBoundNumericFact() throws Exception {
 var engine=new TranscriptMetricEngine(json);var source=messages();var production=engine.execute(context(source,json.createObjectNode(),PipelineRuntime.Mode.PRODUCTION));var evaluation=engine.execute(context(source,json.createObjectNode(),PipelineRuntime.Mode.EVALUATION));
 assertEquals(production.findings(),evaluation.findings());var fact=production.findings().getFirst();assertEquals(100,fact.value().intValue());assertEquals(Finding.State.KNOWN,fact.state());assertEquals(Finding.EvidenceType.METRIC,fact.evidence().getFirst().type());assertEquals("ms",fact.evidence().getFirst().details().path("unit").asText());assertEquals(2,fact.evidence().getFirst().details().path("messageIds").size());
 }
 @Test void mappedSubsetCannotReplaceTrustedManifest() throws Exception {
 var source=messages();var input=json.createObjectNode();input.set("messages",json.createArrayNode().add(source.get(0)));assertThrows(IllegalArgumentException.class,()->new TranscriptMetricEngine(json).execute(context(source,input,PipelineRuntime.Mode.PRODUCTION)));
 }
 @Test void absentCoordinatesRemainUnknownWithoutFakeEvidence() throws Exception {
 var source=messages();((com.fasterxml.jackson.databind.node.ObjectNode)source.get(0)).remove(List.of("startMs","endMs"));var result=new TranscriptMetricEngine(json).execute(context(source,json.createObjectNode(),PipelineRuntime.Mode.PRODUCTION));var fact=result.findings().getFirst();assertEquals(Finding.State.UNKNOWN,fact.state());assertNull(fact.value());assertTrue(fact.evidence().isEmpty());assertFalse(result.variables().containsKey("timing_value"));
 }
 @Test void realRuntimePublishesMetricAndHidesTrustedMetadata() throws Exception {
 var context=context(messages(),json.createObjectNode(),PipelineRuntime.Mode.PRODUCTION);
 try(var runtime=new PipelineRuntime(List.of(new TranscriptMetricEngine(json)),1,4)) {
 var run=runtime.execute(new PipelineDefinition(PipelineDefinition.SCHEMA,List.of(context.node())),context.input(),context.variables(),context.mode());
 assertEquals(PipelineRuntime.Status.SUCCESS,run.status());assertEquals(1,run.trace().getFirst().attempt());assertEquals(100,run.variables().get("timing_value").intValue());assertEquals(1,run.findings().size());assertFalse(run.variables().containsKey("__messages"));assertFalse(run.variables().containsKey("__task"));
 }
 }
 @Test void realRuntimeConditionSkipDoesNotPublishMetric() throws Exception {
 var context=context(messages(),json.createObjectNode(),PipelineRuntime.Mode.PRODUCTION);var original=context.node();
 var skipped=new PipelineDefinition.Node(original.nodeId(),original.type(),original.name(),original.version(),List.of(),Map.of(),original.config(),new PipelineDefinition.Condition("/input/eligible",PipelineDefinition.Operator.EQUALS,json.getNodeFactory().booleanNode(true)),1000,0,PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
 try(var runtime=new PipelineRuntime(List.of(new TranscriptMetricEngine(json)),1,4)) {
 var run=runtime.execute(new PipelineDefinition(PipelineDefinition.SCHEMA,List.of(skipped)),context.input(),context.variables(),context.mode());assertEquals(NodeResult.Status.SKIPPED,run.trace().getFirst().result().status());assertTrue(run.findings().isEmpty());assertFalse(run.variables().containsKey("timing_value"));
 }
 }
}
