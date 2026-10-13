package io.github.opensabre.iqc.pipelinebridge;
import com.fasterxml.jackson.databind.*;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.finding.*;
import io.github.opensabre.iqc.rule.dls.DlsRuleDocument;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SessionAggregationEngineTest {
 final ObjectMapper json=new ObjectMapper();
 NodeContext context(JsonNode manifest,JsonNode input) throws Exception {
 var definition=new FindingDefinition(FindingDefinition.SCHEMA,"OFFER_COUNT","Offers","SESSION",null,Finding.ValueType.NUMBER,List.of(),Finding.Severity.LOW,true,List.of(Finding.EvidenceType.RULE_MATCH));
 var expression=new DlsRuleDocument("1.0",null,List.of(new DlsRuleDocument.Definition("rule_offer","RULE","offer","agent")),"Offers","[rule_offer]");
 var config=json.createObjectNode().put("aggregation","MATCHED_MESSAGE_COUNT").put("countDefinition","rule_offer");config.set("findingDefinition",json.valueToTree(definition));config.set("ruleReference",json.createObjectNode().put("id","rule").put("versionNo",1));config.set("ruleSnapshot",json.createObjectNode().put("id","rule").put("versionNo",1).put("ruleType","DLS").put("expression",json.writeValueAsString(expression)));
 var node=new PipelineDefinition.Node("session","SESSION","Session",SessionAggregationEngine.VERSION,List.of(),Map.of(),config,null,1000,0,PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
 return new NodeContext(node,input,Map.of("__messages",manifest,"__task",json.createObjectNode().put("currentExecutionId","execution")),Map.of(),PipelineRuntime.Mode.PRODUCTION,1);
 }
 JsonNode messages() throws Exception {return json.readTree("[{\"id\":\"a\",\"conversationId\":\"c\",\"sequenceNo\":1,\"speakerRole\":\"agent\",\"content\":\"offer offer\"},{\"id\":\"b\",\"conversationId\":\"c\",\"sequenceNo\":2,\"speakerRole\":\"customer\",\"content\":\"offer\"}]");}
 @Test void realRuntimeProducesSourceBoundCountAndEvidence() throws Exception {
 var context=context(messages(),json.createObjectNode());try(var runtime=new PipelineRuntime(List.of(new SessionAggregationEngine(json)),1,4)) {var run=runtime.execute(new PipelineDefinition(PipelineDefinition.SCHEMA,List.of(context.node())),context.input(),context.variables(),context.mode());assertEquals(PipelineRuntime.Status.SUCCESS,run.status());var fact=run.findings().getFirst();assertEquals(1,fact.value().intValue());assertEquals("matched_messages",fact.evidence().getFirst().details().path("unit").asText());assertEquals(2,fact.evidence().getFirst().details().path("messageIds").size());assertNotNull(fact.source().inputHash());}
 }
 @Test void mappedSubsetAndDuplicateIdentityAreRejected() throws Exception {
 var source=messages();var input=json.createObjectNode();input.set("messages",json.createArrayNode().add(source.get(0)));assertThrows(IllegalArgumentException.class,()->new SessionAggregationEngine(json).execute(context(source,input)));
 ((com.fasterxml.jackson.databind.node.ObjectNode)source.get(1)).put("id","a");assertThrows(IllegalArgumentException.class,()->new SessionAggregationEngine(json).execute(context(source,json.createObjectNode())));
 }
 @Test void mismatchedFrozenRuleVersionIsRejected() throws Exception {
 var context=context(messages(),json.createObjectNode());var config=context.node().config();((com.fasterxml.jackson.databind.node.ObjectNode)config.path("ruleReference")).put("versionNo",2);var definition=json.treeToValue(config.path("findingDefinition"),FindingDefinition.class);assertThrows(IllegalArgumentException.class,()->SessionAggregationEngine.validateConfiguration(json,SessionAggregationEngine.VERSION,config,definition));
 }
}
