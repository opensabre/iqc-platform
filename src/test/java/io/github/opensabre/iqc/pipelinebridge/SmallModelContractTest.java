package io.github.opensabre.iqc.pipelinebridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.finding.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** Dedicated operations must preserve their own output semantics and immutable deployment identity. */
class SmallModelContractTest {
    private final ObjectMapper json=new ObjectMapper();
    private ObjectNode config(String operation){
        var config=json.createObjectNode().put("operation",operation).put("toolName","infer");
        config.set("modelSnapshot",json.createObjectNode().put("id","model").put("versionNo",3).put("modelName","classifier")
                .put("provider","LOCAL").put("endpoint","https://models.example.test").put("deploymentRef","classifier-v3"));
        config.set("mcpSnapshot",json.createObjectNode().put("id","inference-server").put("versionNo",4));
        for(String field:List.of("inputSchema","outputSchema","toolInputSchema","toolOutputSchema"))config.set(field,json.createObjectNode().put("type","object"));
        return config;
    }
    private FindingDefinition definition(Finding.ValueType type){return new FindingDefinition(FindingDefinition.SCHEMA,"MODEL_RESULT","Result","MODEL",null,type,
            type==Finding.ValueType.ENUM?List.of("positive","negative"):List.of(),Finding.Severity.LOW,false,List.of());}
    @Test void dedicatedOperationsRequireCompatibleCanonicalFindingTypes(){
        for(var entry:List.of(new Object[]{"CLASSIFICATION",Finding.ValueType.ENUM},new Object[]{"NER",Finding.ValueType.OBJECT},
                new Object[]{"EMOTION",Finding.ValueType.STRING},new Object[]{"INTENT",Finding.ValueType.ENUM},new Object[]{"SEMANTIC_MATCHING",Finding.ValueType.NUMBER}))
            SmallModelContract.validateConfiguration(SmallModelContract.VERSION,config((String)entry[0]),definition((Finding.ValueType)entry[1]));
        assertThatThrownBy(()->SmallModelContract.validateConfiguration(SmallModelContract.VERSION,config("NER"),definition(Finding.ValueType.BOOLEAN))).hasMessageContaining("Finding type differs");
    }
    @Test void gatewayIdentityAndExactToolVersionAreRequiredBeforePublication(){
        var credentials=config("CLASSIFICATION");
        ((ObjectNode)credentials.get("modelSnapshot")).put("endpoint","https://user:password@models.example.test");
        assertThatThrownBy(()->SmallModelContract.validateConfiguration(SmallModelContract.VERSION,credentials,definition(Finding.ValueType.BOOLEAN)))
                .hasMessageContaining("MODEL_ENDPOINT_IDENTITY_INVALID");
        var deployment=config("CLASSIFICATION");
        ((ObjectNode)deployment.get("modelSnapshot")).put("deploymentRef","classifier v3");
        assertThatThrownBy(()->SmallModelContract.validateConfiguration(SmallModelContract.VERSION,deployment,definition(Finding.ValueType.BOOLEAN)))
                .hasMessageContaining("MODEL_DEPLOYMENT_REFERENCE_INVALID");
        var tool=config("CLASSIFICATION");
        ((ObjectNode)tool.get("mcpSnapshot")).put("versionNo",4.5);
        assertThatThrownBy(()->SmallModelContract.validateConfiguration(SmallModelContract.VERSION,tool,definition(Finding.ValueType.BOOLEAN)))
                .hasMessageContaining("Exact inference tool identity");
        var collision=config("CLASSIFICATION").put("toolName","infer:other");
        assertThatThrownBy(()->SmallModelContract.validateConfiguration(SmallModelContract.VERSION,collision,definition(Finding.ValueType.BOOLEAN)))
                .hasMessageContaining("Exact inference tool identity");
    }
    @Test void missingDeploymentOrFractionalVersionCannotPublish(){
        var missing=config("CLASSIFICATION");((ObjectNode)missing.get("modelSnapshot")).remove("deploymentRef");
        assertThatThrownBy(()->SmallModelContract.validateConfiguration(SmallModelContract.VERSION,missing,definition(Finding.ValueType.BOOLEAN))).hasMessageContaining("deployment required");
        var fractional=config("CLASSIFICATION");((ObjectNode)fractional.get("modelSnapshot")).put("versionNo",1.5);
        assertThatThrownBy(()->SmallModelContract.validateConfiguration(SmallModelContract.VERSION,fractional,definition(Finding.ValueType.BOOLEAN))).hasMessageContaining("Exact model version");
    }
}
