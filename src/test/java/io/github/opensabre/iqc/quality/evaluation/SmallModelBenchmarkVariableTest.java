package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.pipelineasset.PipelineRelease;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Original comparator must isolate the actual model request contract from its tool route. */
class SmallModelBenchmarkVariableTest {
    private final ObjectMapper json=new ObjectMapper();
    private ObjectNode config(int version) throws Exception {
        var config=json.createObjectNode().put("toolName","inspect").put("operation","CLASSIFICATION");
        config.putObject("agentReference").put("id","agent").put("versionNo",2);
        config.putObject("modelReference").put("id","model").put("versionNo",version);
        config.putObject("modelSnapshot").put("id","model").put("versionNo",version).put("provider","OLLAMA")
                .put("modelName","classifier").put("endpoint","https://models.example.test").put("deploymentRef","classifier-v3");
        config.putObject("mcpSnapshot").put("id","server").put("versionNo",4);
        for(String field:List.of("inputSchema","outputSchema","toolInputSchema","toolOutputSchema"))config.putObject(field).put("type","object");
        config.set("findingDefinition",json.valueToTree(new io.github.opensabre.iqc.finding.FindingDefinition(
                io.github.opensabre.iqc.finding.FindingDefinition.SCHEMA,"MODEL_RESULT","Model","MODEL",null,
                io.github.opensabre.iqc.finding.Finding.ValueType.STRING,List.of(),io.github.opensabre.iqc.finding.Finding.Severity.LOW,false,List.of())));
        return config;
    }
    private PipelineRelease release(ObjectNode config){
        var node=new PipelineDefinition.Node("model","SMALL_MODEL","Model","small-model-v1",List.of(),Map.of(),config,null,3000,0,
                PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
        return new PipelineRelease(PipelineRelease.SCHEMA,new PipelineDefinition(PipelineDefinition.SCHEMA,List.of(node)),
                new io.github.opensabre.iqc.fusion.FusionPolicy("fusion",1,io.github.opensabre.iqc.fusion.FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of()),
                new io.github.opensabre.iqc.scoring.InspectionScoring.Policy(io.github.opensabre.iqc.scoring.InspectionScoring.POLICY_VERSION,
                        io.github.opensabre.iqc.scoring.InspectionScoring.Mode.DEDUCTION,100,60,List.of()),List.of());
    }
    private ObjectNode changed(ObjectNode baseline){
        var changed=baseline.deepCopy();((ObjectNode)changed.path("modelReference")).put("versionNo",4);
        ((ObjectNode)changed.path("modelSnapshot")).put("versionNo",4).put("deploymentRef","classifier-v4");
        ((ObjectNode)changed.path("agentReference")).put("versionNo",5);return changed;
    }
    private BenchmarkVariableComparator.Comparison compare(ObjectNode left,ObjectNode right,Integer slot){
        return BenchmarkVariableComparator.compare(release(left),release(right),new BenchmarkVariableComparator.Selection(
                BenchmarkVariableComparator.Dimension.MODEL,"model",slot),json);
    }
    @Test void changedRequestContractHasIndependentVariableHashAndNoInventedPrompt() throws Exception {
        var baseline=config(3);var result=compare(baseline,changed(baseline),null);
        assertThat(result.baselineVariableHash()).isNotEqualTo(result.candidateVariableHash());
        assertThat(result.baselineEffectivePromptHash()).isNull();assertThat(result.candidateEffectivePromptHash()).isNull();
        assertThat(baseline.path("modelSnapshot").path("versionNo").intValue()).isEqualTo(3);
    }
    @Test void routeAndSchemaChangesCannotHideInsideModelVariable() throws Exception {
        var baseline=config(3);
        for(String field:List.of("toolName","mcpSnapshot","toolInputSchema")){
            var candidate=changed(baseline);
            if(field.equals("toolName"))candidate.put(field,"other");
            else if(field.equals("mcpSnapshot"))((ObjectNode)candidate.path(field)).put("versionNo",9);
            else ((ObjectNode)candidate.path(field)).put("additionalProperties",false);
            assertThatThrownBy(()->compare(baseline,candidate,0)).hasMessageContaining("变量之外");
        }
    }
    @Test void unchangedModelAnchorOnlyAndInvalidSlotAreRejected() throws Exception {
        var baseline=config(3);var anchor=baseline.deepCopy();((ObjectNode)anchor.path("agentReference")).put("versionNo",9);
        assertThatThrownBy(()->compare(baseline,anchor,0)).hasMessageContaining("没有实际语义变化");
        assertThatThrownBy(()->compare(baseline,changed(baseline),1)).hasMessageContaining("slot");
    }
    @Test void mismatchedModelReferenceAndChangedOperationAreRejected() throws Exception {
        var baseline=config(3);var mismatch=changed(baseline);((ObjectNode)mismatch.path("modelReference")).put("versionNo",99);
        assertThatThrownBy(()->compare(baseline,mismatch,0)).hasMessageContaining("不一致");
        var operation=changed(baseline).put("operation","INTENT");
        assertThatThrownBy(()->compare(baseline,operation,0)).hasMessageContaining("变量之外");
    }
}
