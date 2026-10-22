package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.pipeline.NodeResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ModelConsistencyMetricsTest {
    final ObjectMapper json=new ObjectMapper();
    NodeResult node(String number,String input) throws Exception {
        var f=new Finding("id-"+number,"VALUE","BUSINESS",Finding.ValueType.NUMBER,
                com.fasterxml.jackson.databind.node.DecimalNode.valueOf(new java.math.BigDecimal(number)),Finding.State.KNOWN,
                null,Finding.Severity.LOW,"explanation",List.of(),new Finding.Source("llm","LLM","v1",1,"legacy"));
        var metrics=json.createObjectNode().put("schemaVersion","iqc-llm-structured-metrics-v1")
                .put("logicalInvocations",1).put("validOutputs",1).put("invalidOrFailedOutputs",0);
        metrics.putArray("invocationFingerprints").addObject().put("schemaVersion","iqc-llm-invocation-fingerprint-v1")
                .put("modelKey","a".repeat(64)).put("inputHash",input);
        return new NodeResult(NodeResult.Status.SUCCESS,List.of(f),Map.of(),Map.of("llmStructuredOutput",metrics),null);
    }
    ModelConsistencyMetrics.Pair pair(String leftId,NodeResult left,String rightId,NodeResult right){
        return new ModelConsistencyMetrics.Pair(new ModelConsistencyMetrics.Observation(leftId,left),new ModelConsistencyMetrics.Observation(rightId,right));
    }
    @Test void exactValuesAndNumericEquivalenceUseOnlyComparableDenominator() throws Exception {
        var one=node("1.000000000000000001","b".repeat(64));
        var report=ModelConsistencyMetrics.evaluate(List.of(
                pair("a",one,"b",node("1.0000000000000000010","b".repeat(64))),
                pair("c",one,"d",node("1.000000000000000002","b".repeat(64))),
                pair("e",one,"f",node("1.000000000000000001","c".repeat(64)))),json);
        assertThat(report.expectedPairs()).isEqualTo(3);assertThat(report.comparablePairs()).isEqualTo(2);
        assertThat(report.agreeingPairs()).isEqualTo(1);assertThat(report.agreementRate()).isEqualByComparingTo("0.5");
        assertThat(report.comparisonCoverage()).isEqualByComparingTo("0.666667");
        assertThat(report.excludedReasons()).containsEntry("DIFFERENT_MODEL_OR_INPUT",1);
    }
    @Test void inheritedSameArtifactAndMissingInputsNeverCountAsAgreement() throws Exception {
        var n=node("1","b".repeat(64));
        var report=ModelConsistencyMetrics.evaluate(List.of(pair("same",n,"same",n),new ModelConsistencyMetrics.Pair(null,null),
                pair("a",n,"b",null)),json);
        assertThat(report.comparablePairs()).isZero();assertThat(report.agreementRate()).isNull();
        assertThat(report.comparisonCoverage()).isEqualByComparingTo("0");
        assertThat(report.excludedReasons()).containsEntry("SAME_ARTIFACT",1).containsEntry("MISSING_ARTIFACT",1).containsEntry("MISSING_NODE",1);
    }
    @Test void oldOrMalformedInvocationEvidenceAndFailedNodesAreExcluded() throws Exception {
        var n=node("1","b".repeat(64));var legacy=new NodeResult(n.status(),n.findings(),Map.of(),Map.of(),null);
        var malformed=node("1","invalid");
        var report=ModelConsistencyMetrics.evaluate(List.of(pair("a",n,"b",legacy),pair("c",n,"d",malformed),
                pair("e",n,"f",NodeResult.failure(NodeResult.Status.ERROR,"failed"))),json);
        assertThat(report.agreementRate()).isNull();
        assertThat(report.excludedReasons()).containsEntry("UNMEASURED_INVOCATION",2).containsEntry("UNSUCCESSFUL_NODE",1);
    }
    @Test void noDeclaredPairsDoNotProducePerfectScores(){
        var report=ModelConsistencyMetrics.evaluate(List.of(),json);
        assertThat(report.agreementRate()).isNull();assertThat(report.comparisonCoverage()).isNull();
    }
    @Test void calculationLeavesOriginalEvidenceUnchanged() throws Exception {
        var a=node("1","b".repeat(64));var b=node("1.0","b".repeat(64));String before=json.writeValueAsString(a);
        assertThat(ModelConsistencyMetrics.evaluate(List.of(pair("a",a,"b",b)),json).agreeingPairs()).isEqualTo(1);
        assertThat(json.writeValueAsString(a)).isEqualTo(before);
    }
}
