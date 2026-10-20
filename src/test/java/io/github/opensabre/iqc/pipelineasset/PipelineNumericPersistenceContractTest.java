package io.github.opensabre.iqc.pipelineasset;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

/** Regression requirements for exact frozen release decoding and content identity. */
class PipelineNumericPersistenceContractTest {
    private String release(String number) {
        return """
          {"schemaVersion":"iqc-pipeline-release-v2",
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
    @Test void v2EnvelopesPreservePrecisionAndIgnoreArbitraryNestedProtocolLookalikes() throws Exception {
        var mapper=new ObjectMapper();
        String left=release("0.900000000000000001"),right=release("0.900000000000000002");
        for(String prefix:java.util.List.of("{\"release\":","{\"pipelineSnapshot\":{\"release\":")) {
            String suffix=prefix.contains("pipelineSnapshot")?"}}":"}";
            var tree=PipelineAssetService.readFrozenTree(mapper,prefix+left+suffix);
            assertThat(PipelineAssetService.usesExactNumbers(tree)).isTrue();
            assertThat(PipelineAssetService.hash(prefix+left+suffix)).isNotEqualTo(PipelineAssetService.hash(prefix+right+suffix));
        }
        assertThat(PipelineAssetService.usesExactNumbers(mapper.readTree("{\"config\":"+left+"}"))).isFalse();
        var legacy=left.replace(PipelineRelease.EXACT_SCHEMA,PipelineRelease.SCHEMA);
        PipelineRelease old=ReflectionTestUtils.invokeMethod(new PipelineAssetService(null,null,null,null,null,null,null,null,mapper,null,null,null,null,null),"read",legacy);
        assertThat(old.pipeline().nodes().getFirst().config().path("value").decimalValue()).isEqualByComparingTo("0.9");
        assertThat(PipelineAssetService.hash(left)).isEqualTo(io.github.opensabre.iqc.selector.SelectorAssetService.hash(left));
    }
    @Test void unknownReleaseProtocolIsRejected() {
        var service=new PipelineAssetService(null,null,null,null,null,null,null,null,new ObjectMapper(),null,null,null,null,null);
        assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(service,"read",release("1.0").replace(PipelineRelease.EXACT_SCHEMA,"iqc-pipeline-release-v99")))
                .hasMessageContaining("无效流水线版本");
    }
    @Test void legacyCanonicalIdentityRemainsStableAcrossFieldOrderAndWhitespace() {
        assertThat(PipelineAssetService.hash("{\"b\":1.0,\"a\":2}"))
                .isEqualTo(PipelineAssetService.hash("{ \"a\": 2, \"b\": 1.0 }"));
        assertThat(PipelineAssetService.hash("{\"b\":1.0,\"a\":2}"))
                .isNotEqualTo(PipelineAssetService.hash("{\"b\":1,\"a\":2}"));
    }
}
