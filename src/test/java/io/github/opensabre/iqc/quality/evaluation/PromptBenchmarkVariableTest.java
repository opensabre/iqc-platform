package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.agent.AgentConfiguration;
import io.github.opensabre.iqc.pipeline.PipelineDefinition;
import io.github.opensabre.iqc.pipelineasset.PipelineRelease;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Independent Prompt provenance must not hide changes to other experimental variables. */
class PromptBenchmarkVariableTest {
    private final ObjectMapper json=new ObjectMapper();
    private ObjectNode config(String prompt,String source){
        var config=json.createObjectNode().put("schemaVersion","3.0").put("systemPrompt",prompt).put("primaryModelProfileId","model");
        config.putArray("fallbackModelProfileIds");config.putArray("skillIds");config.putArray("mcpServerIds");
        var assets=config.putObject("assetSnapshots");assets.putObject("primaryModel").put("id","model").put("modelName","baseline");
        assets.putArray("fallbackModels");assets.putArray("skills");assets.putArray("mcpServers");
        if(source!=null){var reference=new AgentConfiguration.PromptReference(source,2,null);
            config.set("promptAsset",json.valueToTree(new AgentConfiguration.PromptReference(source,2,AgentConfiguration.promptHash(reference,prompt))));}
        return config;
    }
    private PipelineRelease release(ObjectNode agent){
        var config=json.createObjectNode();config.putObject("agentReference").put("id","agent").put("versionNo",1);
        config.putObject("agentSnapshot").put("id","agent").set("configJson",agent);
        var node=new PipelineDefinition.Node("llm","LLM","Detector","1",List.of(),Map.of(),config,null,3000,0,PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
        return new PipelineRelease(PipelineRelease.EXACT_SCHEMA,new PipelineDefinition(PipelineDefinition.SCHEMA,List.of(node)),
                new io.github.opensabre.iqc.fusion.FusionPolicy("fusion",1,io.github.opensabre.iqc.fusion.FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of()),
                new io.github.opensabre.iqc.scoring.InspectionScoring.Policy(io.github.opensabre.iqc.scoring.InspectionScoring.POLICY_VERSION,
                        io.github.opensabre.iqc.scoring.InspectionScoring.Mode.DEDUCTION,100,60,List.of()),List.of());
    }
    private BenchmarkVariableComparator.Comparison compare(ObjectNode left,ObjectNode right,BenchmarkVariableComparator.Dimension dimension){
        return BenchmarkVariableComparator.compare(release(left),release(right),new BenchmarkVariableComparator.Selection(dimension,"llm",dimension==BenchmarkVariableComparator.Dimension.SKILL?0:null),json);
    }
    @Test void inlineToVersionedAndReversePermitOnlyActualPromptChanges(){
        var inline=config("original prompt",null);var versioned=config("revised prompt","instructions");
        var originalInline=inline.deepCopy();var originalVersioned=versioned.deepCopy();
        for(boolean reverse:List.of(false,true)){
            var result=compare(reverse?versioned:inline,reverse?inline:versioned,BenchmarkVariableComparator.Dimension.PROMPT);
            assertThat(result.baselineEffectivePromptHash()).isNotEqualTo(result.candidateEffectivePromptHash());
            assertThat(result.allowedPaths()).anyMatch(path->path.endsWith("/promptAsset"));
        }
        assertThat(inline).isEqualTo(originalInline);assertThat(versioned).isEqualTo(originalVersioned);
    }
    @Test void provenanceOnlyCannotManufacturePromptExperiment(){
        assertThatThrownBy(()->compare(config("same prompt",null),config("same prompt","instructions"),BenchmarkVariableComparator.Dimension.PROMPT))
                .hasMessageContaining("实际有效提示");
        assertThatThrownBy(()->compare(config("same prompt","old"),config("same prompt","new"),BenchmarkVariableComparator.Dimension.PROMPT))
                .hasMessageContaining("实际有效提示");
    }
    @Test void promptExperimentCannotAlsoChangeModelAndRejectsCorruptedProvenance(){
        var left=config("original","instructions");var right=config("revised","instructions");
        ((ObjectNode)right.path("assetSnapshots").path("primaryModel")).put("modelName","other");
        assertThatThrownBy(()->compare(left,right,BenchmarkVariableComparator.Dimension.PROMPT)).hasMessageContaining("变量之外");
        var corrupt=config("revised","instructions");((ObjectNode)corrupt.path("promptAsset")).put("contentHash","bad");
        assertThatThrownBy(()->compare(left,corrupt,BenchmarkVariableComparator.Dimension.PROMPT)).hasMessageContaining("摘要不一致");
    }
    @Test void modelExperimentCannotSmuggleChangedPromptSource(){
        var left=config("same prompt","old");var right=config("same prompt","new");
        ((ObjectNode)right.path("assetSnapshots").path("primaryModel")).put("modelName","other");
        assertThatThrownBy(()->compare(left,right,BenchmarkVariableComparator.Dimension.MODEL)).hasMessageContaining("变量之外");
    }
}
