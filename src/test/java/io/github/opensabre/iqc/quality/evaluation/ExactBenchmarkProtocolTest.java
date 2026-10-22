package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.pipeline.PipelineDefinition;
import io.github.opensabre.iqc.pipelineasset.PipelineRelease;
import io.github.opensabre.iqc.fusion.FusionPolicy;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Exercises the actual single-variable comparator, including string-encoded frozen Agent configuration. */
class ExactBenchmarkProtocolTest {
    private final ObjectMapper json=new ObjectMapper();
    private PipelineRelease release(String schema,String type,ObjectNode config) {
        var node=new PipelineDefinition.Node("node",type,"Node","1",List.of(),Map.of(),config,null,1000,0,
                PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
        return new PipelineRelease(schema,new PipelineDefinition(PipelineDefinition.SCHEMA,List.of(node)),
                new FusionPolicy("fusion",1,FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of()),
                new InspectionScoring.Policy(InspectionScoring.POLICY_VERSION,InspectionScoring.Mode.DEDUCTION,100,60,List.of()),List.of());
    }
    private ObjectNode decimal(String value) { return json.createObjectNode().put("amount",new BigDecimal(value)); }
    private BenchmarkVariableComparator.Comparison pipeline(String schema,String left,String right) {
        return BenchmarkVariableComparator.compare(release(schema,"RULE",decimal(left)),release(schema,"RULE",decimal(right)),
                new BenchmarkVariableComparator.Selection(BenchmarkVariableComparator.Dimension.PIPELINE,null,null),json);
    }
    @Test void exactPipelineVariableDistinguishesAdjacentDecimalsButNotScale() {
        var compared=pipeline(PipelineRelease.EXACT_SCHEMA,"0.900000000000000001","0.900000000000000002");
        assertThat(compared.baselineVariableHash()).isNotEqualTo(compared.candidateVariableHash());
        assertThat(compared.baselineReleaseProjectionHash()).isNotEqualTo(compared.candidateReleaseProjectionHash());
        assertThatThrownBy(() -> pipeline(PipelineRelease.EXACT_SCHEMA,"0.90","0.900"))
                .hasMessageContaining("没有实际语义变化");
    }
    @Test void legacyKeepsHistoricalDigestAndMixedProtocolsAreRejected() {
        assertThatThrownBy(() -> pipeline(PipelineRelease.SCHEMA,"0.900000000000000001","0.900000000000000002"))
                .hasMessageContaining("没有实际语义变化");
        assertThatThrownBy(() -> BenchmarkVariableComparator.compare(release(PipelineRelease.SCHEMA,"RULE",decimal("1")),
                release(PipelineRelease.EXACT_SCHEMA,"RULE",decimal("2")),
                new BenchmarkVariableComparator.Selection(BenchmarkVariableComparator.Dimension.PIPELINE,null,null),json));
    }
    private ObjectNode agent(String threshold) {
        var cfg=json.createObjectNode().put("schemaVersion","3.0").put("systemPrompt","Inspect processed conversation")
                .put("primaryModelProfileId","model");
        cfg.putObject("assetSnapshots").putObject("primaryModel").put("id","model")
                .put("temperature",new BigDecimal(threshold));
        var config=json.createObjectNode();
        config.putObject("agentSnapshot").put("configJson",cfg.toString());
        config.putObject("agentReference").put("id","agent").put("versionNo",1);
        return config;
    }
    @Test void exactStringEncodedAgentModelNumbersStayExactDuringProjection() {
        var left=release(PipelineRelease.EXACT_SCHEMA,"LLM",agent("0.900000000000000001"));
        var right=release(PipelineRelease.EXACT_SCHEMA,"LLM",agent("0.900000000000000002"));
        String original=left.pipeline().nodes().getFirst().config().toString();
        var comparison=BenchmarkVariableComparator.compare(left,right,new BenchmarkVariableComparator.Selection(
                BenchmarkVariableComparator.Dimension.MODEL,"node",0),json);
        assertThat(comparison.baselineVariableHash()).isNotEqualTo(comparison.candidateVariableHash());
        assertThat(left.pipeline().nodes().getFirst().config().toString()).isEqualTo(original);
        var outside=agent("0.900000000000000002"); outside.put("outside",true);
        assertThatThrownBy(() -> BenchmarkVariableComparator.compare(left,release(PipelineRelease.EXACT_SCHEMA,"LLM",outside),
                new BenchmarkVariableComparator.Selection(BenchmarkVariableComparator.Dimension.MODEL,"node",0),json))
                .hasMessageContaining("变量之外");
    }
}
