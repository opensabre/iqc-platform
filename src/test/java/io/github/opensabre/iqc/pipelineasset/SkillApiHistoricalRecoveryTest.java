package io.github.opensabre.iqc.pipelineasset;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.opensabre.iqc.agent.AgentAssetReferenceValidator;
import io.github.opensabre.iqc.agent.dao.QualityAgentMapper;
import io.github.opensabre.iqc.agent.model.QualityAgent;
import io.github.opensabre.iqc.finding.*;
import io.github.opensabre.iqc.fusion.FusionPolicy;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.pipelineasset.dao.*;
import io.github.opensabre.iqc.pipelineasset.model.*;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Validates published historical task proof through the original service, not a reconstructed latest config. */
class SkillApiHistoricalRecoveryTest {
    private final ObjectMapper json=new ObjectMapper();
    private final PipelineAssetMapper assets=mock(PipelineAssetMapper.class);
    private final PipelineAssetVersionMapper versions=mock(PipelineAssetVersionMapper.class);
    private final QualityAgentMapper agents=mock(QualityAgentMapper.class);
    private final AgentAssetReferenceValidator capabilities=mock(AgentAssetReferenceValidator.class);
    private final FindingCatalogService findings=mock(FindingCatalogService.class);
    private final PipelineRuntime runtime=mock(PipelineRuntime.class);
    private final PipelineAssetService service=new PipelineAssetService(assets,versions,null,null,agents,null,capabilities,findings,json,null,runtime,null,null,null);
    private ObjectNode snapshot() throws Exception {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"skill-history"),PipelineAssetVersion.class);
        var config=json.createObjectNode().put("toolName","inspect");
        config.set("agentReference",json.createObjectNode().put("id","agent").put("versionNo",2));
        config.set("findingReference",json.createObjectNode().put("id","fact").put("versionNo",1));
        config.set("skillSnapshot",json.createObjectNode().put("id","skill").put("code","CHECK").put("versionNo",3)
                .put("instructions","Historical instruction").put("inputSchemaJson","{\"type\":\"object\"}").put("outputSchemaJson","{\"type\":\"object\"}"));
        config.set("mcpSnapshot",json.createObjectNode().put("id","server").put("versionNo",4));
        config.set("toolInputSchema",json.createObjectNode().put("type","object"));config.set("toolOutputSchema",json.createObjectNode().put("type","object"));
        config.set("findingDefinition",json.valueToTree(new FindingDefinition(FindingDefinition.SCHEMA,"CHECK_OK","Check","BUSINESS",null,
                Finding.ValueType.BOOLEAN,List.of(),Finding.Severity.LOW,false,List.of())));
        var node=new PipelineDefinition.Node("skill","SKILL_API","Skill","skill-api-v1",List.of(),Map.of(),config,null,3000,0,PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
        var release=new PipelineRelease(PipelineRelease.SCHEMA,new PipelineDefinition(PipelineDefinition.SCHEMA,List.of(node)),
                new FusionPolicy("priority",1,FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of()),
                new InspectionScoring.Policy(InspectionScoring.POLICY_VERSION,InspectionScoring.Mode.DEDUCTION,100,60,List.of()),List.of());
        // Task and publication bodies are both read from persisted JSON, including its number node types.
        var body=json.readTree(json.writeValueAsString(release));var hash=PipelineAssetService.hash(body.toString());
        var version=new PipelineAssetVersion();version.setPipelineId("pipeline");version.setVersionNo(1);version.setSnapshotJson(body.toString());version.setContentHash(hash);
        when(versions.selectOne(any())).thenReturn(version);
        var task=json.createObjectNode();task.putObject("executionStrategy").put("schemaVersion",PipelineAssetService.TASK_SCHEMA).put("mode","PIPELINE");
        var marker=task.putObject("pipelineSnapshot").put("pipelineId","pipeline").put("versionNo",1).put("trial",false).put("contentHash",hash);
        marker.set("release",body);return task;
    }
    @Test void historicalRecoveryChecksPublicationWithoutReadingCurrentAvailability() throws Exception {
        var snapshot=snapshot();var recovered=service.historicalPublishedTaskRelease(snapshot);
        assertThat(recovered.pipeline().nodes().getFirst().config().path("skillSnapshot").path("instructions").asText()).isEqualTo("Historical instruction");
        assertThat(recovered.pipeline().nodes().getFirst().config().path("mcpSnapshot").path("versionNo").intValue()).isEqualTo(4);
        verify(runtime).validateStructure(any());verifyNoInteractions(assets,agents,capabilities,findings);
    }
    @Test void changedReleaseCannotBeRecoveredUsingOldPublishedDigest() throws Exception {
        var snapshot=snapshot();((ObjectNode)snapshot.path("pipelineSnapshot").path("release").path("pipeline").path("nodes").get(0).path("config").path("skillSnapshot")).put("instructions","Latest replacement");
        assertThatThrownBy(()->service.historicalPublishedTaskRelease(snapshot)).hasMessageContaining("任务流水线摘要不一致");
        verifyNoInteractions(assets,agents,capabilities,findings);
    }
    @Test void newExecutionChecksCapabilityAvailabilityAndPreservesFrozenVersionBody() throws Exception {
        var snapshot=snapshot();var asset=new PipelineAsset();asset.setStatus("ACTIVE");when(assets.selectById("pipeline")).thenReturn(asset);
        var agent=new QualityAgent();agent.setStatus("ENABLED");when(agents.selectById("agent")).thenReturn(agent);
        doThrow(new IllegalStateException("Skill disabled fixture")).when(capabilities).validateDetectionCapabilities("skill","server");
        assertThatThrownBy(()->service.publishedTaskRelease(snapshot)).hasMessageContaining("Skill disabled fixture");
        assertThat(snapshot.path("pipelineSnapshot").path("release").path("pipeline").path("nodes").get(0).path("config").path("skillSnapshot").path("versionNo").intValue()).isEqualTo(3);
    }
}
