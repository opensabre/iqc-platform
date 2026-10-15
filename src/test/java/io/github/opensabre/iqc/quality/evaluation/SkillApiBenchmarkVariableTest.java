package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.pipelineasset.PipelineRelease;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Original comparator must isolate the actual Skill request contract from its tool route. */
class SkillApiBenchmarkVariableTest {
    private final ObjectMapper json=new ObjectMapper();
    private ObjectNode config(int version) throws Exception {
        var config=json.createObjectNode().put("toolName","inspect");
        config.putObject("agentReference").put("id","agent").put("versionNo",2);
        config.putObject("skillReference").put("id","skill").put("versionNo",version);
        config.putObject("skillSnapshot").put("id","skill").put("code","CHECK").put("versionNo",version)
                .put("instructions","Check record").put("inputSchemaJson","{}").put("outputSchemaJson","{}");
        config.putObject("mcpSnapshot").put("id","server").put("versionNo",4);
        config.putObject("toolInputSchema").put("type","object");config.putObject("toolOutputSchema").put("type","object");
        config.putObject("findingDefinition");return config;
    }
    private PipelineRelease release(ObjectNode config){
        var node=new PipelineDefinition.Node("skill","SKILL_API","Skill","skill-api-v1",List.of(),Map.of(),config,null,3000,0,
                PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
        return new PipelineRelease(PipelineRelease.SCHEMA,new PipelineDefinition(PipelineDefinition.SCHEMA,List.of(node)),
                new io.github.opensabre.iqc.fusion.FusionPolicy("fusion",1,io.github.opensabre.iqc.fusion.FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of()),
                new io.github.opensabre.iqc.scoring.InspectionScoring.Policy(io.github.opensabre.iqc.scoring.InspectionScoring.POLICY_VERSION,
                        io.github.opensabre.iqc.scoring.InspectionScoring.Mode.DEDUCTION,100,60,List.of()),List.of());
    }
    private ObjectNode changed(ObjectNode baseline){
        var changed=baseline.deepCopy();((ObjectNode)changed.path("skillReference")).put("versionNo",4);
        ((ObjectNode)changed.path("skillSnapshot")).put("versionNo",4).put("instructions","Check revised record");
        ((ObjectNode)changed.path("agentReference")).put("versionNo",5);return changed;
    }
    private BenchmarkVariableComparator.Comparison compare(ObjectNode left,ObjectNode right,Integer slot){
        return BenchmarkVariableComparator.compare(release(left),release(right),new BenchmarkVariableComparator.Selection(
                BenchmarkVariableComparator.Dimension.SKILL,"skill",slot),json);
    }
    @Test void changedRequestContractHasIndependentVariableHashAndNoInventedPrompt() throws Exception {
        var baseline=config(3);var result=compare(baseline,changed(baseline),null);
        assertThat(result.baselineVariableHash()).isNotEqualTo(result.candidateVariableHash());
        assertThat(result.baselineEffectivePromptHash()).isNull();assertThat(result.candidateEffectivePromptHash()).isNull();
        assertThat(baseline.path("skillSnapshot").path("versionNo").intValue()).isEqualTo(3);
    }
    @Test void routeAndSchemaChangesCannotHideInsideSkillVariable() throws Exception {
        var baseline=config(3);
        for(String field:List.of("toolName","mcpSnapshot","toolInputSchema")){
            var candidate=changed(baseline);
            if(field.equals("toolName"))candidate.put(field,"other");
            else if(field.equals("mcpSnapshot"))((ObjectNode)candidate.path(field)).put("versionNo",9);
            else ((ObjectNode)candidate.path(field)).put("additionalProperties",false);
            assertThatThrownBy(()->compare(baseline,candidate,0)).hasMessageContaining("变量之外");
        }
    }
    @Test void unchangedSkillAnchorOnlyAndInvalidSlotAreRejected() throws Exception {
        var baseline=config(3);var anchor=baseline.deepCopy();((ObjectNode)anchor.path("agentReference")).put("versionNo",9);
        assertThatThrownBy(()->compare(baseline,anchor,0)).hasMessageContaining("没有实际语义变化");
        assertThatThrownBy(()->compare(baseline,changed(baseline),1)).hasMessageContaining("slot");
    }
    @Test void requestHashAlgorithmCannotChangeInsideAssetVariable() throws Exception {
        var baseline=config(3).put("inputHashAlgorithm","EXACT_JSON_V1");
        var candidate=changed(baseline).put("inputHashAlgorithm","LEGACY_JSON_V1");
        assertThatThrownBy(()->compare(baseline,candidate,0)).hasMessageContaining("变量之外");
    }

}
