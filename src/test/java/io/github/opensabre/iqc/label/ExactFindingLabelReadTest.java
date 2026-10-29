package io.github.opensabre.iqc.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.action.*;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.fusion.*;
import io.github.opensabre.iqc.pipeline.PipelineDefinition;
import io.github.opensabre.iqc.pipelineasset.*;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.label.model.InspectionLabelResult;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual directory/hash and historical label reads; production source proof and storage are isolated. */
class ExactFindingLabelReadTest {
    private final ObjectMapper json=new ObjectMapper();
    private InspectionTask task;
    private ConversationInspectionResult result;
    private InspectionLabelResult row;
    private FindingLabelReadService reader;
    private void fixture(String schema) throws Exception {
        var directory=json.readTree("""
            {"schemaVersion":"2.0","labels":[{"id":"ratio","versionNo":1,"name":"Ratio","targetRole":"agent",
             "values":[{"valueCode":"VALUE","valueType":"PERCENTAGE"}]}]}
            """);
        var labels=new FindingLabelPolicy(FindingLabelPolicy.SCHEMA,List.of(new FindingLabelPolicy.Binding("ratio",1,"VALUE","RATIO")),directory);
        var fusion=new FusionPolicy("fusion",1,FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of());
        var action=new ActionPolicy.Action("label",ActionExecutionContract.Type.UPDATE_LABEL,
                new ActionPolicy.Condition(ActionPolicy.Kind.ALWAYS,null,null,List.of()),ActionPolicy.Idempotency.PRODUCTION_SOURCE_V1,
                null,ActionPolicy.FailureHandling.RECORD_ONLY,json.createObjectNode().put("labelId","ratio").put("valueCode","VALUE"));
        var policy=new ActionPolicy(ActionPolicy.SCHEMA,List.of(action));String policyBody=json.writeValueAsString(policy);
        var node=new PipelineDefinition.Node("rule","RULE","Rule","1",List.of(),Map.of(),json.createObjectNode().put("amount",new BigDecimal("0.900000000000000001")),null,1000,0,
                PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
        var release=new PipelineRelease(schema,new PipelineDefinition(PipelineDefinition.SCHEMA,List.of(node)),fusion,
                new InspectionScoring.Policy(InspectionScoring.POLICY_VERSION,InspectionScoring.Mode.DEDUCTION,100,60,List.of()),List.of(),null,policy,labels,null,null);
        var root=json.createObjectNode();root.putObject("pipelineSnapshot").put("contentHash",PipelineAssetService.hash(json.writeValueAsString(release))).set("release",json.valueToTree(release));
        task=new InspectionTask();task.setId("task");task.setCreatedBy("alice");task.setRuleSnapshotJson(root.toString());task.setLabelScopeSnapshotJson(directory.toString());
        result=new ConversationInspectionResult();result.setId("result");result.setTaskId("task");result.setExecutionId("execution");result.setConversationId("conversation");
        var fact=new Finding("fact","RATIO","Quality",Finding.ValueType.NUMBER,json.getNodeFactory().numberNode(new BigDecimal("0.900000000000000001")),Finding.State.KNOWN,null,
                Finding.Severity.LOW,"ratio",List.of(),new Finding.Source("rule","RULE","1",1,null));
        var fused=FindingFusion.fuse(List.of(fact),fusion).getFirst();
        var projection=FindingLabelProjection.project(labels.projectionBinding(labels.bindings().getFirst()),fused);
        String body=json.writeValueAsString(projection);
        String input=json.createObjectNode().put("taskSnapshotJson",task.getRuleSnapshotJson()).toString();String inputHash=SelectorAssetService.hash(input);
        var context=new ActionPolicy.Context(Map.of("RATIO",fused),Map.of(),InspectionScoring.ScoreStatus.FINAL,BigDecimal.valueOf(100),"NONE","QUALIFIED");
        var reference=new ActionProductionSourceValidator.Reference("task","execution","conversation","result","alice");
        var validated=new ActionProductionSourceValidator.Validated(reference,null,"result",input,inputHash,policy,context,null);
        var source=new ActionExecutionContract.Source("task","execution","conversation","result","result",SelectorAssetService.hash(policyBody),"label","alice");
        String key=source.idempotencyKey(ActionExecutionContract.Type.UPDATE_LABEL);
        var origin=new ActionExecution();origin.setId(key);origin.setIdempotencyKey(key);origin.setTaskId("task");origin.setExecutionId("execution");origin.setConversationId("conversation");
        origin.setSourceResultId("result");origin.setDecisionReference("result");origin.setActionPolicyHash(source.actionPolicyHash());origin.setActionId("label");origin.setActionType("UPDATE_LABEL");
        origin.setOwner("alice");origin.setPolicySnapshotJson(policyBody);origin.setInputSnapshotJson(input);origin.setInputSnapshotHash(inputHash);
        origin.setAttemptCount(1);origin.setClaimToken("a".repeat(32));origin.setClaimedAtUtc(LocalDateTime.now());
        row=new InspectionLabelResult();row.setId("fl-"+SelectorAssetService.hash(json.writeValueAsString(List.of("result","ratio","VALUE"))).substring(0,60));
        row.setConversationResultId("result");row.setCreatedBy("alice");row.setLabelId("ratio");row.setLabelVersionNo(1);row.setValueCode("VALUE");row.setGenerationSource(LabelResultSourceValidator.FINDING);
        row.setSourceFindingCode("RATIO");row.setSourceActionExecutionId(key);row.setSourceInputHash(inputHash);row.setValueJson(body);row.setSourceProjectionHash(SelectorAssetService.hash(body));
        var sources=mock(ActionProductionSourceValidator.class);when(sources.validateHistorical(reference)).thenReturn(validated);
        var actions=mock(ActionExecutionMapper.class);when(actions.selectById(key)).thenReturn(origin);
        reader=new FindingLabelReadService(sources,actions,json);
    }
    @Test void v2DirectoryHashAcceptsExactNodeConfigurationAndRejectsTampering() throws Exception {
        fixture(PipelineRelease.EXACT_SCHEMA);
        assertThat(FindingLabelTaskDirectory.expected(task,json).path("labels").get(0).path("id").asText()).isEqualTo("ratio");
        task.setRuleSnapshotJson(task.getRuleSnapshotJson().replace("0.900000000000000001","0.900000000000000002"));
        assertThatThrownBy(() -> FindingLabelTaskDirectory.expected(task,json)).hasMessageContaining("摘要不一致");
    }
    @Test void exactProjectionProtocolReadsPreciseValuesEvenFromLegacyPipeline() throws Exception {
        fixture(PipelineRelease.SCHEMA);
        var read=reader.read(task,result,row);
        assertThat(read.value().decimalValue()).isEqualByComparingTo("0.900000000000000001");
        assertThat(read.rawFindings().getFirst().value().decimalValue()).isEqualByComparingTo("0.900000000000000001");
        row.setValueJson(row.getValueJson().replace("0.900000000000000001","0.900000000000000002"));
        assertThatThrownBy(() -> reader.read(task,result,row)).hasMessageContaining("摘要无效");
        row.setSourceProjectionHash(SelectorAssetService.hash(row.getValueJson()));
        assertThatThrownBy(() -> reader.read(task,result,row)).hasMessageContaining("实际生产融合结果不一致");
    }
}
