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
                .put("modelKey","a".repeat(64)).put("inputHash",input).put("deploymentRef","model-build:2026-10-25");
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
        assertThat(report.excludedReasons()).containsEntry("DIFFERENT_MODEL_DEPLOYMENT_OR_INPUT",1);
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

    NodeResult changed(NodeResult source,java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> edit){
        var metrics=(com.fasterxml.jackson.databind.node.ObjectNode)source.metrics().get("llmStructuredOutput");edit.accept(metrics);
        return new NodeResult(source.status(),source.findings(),source.variables(),Map.of("llmStructuredOutput",metrics),source.error());
    }
    com.fasterxml.jackson.databind.node.ObjectNode fingerprint(com.fasterxml.jackson.databind.node.ObjectNode metrics){
        return (com.fasterxml.jackson.databind.node.ObjectNode)metrics.path("invocationFingerprints").get(0);
    }
    @Test void missingNullDifferentAndMalformedDeploymentAreNeverComparable() throws Exception {
        var baseline=node("1","b".repeat(64));var pairs=new java.util.ArrayList<ModelConsistencyMetrics.Pair>();
        pairs.add(pair("a",baseline,"b",changed(baseline,m->fingerprint(m).remove("deploymentRef"))));
        pairs.add(pair("c",baseline,"d",changed(baseline,m->fingerprint(m).putNull("deploymentRef"))));
        pairs.add(pair("e",baseline,"f",changed(baseline,m->fingerprint(m).put("deploymentRef","other-build"))));
        for(var invalid:List.of(json.getNodeFactory().numberNode(1),json.getNodeFactory().textNode("bad deployment"),json.getNodeFactory().textNode("")))
            pairs.add(pair("x"+pairs.size(),baseline,"y"+pairs.size(),changed(baseline,m->fingerprint(m).set("deploymentRef",invalid))));
        var report=ModelConsistencyMetrics.evaluate(pairs,json);assertThat(report.schemaVersion()).isEqualTo("iqc-model-consistency-v2");
        assertThat(report.comparablePairs()).isZero();assertThat(report.agreementRate()).isNull();
        assertThat(report.excludedReasons()).containsEntry("UNMEASURED_DEPLOYMENT",2).containsEntry("DIFFERENT_MODEL_DEPLOYMENT_OR_INPUT",1).containsEntry("UNMEASURED_INVOCATION",3);
    }
    @Test void everyLogicalInvocationMustMatchInOrderAndHaveCompleteEvidence() throws Exception {
        var left=changed(node("1","b".repeat(64)),m->{
            m.put("logicalInvocations",2).put("validOutputs",2);var second=fingerprint(m).deepCopy();second.put("inputHash","c".repeat(64));
            ((com.fasterxml.jackson.databind.node.ArrayNode)m.path("invocationFingerprints")).add(second);
        });
        var right=changed(left,m->{});
        assertThat(ModelConsistencyMetrics.evaluate(List.of(pair("a",left,"b",right)),json).agreeingPairs()).isEqualTo(1);
        var reversed=changed(right,m->{var values=(com.fasterxml.jackson.databind.node.ArrayNode)m.path("invocationFingerprints");var first=values.remove(0);values.add(first);});
        assertThat(ModelConsistencyMetrics.evaluate(List.of(pair("a",left,"b",reversed)),json).excludedReasons()).containsEntry("DIFFERENT_MODEL_DEPLOYMENT_OR_INPUT",1);
        var missing=changed(right,m->((com.fasterxml.jackson.databind.node.ArrayNode)m.path("invocationFingerprints")).remove(1));
        assertThat(ModelConsistencyMetrics.evaluate(List.of(pair("a",left,"b",missing)),json).excludedReasons()).containsEntry("UNMEASURED_INVOCATION",1);
    }
    @Test void malformedIdentityFieldsCannotBeCoercedToMatchingText() throws Exception {
        var baseline=node("1","b".repeat(64));
        for(String field:List.of("schemaVersion","modelKey","inputHash")){
            var invalid=changed(baseline,m->fingerprint(m).put(field,123));
            assertThat(ModelConsistencyMetrics.evaluate(List.of(pair("a",baseline,"b",invalid)),json).excludedReasons()).containsEntry("UNMEASURED_INVOCATION",1);
        }
    }
}
