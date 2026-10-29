package io.github.opensabre.iqc.pipelineasset;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.opensabre.iqc.agent.dao.*;
import io.github.opensabre.iqc.agent.model.*;
import io.github.opensabre.iqc.finding.*;
import io.github.opensabre.iqc.pipelinebridge.SkillApiDetectorEngine;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;

/** Exercises the original publication service's exact frozen capability resolution without SQL. */
class SkillApiPublicationFreezeTest {
    private final ObjectMapper json=new ObjectMapper();
    private final QualityAgentMapper agents=mock(QualityAgentMapper.class);
    private final QualityAgentVersionMapper versions=mock(QualityAgentVersionMapper.class);
    private final FindingCatalogService findings=mock(FindingCatalogService.class);
    private final io.github.opensabre.iqc.agent.AgentAssetReferenceValidator capabilities=mock(io.github.opensabre.iqc.agent.AgentAssetReferenceValidator.class);
    private final PipelineAssetService service=new PipelineAssetService(null,null,null,null,agents,versions,capabilities,findings,json,null,null,null,null,null);
    private ObjectNode config() throws Exception {
        return (ObjectNode)json.readTree("""
            {"agentReference":{"id":"agent","versionNo":2},"skillReference":{"id":"skill","versionNo":3},
             "mcpReference":{"id":"server","versionNo":4},"findingReference":{"id":"fact","versionNo":1},
             "toolName":"inspect","toolInputSchema":{"type":"object"},"toolOutputSchema":{"type":"object"}}
            """);
    }
    private void fixture(String inputSchema) throws Exception {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"skill-api-freeze"),QualityAgentVersion.class);
        var current=new QualityAgent();current.setStatus("ENABLED");when(agents.selectById("agent")).thenReturn(current);
        var skill=json.createObjectNode().put("id","skill").put("code","CHECK").put("versionNo",3).put("instructions","Frozen instruction")
                .put("inputSchemaJson",inputSchema).put("outputSchemaJson","{\"type\":\"object\"}");
        var body=json.createObjectNode().put("schemaVersion","3.0");var snapshots=body.putObject("assetSnapshots");
        snapshots.putArray("skills").add(skill);snapshots.putArray("mcpServers").add(json.createObjectNode().put("id","server").put("versionNo",4));
        var version=new QualityAgentVersion();version.setConfigJson(json.writeValueAsString(body));when(versions.selectOne(any())).thenReturn(version);
        when(findings.publishedDefinition("fact",1)).thenReturn(new FindingDefinition(FindingDefinition.SCHEMA,"CHECK_OK","Check","BUSINESS",null,
                Finding.ValueType.BOOLEAN,List.of(),Finding.Severity.LOW,false,List.of(Finding.EvidenceType.EXTERNAL_RESULT)));
    }
    private void freeze(ObjectNode config){ReflectionTestUtils.invokeMethod(service,"freezeSkillApi",config,SkillApiDetectorEngine.VERSION);}
    @Test void publicationReplacesCallerSnapshotsWithExactPublishedBodies() throws Exception {
        fixture("{\"type\":\"object\"}");var config=config();
        config.set("skillSnapshot",json.createObjectNode().put("instructions","Spoofed latest"));
        freeze(config);
        assertThat(config.path("inputHashAlgorithm").asText()).isEqualTo("EXACT_JSON_V1");
        assertThat(config.path("skillSnapshot").path("instructions").asText()).isEqualTo("Frozen instruction");
        assertThat(config.path("skillSnapshot").path("versionNo").intValue()).isEqualTo(3);
        assertThat(config.path("mcpSnapshot").path("versionNo").intValue()).isEqualTo(4);
        assertThat(config.path("findingDefinition").path("code").asText()).isEqualTo("CHECK_OK");
        verify(capabilities).validateDetectionCapabilities("skill","server");
    }
    @Test void wrongSkillVersionNeverFallsBackToAvailableVersion() throws Exception {
        fixture("{\"type\":\"object\"}");var config=config();((ObjectNode)config.get("skillReference")).put("versionNo",5);
        assertThatThrownBy(()->freeze(config)).hasMessageContaining("唯一的冻结版本");
        verifyNoInteractions(findings);
    }
    @Test void unsupportedSkillSchemaFailsPublication() throws Exception {
        fixture("{\"type\":\"object\",\"oneOf\":[]}");var config=config();
        assertThatThrownBy(()->freeze(config)).hasMessageContaining("结构化协议无效");
    }
    @Test void explicitLegacyHashIsRetainedAndUnknownAlgorithmIsRejected() throws Exception {
        fixture("{\"type\":\"object\"}");
        var legacy=config().put("inputHashAlgorithm","LEGACY_JSON_V1");
        freeze(legacy);
        assertThat(legacy.path("inputHashAlgorithm").asText()).isEqualTo("LEGACY_JSON_V1");
        assertThatThrownBy(()->freeze(config().put("inputHashAlgorithm","UNKNOWN")))
                .hasMessageContaining("Unsupported structured tool input hash algorithm");
    }

}
