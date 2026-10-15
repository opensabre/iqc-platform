package io.github.opensabre.iqc.pipelineasset;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.opensabre.iqc.agent.dao.*;
import io.github.opensabre.iqc.agent.model.*;
import io.github.opensabre.iqc.finding.*;
import io.github.opensabre.iqc.pipelinebridge.SmallModelContract;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;

/** Exercises the original publication service's exact frozen capability resolution without SQL. */
class SmallModelPublicationFreezeTest {
    private final ObjectMapper json=new ObjectMapper();
    private final QualityAgentMapper agents=mock(QualityAgentMapper.class);
    private final QualityAgentVersionMapper versions=mock(QualityAgentVersionMapper.class);
    private final FindingCatalogService findings=mock(FindingCatalogService.class);
    private final io.github.opensabre.iqc.agent.AgentAssetReferenceValidator capabilities=mock(io.github.opensabre.iqc.agent.AgentAssetReferenceValidator.class);
    private final PipelineAssetService service=new PipelineAssetService(null,null,null,null,agents,versions,capabilities,findings,json,null,null,null,null,null);
    private ObjectNode config() throws Exception {
        return (ObjectNode)json.readTree("""
            {"agentReference":{"id":"agent","versionNo":2},"modelReference":{"id":"model","versionNo":3},
             "mcpReference":{"id":"server","versionNo":4},"findingReference":{"id":"fact","versionNo":1},
             "operation":"CLASSIFICATION","inputSchema":{"type":"object"},"outputSchema":{"type":"object"},"toolName":"inspect","toolInputSchema":{"type":"object"},"toolOutputSchema":{"type":"object"}}
            """);
    }
    private void fixture(String inputSchema) throws Exception {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"skill-api-freeze"),QualityAgentVersion.class);
        var current=new QualityAgent();current.setStatus("ENABLED");when(agents.selectById("agent")).thenReturn(current);
        var model=json.createObjectNode().put("id","model").put("versionNo",3).put("provider","OLLAMA")
                .put("modelName","classifier").put("endpoint","https://models.example.test").put("deploymentRef","classifier-v3");
        var body=json.createObjectNode().put("schemaVersion","3.0");var snapshots=body.putObject("assetSnapshots");
        snapshots.set("primaryModel",model);snapshots.putArray("mcpServers").add(json.createObjectNode().put("id","server").put("versionNo",4));
        var version=new QualityAgentVersion();version.setConfigJson(json.writeValueAsString(body));when(versions.selectOne(any())).thenReturn(version);
        when(findings.publishedDefinition("fact",1)).thenReturn(new FindingDefinition(FindingDefinition.SCHEMA,"CHECK_OK","Check","BUSINESS",null,
                Finding.ValueType.BOOLEAN,List.of(),Finding.Severity.LOW,false,List.of(Finding.EvidenceType.EXTERNAL_RESULT)));
    }
    private void freeze(ObjectNode config){ReflectionTestUtils.invokeMethod(service,"freezeStructuredTool",config,SmallModelContract.VERSION,true);}
    @Test void publicationReplacesCallerSnapshotsWithExactPublishedBodies() throws Exception {
        fixture("{\"type\":\"object\"}");var config=config();
        config.set("modelSnapshot",json.createObjectNode().put("deploymentRef","spoofed-latest"));
        freeze(config);
        assertThat(config.path("modelSnapshot").path("deploymentRef").asText()).isEqualTo("classifier-v3");
        assertThat(config.path("modelSnapshot").path("versionNo").intValue()).isEqualTo(3);
        assertThat(config.path("mcpSnapshot").path("versionNo").intValue()).isEqualTo(4);
        assertThat(config.path("findingDefinition").path("code").asText()).isEqualTo("CHECK_OK");
        verify(capabilities).validateInferenceCapabilities("model","server");
    }
    @Test void wrongModelVersionNeverFallsBackToAvailableVersion() throws Exception {
        fixture("{\"type\":\"object\"}");var config=config();((ObjectNode)config.get("modelReference")).put("versionNo",5);
        assertThatThrownBy(()->freeze(config)).hasMessageContaining("唯一的冻结版本");
        verifyNoInteractions(findings);
    }
    @Test void unsupportedInferenceSchemaFailsPublication() throws Exception {
        fixture("unused");var config=config();
        config.set("inputSchema",json.readTree("{\"type\":\"object\",\"oneOf\":[]}"));
        assertThatThrownBy(()->freeze(config)).hasMessageContaining("结构化协议无效");
    }
    @Test void exactFallbackModelCanBeSelectedWithoutUsingPrimary() throws Exception {
        fixture("unused");
        var version=versions.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<QualityAgentVersion>lambdaQuery());var body=(ObjectNode)json.readTree(version.getConfigJson());
        var snapshots=(ObjectNode)body.path("assetSnapshots");
        var fallback=snapshots.path("primaryModel").deepCopy();((ObjectNode)fallback).put("id","fallback").put("versionNo",7);
        snapshots.putArray("fallbackModels").add(fallback);version.setConfigJson(json.writeValueAsString(body));
        var config=config();((ObjectNode)config.path("modelReference")).put("id","fallback").put("versionNo",7);
        freeze(config);assertThat(config.path("modelSnapshot").path("id").asText()).isEqualTo("fallback");
        verify(capabilities).validateInferenceCapabilities("fallback","server");
    }
}
