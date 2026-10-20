package io.github.opensabre.iqc.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.pipelineasset.*;
import io.github.opensabre.iqc.pipelinebridge.PipelineConversationEvaluator;
import io.github.opensabre.iqc.fusion.FusionPolicy;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.result.ReviewSampleInputValidator;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real bounded runtime, evaluator, persisted-input validator and candidate input boundary; no provider or SQL. */
class ExactPipelineExecutionInputTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private PipelineRelease release(String schema) {
        var config=json.createObjectNode();
        config.set("findingDefinition",json.valueToTree(new io.github.opensabre.iqc.finding.FindingDefinition(
                io.github.opensabre.iqc.finding.FindingDefinition.SCHEMA,"COMPLIANCE","Compliance","Quality",null,
                io.github.opensabre.iqc.finding.Finding.ValueType.BOOLEAN,List.of(),io.github.opensabre.iqc.finding.Finding.Severity.LOW,false,List.of())));
        var node=new PipelineDefinition.Node("rule","RULE","Rule","rule-v1",List.of(),Map.of(),config,
                null,1000,0,PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
        return new PipelineRelease(schema,new PipelineDefinition(PipelineDefinition.SCHEMA,List.of(node)),
                new FusionPolicy("f",1,FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of()),
                new InspectionScoring.Policy(InspectionScoring.POLICY_VERSION,InspectionScoring.Mode.DEDUCTION,100,60,List.of()),List.of());
    }
    private InspectionTask task(PipelineRelease release,String decimal) {
        var context=json.createObjectNode().put("value",new BigDecimal(decimal));
        var input=new ProductionInputSnapshotService.Input("c","fingerprint",context,
                List.of(new ProductionInputSnapshotService.Message("m",1,"customer","processed conversation",null)));
        var body=json.valueToTree(List.of(input));var root=json.createObjectNode();
        root.putObject("executionStrategy").put("schemaVersion","iqc-pipeline-task-v1").put("mode","PIPELINE");
        root.putObject("pipelineSnapshot").set("release",json.valueToTree(release));
        root.putObject("productionInputs").put("schemaVersion",ProductionInputSnapshotService.SCHEMA)
                .put("hashAlgorithm","selector-decimal-sha256-v1")
                .put("contentHash",io.github.opensabre.iqc.selector.SelectorAssetService.hash(body.toString())).set("conversations",body);
        var task=new InspectionTask();task.setId("task");task.setCurrentExecutionId("execution");task.setStatus("RUNNING");
        task.setTaskType("PIPELINE");task.setTotalMessages(1);task.setConversationIdsJson("[\"c\"]");task.setRuleSnapshotJson(root.toString());return task;
    }
    private PipelineConversationEvaluator.Evaluation evaluate(PipelineRelease release,InspectionTask task) throws Exception {
        var inputs=new ProductionInputSnapshotService(null,null,null,json);
        var root=PipelineAssetService.readFrozenTree(json,task.getRuleSnapshotJson());var prepared=inputs.prepare(task,root);
        var tasks=mock(InspectionTaskMapper.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(),"exact-input"),InspectionTask.class);
        when(tasks.update(isNull(),any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1);
        EngineAdapter adapter=new EngineAdapter(){public String type(){return "RULE";}
            public NodeResult execute(NodeContext context){var fact=new io.github.opensabre.iqc.finding.Finding("f","COMPLIANCE","Quality",io.github.opensabre.iqc.finding.Finding.ValueType.BOOLEAN,
                        json.getNodeFactory().booleanNode(true),io.github.opensabre.iqc.finding.Finding.State.KNOWN,null,
                        io.github.opensabre.iqc.finding.Finding.Severity.LOW,"processed input",List.of(),
                        new io.github.opensabre.iqc.finding.Finding.Source("rule","RULE","rule-v1",1,null));
                return new NodeResult(NodeResult.Status.SUCCESS,List.of(fact),Map.of(),Map.of(),null);}};
        try(var runtime=new PipelineRuntime(List.of(adapter),1,4)) {
            var result=new PipelineConversationEvaluator(runtime,json,tasks).evaluate(task,release,prepared.messages().get("c"),
                    PipelineRuntime.Mode.PRODUCTION,prepared.businessContext("c"));
            assertThat(result.run().status()).isEqualTo(PipelineRuntime.Status.SUCCESS);
            inputs.validateResult(task,root,prepared.messages().get("c"),result,prepared);
            assertThat(ReviewSampleInputValidator.validateInput(json.writeValueAsString(result),prepared.messages().get("c"),task,"c",json).executionInputHash())
                    .isEqualTo(result.inputHash());
            return result;
        }
    }
    @Test void v2RealExecutionAndFrozenValidationDistinguishAdjacentContextDecimals() throws Exception {
        var release=release(PipelineRelease.EXACT_SCHEMA);
        assertThat(evaluate(release,task(release,"0.900000000000000001")).inputHash())
                .isNotEqualTo(evaluate(release,task(release,"0.900000000000000002")).inputHash());
    }
    @Test void persistedV2ProtocolCannotBeDowngradedByCallerSnapshotOrLegacyDigest() throws Exception {
        var release=release(PipelineRelease.EXACT_SCHEMA);var task=task(release,"0.900000000000000001");
        var correct=evaluate(release,task);var inputs=new ProductionInputSnapshotService(null,null,null,json);
        var root=PipelineAssetService.readFrozenTree(json,task.getRuleSnapshotJson());var prepared=inputs.prepare(task,root);
        var input=PipelineConversationEvaluator.executionInput(prepared.messages().get("c"),prepared.businessContext("c"),json);
        var downgraded=new PipelineConversationEvaluator.Evaluation(correct.schemaVersion(),PipelineAssetService.hash(input.toString()),
                correct.run(),correct.fusion(),correct.items(),correct.scoring(),correct.riskLevel(),correct.mode(),correct.riskDecision(),correct.reviewDecision());
        ((com.fasterxml.jackson.databind.node.ObjectNode)root.path("pipelineSnapshot").path("release")).put("schemaVersion",PipelineRelease.SCHEMA);
        assertThat(prepared.releaseSchema()).isEqualTo(PipelineRelease.EXACT_SCHEMA);
        assertThatThrownBy(()->inputs.validateResult(task,root,prepared.messages().get("c"),downgraded,prepared)).hasMessageContaining("输入摘要");
        assertThatThrownBy(()->ReviewSampleInputValidator.validateInput(json.writeValueAsString(downgraded),prepared.messages().get("c"),task,"c",json))
                .hasMessageContaining("原执行输入一致");
        assertThatThrownBy(()->PipelineConversationEvaluator.executionInputHash("iqc-pipeline-release-v99",input))
                .hasMessageContaining("Unsupported");
    }
    @Test void legacyExecutionRetainsOriginalFingerprintContract() throws Exception {
        var release=release(PipelineRelease.SCHEMA);
        assertThat(evaluate(release,task(release,"0.900000000000000001")).inputHash())
                .isEqualTo(evaluate(release,task(release,"0.900000000000000002")).inputHash());
    }
}
