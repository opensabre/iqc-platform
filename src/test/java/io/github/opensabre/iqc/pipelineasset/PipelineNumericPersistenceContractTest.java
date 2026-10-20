package io.github.opensabre.iqc.pipelineasset;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

/** Regression requirements for exact frozen release decoding and content identity. */
class PipelineNumericPersistenceContractTest {
    private String release(String number) {
        return """
          {"schemaVersion":"iqc-pipeline-release-v1",
           "pipeline":{"schemaVersion":"iqc-pipeline-v1","nodes":[{
             "nodeId":"rule","type":"RULE","name":"Rule","version":"rule-v1","dependsOn":[],"inputMapping":{},
             "config":{"value":NUMBER},"condition":{"pointer":"/value","operator":"GREATER_THAN","value":NUMBER},
             "timeoutMs":1000,"retries":0,"onError":"FAIL_PIPELINE","enabled":true,
             "outputSchema":{"type":"number","minimum":NUMBER}}]},
           "fusion":{"id":"fusion","version":1,"method":"PRIORITY","nodePriority":{},"overrideNodeByCode":{},"calibratedNodeIds":[]},
           "scoring":{"version":"iqc-score-v2","mode":"DEDUCTION","baseScore":100,"passingScore":60,"items":[]},"items":[]}
          """.replace("NUMBER",number);
    }
    @Test void storedReleaseReadRetainsExactNodeConditionAndSchemaDecimals() {
        var service=new PipelineAssetService(null,null,null,null,null,null,null,null,new ObjectMapper(),null,null,null,null,null);
        PipelineRelease restored=ReflectionTestUtils.invokeMethod(service,"read",release("0.900000000000000001"));
        var node=restored.pipeline().nodes().getFirst();
        assertThat(node.config().path("value").decimalValue()).isEqualByComparingTo("0.900000000000000001");
        assertThat(node.condition().value().decimalValue()).isEqualByComparingTo("0.900000000000000001");
        assertThat(node.outputSchema().path("minimum").decimalValue()).isEqualByComparingTo("0.900000000000000001");
    }
    @Test void distinctExactReleasesMustNotShareContentIdentity() {
        assertThat(PipelineAssetService.hash(release("0.900000000000000001")))
                .isNotEqualTo(PipelineAssetService.hash(release("0.900000000000000002")));
    }
    @Test void legacyCanonicalIdentityRemainsStableAcrossFieldOrderAndWhitespace() {
        assertThat(PipelineAssetService.hash("{\"b\":1.0,\"a\":2}"))
                .isEqualTo(PipelineAssetService.hash("{ \"a\": 2, \"b\": 1.0 }"));
        assertThat(PipelineAssetService.hash("{\"b\":1.0,\"a\":2}"))
                .isNotEqualTo(PipelineAssetService.hash("{\"b\":1,\"a\":2}"));
    }
}
