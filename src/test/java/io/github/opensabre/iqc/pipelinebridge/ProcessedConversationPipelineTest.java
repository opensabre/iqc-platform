package io.github.opensabre.iqc.pipelinebridge;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.finding.*;
import io.github.opensabre.iqc.fusion.FusionPolicy;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.pipelineasset.*;
import io.github.opensabre.iqc.rule.dls.DlsRuleDocument;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.scheme.SchemeDefinition;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import java.util.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Original evaluator, real Metric/Session adapters, parallel executor, fusion and scoring; task lease is mocked. */
class ProcessedConversationPipelineTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private ConversationMessage message(String id,int sequence,String role,String content,long start,long end){
        var row=new ConversationMessage();row.setId(id);row.setSequenceNo(sequence);row.setConversationId("conversation");
        row.setSpeakerRole(role);row.setContent(content);row.setStartMs(start);row.setEndMs(end);return row;
    }
    private PipelineDefinition.Node node(String id,String type,String version,com.fasterxml.jackson.databind.JsonNode config,String valueType){
        var schema=json.createObjectNode().put("type","object").put("additionalProperties",false);
        var properties=schema.putObject("properties");properties.putObject(id+"_state").put("type","string");
        properties.putObject(id+"_value").put("type",valueType);
        return new PipelineDefinition.Node(id,type,id,version,List.of(),Map.of(),config,null,3000,0,
                PipelineDefinition.OnError.FAIL_PIPELINE,null,schema,true);
    }
    private PipelineRelease release() throws Exception {
        var metric=json.createObjectNode().put("metric","TRANSCRIPT_GAP_MS").put("agentRole","agent").put("customerRole","customer");
        metric.set("findingDefinition",json.valueToTree(new FindingDefinition(FindingDefinition.SCHEMA,"GAP_MS","Gap","TIMING",null,
                Finding.ValueType.NUMBER,List.of(),Finding.Severity.LOW,true,List.of(Finding.EvidenceType.METRIC))));
        var session=json.createObjectNode().put("aggregation","DLS_ENTRY_MATCH");
        session.set("findingDefinition",json.valueToTree(new FindingDefinition(FindingDefinition.SCHEMA,"OFFER","Offer","SESSION",null,
                Finding.ValueType.BOOLEAN,List.of(),Finding.Severity.LOW,true,List.of(Finding.EvidenceType.RULE_MATCH))));
        session.set("ruleReference",json.createObjectNode().put("id","rule").put("versionNo",1));
        var dls=new DlsRuleDocument("1.0",null,List.of(new DlsRuleDocument.Definition("rule_offer","RULE","offer","agent")),"Offer","[rule_offer]");
        session.set("ruleSnapshot",json.createObjectNode().put("id","rule").put("versionNo",1).put("ruleType","DLS").put("targetRole","agent").put("expression",json.writeValueAsString(dls)));
        var pipeline=new PipelineDefinition(PipelineDefinition.PARALLEL_SCHEMA,List.of(
                node("timing","METRIC",TranscriptMetricEngine.VERSION,metric,"number"),
                node("session","SESSION",SessionAggregationEngine.VERSION,session,"boolean")));
        var scoring=new InspectionScoring.Policy("iqc-score-v2",InspectionScoring.Mode.DEDUCTION,100,60,
                List.of(new InspectionScoring.Item("offer-check",10,false)));
        return new PipelineRelease(PipelineRelease.SCHEMA,pipeline,
                new FusionPolicy("priority",1,FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of()),scoring,
                List.of(new PipelineRelease.ItemMapping("offer-check","OFFER",SchemeDefinition.HitMeaning.COMPLIANCE)));
    }
    private PipelineConversationEvaluator evaluator(PipelineRuntime runtime){
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"processed-pipeline-test"),InspectionTask.class);
        var tasks=mock(InspectionTaskMapper.class);when(tasks.update(isNull(),any())).thenReturn(1);
        return new PipelineConversationEvaluator(runtime,json,tasks);
    }
    private InspectionTask task(){var task=new InspectionTask();task.setId("task");task.setCurrentExecutionId("execution");task.setStatus("RUNNING");return task;}
    @Test void productionAndEvaluationShareParallelFactsFusionAndBusinessScore() throws Exception {
        var messages=List.of(message("customer",1,"customer","question",0,100),message("agent",2,"agent","offer offer",200,300));
        var context=json.createObjectNode().put("businessType","processed");
        try(var runtime=new PipelineRuntime(List.of(new TranscriptMetricEngine(json),new SessionAggregationEngine(json)),2,4)){
            var evaluator=evaluator(runtime);var release=release();
            var production=evaluator.evaluate(task(),release,messages,PipelineRuntime.Mode.PRODUCTION,context);
            var evaluation=evaluator.evaluate(task(),release,messages,PipelineRuntime.Mode.EVALUATION,context);
            assertEquals(PipelineRuntime.Status.SUCCESS,production.run().status());assertEquals(2,production.run().trace().size());
            assertEquals(production.run().findings(),evaluation.run().findings());assertEquals(production.fusion(),evaluation.fusion());
            assertEquals(production.scoring(),evaluation.scoring());assertEquals(production.inputHash(),evaluation.inputHash());
            assertEquals(100,production.run().variables().get("timing_value").intValue());
            assertTrue(production.run().variables().get("session_value").booleanValue());
            assertEquals(InspectionScoring.ScoreStatus.FINAL,production.scoring().scoreStatus());
            assertEquals(0,production.scoring().finalScore().compareTo(new java.math.BigDecimal("100")));
            assertEquals(InspectionScoring.ItemStatus.PASS,production.items().getFirst().status());
            assertFalse(production.run().variables().containsKey("__messages"));
            assertEquals(PipelineAssetService.hash(evaluator.executionInput(messages,context).toString()),production.inputHash());
        }
    }
    @Test void missingRequiredSessionParticipantRemainsUnknownAndBusinessScorePending() throws Exception {
        var messages=List.of(message("customer",1,"customer","offer",0,100));
        try(var runtime=new PipelineRuntime(List.of(new TranscriptMetricEngine(json),new SessionAggregationEngine(json)),2,4)){
            var result=evaluator(runtime).evaluate(task(),release(),messages,PipelineRuntime.Mode.PRODUCTION,json.createObjectNode());
            var fact=result.run().findings().stream().filter(value->value.code().equals("OFFER")).findFirst().orElseThrow();
            assertEquals(Finding.State.UNKNOWN,fact.state());assertNull(fact.value());assertTrue(fact.evidence().isEmpty());
            assertFalse(result.run().variables().containsKey("session_value"));
            assertEquals(InspectionScoring.ScoreStatus.PENDING,result.scoring().scoreStatus());assertNull(result.scoring().finalScore());
            assertEquals(InspectionScoring.ItemStatus.REVIEW_REQUIRED,result.items().getFirst().status());
        }
    }
}
