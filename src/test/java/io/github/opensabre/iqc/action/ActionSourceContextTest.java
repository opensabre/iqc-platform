package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.ProductionInputSnapshotService;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.pipelineasset.*;
import io.github.opensabre.iqc.pipelinebridge.PipelineConversationEvaluator;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.task.dao.*;
import io.github.opensabre.iqc.task.model.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual frozen-input validation, evaluator and Action source replay; detector and storage are mocked. */
class ActionSourceContextTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final InspectionTaskMapper tasks=mock(InspectionTaskMapper.class);
    private final TaskExecutionMapper executions=mock(TaskExecutionMapper.class);
    private final ConversationInspectionResultMapper results=mock(ConversationInspectionResultMapper.class);
    private final PipelineAssetService assets=mock(PipelineAssetService.class);
    private final ProductionInputSnapshotService inputs=new ProductionInputSnapshotService(null,null,null,json);
    private final ActionProductionSourceValidator source=new ActionProductionSourceValidator(tasks,executions,results,assets,inputs,json);
    private final ActionProductionSourceValidator.Reference reference=new ActionProductionSourceValidator.Reference("task","execution","conversation","result","alice");
    private InspectionTask task;
    @BeforeEach void fixture() throws Exception { fixture(Finding.State.KNOWN); }
    private void fixture(Finding.State state) throws Exception {
        var release=json.readValue("""
          {"schemaVersion":"iqc-pipeline-release-v2","pipeline":{"schemaVersion":"iqc-pipeline-v1","nodes":[
          {"nodeId":"llm","type":"LLM","version":"1","enabled":true,"config":{"findingDefinition":
          {"schemaVersion":"iqc-finding-definition-v1","name":"Risk","code":"RISK","category":"risk","valueType":"BOOLEAN",
          "defaultSeverity":"HIGH","evidenceRequired":true,"evidenceTypes":["CONVERSATION"]}}}]},
          "fusion":{"id":"fusion","version":1,"method":"PRIORITY"},"items":[{"itemCode":"risk","findingCode":"RISK","hitMeaning":"VIOLATION"}],
          "scoring":{"version":"iqc-score-v2","mode":"DEDUCTION","baseScore":100,"passingScore":80,"items":[{"itemCode":"risk","points":20,"veto":false}]},
          "labelPolicy":{"schemaVersion":"iqc-finding-label-policy-v1","bindings":[{"labelId":"quality","labelVersion":1,"valueCode":"PASS","findingCode":"RISK"}],
            "directory":{"schemaVersion":"2.0","labels":[{"id":"quality","versionNo":1,"name":"Quality","targetRole":"agent","values":[{"valueCode":"PASS","valueType":"BOOLEAN"}]}]}},
          "actionPolicy":{"schemaVersion":"iqc-action-policy-v1","actions":[{"actionId":"save","type":"SAVE_RESULT","condition":{"kind":"ALWAYS"},
          "idempotency":"PRODUCTION_SOURCE_V1","failureHandling":"RECORD_ONLY","configuration":{}}]}}
          """,PipelineRelease.class);
        var input=new ProductionInputSnapshotService.Input("conversation","revision",json.createObjectNode().put("amount",new BigDecimal("0.900000000000000001")),
                List.of(new ProductionInputSnapshotService.Message("message",1,"agent","processed conversation",null)));
        var body=json.valueToTree(List.of(input));var root=json.createObjectNode();root.putObject("executionStrategy").put("mode","PIPELINE");
        root.putObject("productionInputs").put("schemaVersion",ProductionInputSnapshotService.SCHEMA).put("hashAlgorithm","selector-decimal-sha256-v1")
                .put("contentHash",SelectorAssetService.hash(body.toString())).set("conversations",body);
        root.putObject("pipelineSnapshot").put("pipelineId","pipeline").put("versionNo",1)
                .put("contentHash",PipelineAssetService.hash(json.writeValueAsString(release))).set("release",json.valueToTree(release));
        root.putObject("actionOwnerBound").put("schemaVersion","iqc-schedule-owner-scope-v1").put("userId","user").put("owner","alice").put("all",false);
        task=new InspectionTask();task.setId("task");task.setTaskType("PIPELINE");task.setStatus("RUNNING");task.setCreatedBy("alice");
        task.setCurrentExecutionId("execution");task.setRuleSnapshotJson(root.toString());task.setConversationIdsJson("[\"conversation\"]");task.setTotalMessages(1);
        var execution=new TaskExecution();execution.setId("execution");execution.setTaskId("task");execution.setStatus("RUNNING");
        when(tasks.selectOne(any())).thenReturn(task);when(executions.selectOne(any())).thenReturn(execution);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(),"action-context"),InspectionTask.class);
        when(tasks.update(isNull(),any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1);
        var prepared=inputs.prepare(task,root);
        var fact=new Finding("fact","RISK","risk",Finding.ValueType.BOOLEAN,state==Finding.State.KNOWN?json.valueToTree(true):null,state,null,Finding.Severity.HIGH,
                "risk",List.of(new Finding.Evidence("evidence",Finding.EvidenceType.CONVERSATION,"message",null)),new Finding.Source("llm","LLM","1",1,null));
        var runtime=mock(PipelineRuntime.class);
        when(runtime.execute(any(),any(),any(),any(),any())).thenReturn(new PipelineRuntime.Run(PipelineRuntime.Status.SUCCESS,List.of(),List.of(fact),Map.of(),false));
        var evaluated=new PipelineConversationEvaluator(runtime,json,tasks).evaluate(task,release,prepared.messages().get("conversation"),PipelineRuntime.Mode.PRODUCTION,prepared.businessContext("conversation"));
        var result=new ConversationInspectionResult();result.setId("result");result.setTaskId("task");result.setExecutionId("execution");result.setConversationId("conversation");result.setAggregationMode("PIPELINE");
        result.setExecutionTraceJson(json.writeValueAsString(evaluated));result.setScoringResultJson(json.writeValueAsString(evaluated.scoring()));result.setBusinessItemResultsJson(json.writeValueAsString(evaluated.items()));
        result.setScoreStatus(evaluated.scoring().scoreStatus().name());result.setFinalScore(evaluated.scoring().finalScore());result.setResultStatus(evaluated.scoring().conclusion().name());result.setRiskLevel(evaluated.riskLevel());
        when(results.selectOne(any())).thenReturn(result);when(assets.publishedTaskRelease(any())).thenReturn(release);
    }
    @Test void frozenBusinessContextRetainsExactValueAndDrivesCondition() {
        var actual=source.validate(reference);
        var match=new ActionPolicy.Condition(ActionPolicy.Kind.BUSINESS_EQUALS,"/amount",null,List.of(),"0.900000000000000001");
        assertThat(ActionPolicy.evaluate(match,actual.context())).isEqualTo(ActionPolicy.Match.MATCH);
        assertThat(ActionPolicy.evaluate(new ActionPolicy.Condition(ActionPolicy.Kind.BUSINESS_EQUALS,"/amount",null,List.of(),"0.900000000000000002"),actual.context())).isEqualTo(ActionPolicy.Match.NO_MATCH);
        assertThat(ActionPolicy.evaluate(new ActionPolicy.Condition(ActionPolicy.Kind.BUSINESS_EQUALS,"/absent",json.valueToTree(true),List.of()),actual.context())).isEqualTo(ActionPolicy.Match.UNKNOWN);
    }
    @Test void frozenLabelProjectionReusesOriginalFindingEvidence() {
        var actual=source.validate(reference);
        assertThat(ActionPolicy.evaluate(new ActionPolicy.Condition(ActionPolicy.Kind.LABEL_EQUALS,"/quality/PASS",json.valueToTree(true),List.of()),actual.context())).isEqualTo(ActionPolicy.Match.MATCH);
        var projection=actual.context().labels().get("/quality/PASS");
        assertThat(projection.selectedFindingIds()).containsExactly("fact");
        assertThat(projection.evidence()).extracting(Finding.Evidence::reference).containsExactly("message");
    }
    @Test void unknownLabelStaysUnknownAndTamperedFrozenInputIsRejected() throws Exception {
        fixture(Finding.State.UNKNOWN);
        var actual=source.validate(reference);
        assertThat(ActionPolicy.evaluate(new ActionPolicy.Condition(ActionPolicy.Kind.LABEL_EQUALS,"/quality/PASS",json.valueToTree(false),List.of()),actual.context())).isEqualTo(ActionPolicy.Match.UNKNOWN);
        task.setRuleSnapshotJson(task.getRuleSnapshotJson().replace("0.900000000000000001","0.900000000000000002"));
        assertThatThrownBy(() -> source.validate(reference));
    }
}
