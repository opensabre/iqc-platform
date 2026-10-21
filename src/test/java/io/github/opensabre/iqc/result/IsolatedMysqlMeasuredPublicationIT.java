package io.github.opensabre.iqc.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.*;
import io.github.opensabre.iqc.conversation.dao.*;
import io.github.opensabre.iqc.finding.*;
import io.github.opensabre.iqc.finding.dao.*;
import io.github.opensabre.iqc.fusion.FusionPolicy;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.pipelineasset.*;
import io.github.opensabre.iqc.pipelineasset.dao.*;
import io.github.opensabre.iqc.pipelinebridge.*;
import io.github.opensabre.iqc.quality.dao.*;
import io.github.opensabre.iqc.quality.evaluation.*;
import io.github.opensabre.iqc.quality.model.QualitySample;
import io.github.opensabre.iqc.result.dao.*;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.*;
import io.github.opensabre.iqc.task.dao.*;
import io.github.opensabre.iqc.task.model.InspectionTask;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real SQL, catalog, independent gold review, datasets, workers and measured publication; no provider calls. */
class IsolatedMysqlMeasuredPublicationIT {
    private static AnnotationConfigApplicationContext context;
    private static final AtomicReference<String> actor = new AtomicReference<>("annotator");
    @BeforeAll static void start() throws Exception {
        IsolatedMysqlTaskExecutionStateIT.start();
        context = new AnnotationConfigApplicationContext(Config.class);
    }
    @AfterAll static void close() {
        if(context!=null)context.close();IsolatedMysqlTaskExecutionStateIT.close();
    }
    @Test void realMeasuredPublicationMaterializesAndExecutesFrozenProductionInput() throws Exception {
        var json=context.getBean(ObjectMapper.class);var db=new JdbcTemplate(context.getBean(DataSource.class));
        var sql=context.getBean(SqlSessionTemplate.class);String suffix=UUID.randomUUID().toString().replace("-","");
        String conversation="c"+suffix,first="m1"+suffix,second="m2"+suffix;
        db.update("INSERT INTO iqc_conversation(id,source_file_name,message_count,status,created_by,owner_group_id,source_fingerprint) VALUES (?,'processed conversation',2,'READY','annotator','group',?)",conversation,suffix);
        db.update("INSERT INTO iqc_conversation_message(id,conversation_id,sequence_no,speaker_role,content,start_ms,end_ms,relative_time,raw_line,line_number) VALUES (?,?,1,'customer','processed customer text',0,1000,'00:00:00','processed customer text',1),(?,?,2,'agent','processed agent text',2000,3000,'00:00:02','processed agent text',2)",first,conversation,second,conversation);
        var findingService=context.getBean(FindingCatalogService.class);
        var definition=new FindingDefinition(FindingDefinition.SCHEMA,"GAP_"+suffix.toUpperCase(),"Transcript interval gap","TIMING",null,
                Finding.ValueType.NUMBER,List.of(),Finding.Severity.HIGH,true,List.of(Finding.EvidenceType.METRIC));
        var catalog=findingService.create(definition);findingService.publish(catalog.getId(),1);
        var config=json.createObjectNode().put("metric","TRANSCRIPT_GAP_MS").put("agentRole","agent").put("customerRole","customer");
        config.putObject("findingReference").put("id",catalog.getId()).put("versionNo",1);
        var node=new PipelineDefinition.Node("timing","METRIC","Gap",TranscriptMetricEngine.VERSION,List.of(),Map.of(),config,null,3000,0,PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
        var item=new PipelineRelease.ItemMapping("gap",definition.code(),io.github.opensabre.iqc.scheme.SchemeDefinition.HitMeaning.VIOLATION,null,
                new PipelineRelease.NumericPredicate(PipelineDefinition.Operator.GREATER_THAN,"0.900000000000000001"),null);
        var release=new PipelineRelease(PipelineRelease.EXACT_SCHEMA,new PipelineDefinition(PipelineDefinition.SCHEMA,List.of(node)),
                new FusionPolicy("priority",1,FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of()),
                new InspectionScoring.Policy(InspectionScoring.POLICY_VERSION,InspectionScoring.Mode.DEDUCTION,100,60,
                        List.of(new InspectionScoring.Item("gap",20,false))),List.of(item));
        var pipelines=context.getBean(PipelineAssetService.class);var asset=pipelines.create("P_"+suffix.toUpperCase(),"Measured publication",release);
        var gold=context.getBean(GoldSampleService.class);
        var sample=new QualitySample();sample.setName("Processed conversation candidate");sample.setSampleType("POSITIVE");
        sample.setConversationId(conversation);sample.setMessageId(first);sample.setSourceResultId("manual-"+suffix);
        sample.setContentSnapshot("processed customer text");sample.setStatus("CANDIDATE");sample.setCreatedBy("annotator");sample.setOwnerGroupId("group");
        sql.getMapper(QualitySampleMapper.class).insert(sample);
        gold.annotate(sample.getId(),new GoldSampleService.Annotation(0,List.of(new GoldFinding(definition.code(),Finding.ValueType.NUMBER,
                Finding.State.KNOWN,json.valueToTree(1000),List.of())),null,List.of(),GoldConversation.Difficulty.BASIC,
                new GoldDecision(new BigDecimal("80"),GoldDecision.ScoreStatus.FINAL,Map.of(),GoldDecision.Risk.HIGH)));
        assertThrows(RuntimeException.class,()->gold.review(sample.getId(),1,true,"same annotator cannot approve"));
        actor.set("reviewer");gold.review(sample.getId(),1,true,"independent fixture review");actor.set("annotator");
        var datasets=context.getBean(EvaluationDatasetService.class);var evaluations=context.getBean(PipelineEvaluationService.class);
        var evidence=new ArrayList<PipelinePublicationGateService.ExecutionEvidence>();
        for(var type:List.of(DatasetDraft.Type.RISK,DatasetDraft.Type.REGRESSION)) {
            var dataset=datasets.create(type.name(),new DatasetDraft(DatasetDraft.SCHEMA,type,null,List.of(new DatasetDraft.Member(sample.getId(),1))));
            datasets.freeze(dataset.getId(),1);
            var task=evaluations.create(dataset.getId(),1,asset.getId(),null,1,type.name(),1,"eval_"+UUID.randomUUID().toString().replace("-",""));
            var executed=run(task);
            var report=evaluations.report(executed.getId(),executed.getCurrentExecutionId());
            assertTrue(report.complete());assertEquals(1,report.successfulSamples());
            assertEquals(0,BigDecimal.ONE.compareTo(report.findingMetrics().aggregate().f1()));
            evidence.add(new PipelinePublicationGateService.ExecutionEvidence(executed.getId(),executed.getCurrentExecutionId()));
        }
        var trial=context.getBean(InspectionTaskService.class).createUsingPipelineSnapshot(pipelines.frozenDraft(asset.getId(),1),
                "real trial",List.of(conversation),1,"trial_"+suffix);
        trial=run(trial);
        assertThrows(RuntimeException.class,()->pipelines.publishMeasured(asset.getId(),1,null,true,evidence.get(0),evidence.get(1),false,null));
        var published=pipelines.publishMeasured(asset.getId(),1,trial.getId(),true,evidence.get(0),evidence.get(1),false,null);
        var admission=json.readTree(published.getPublicationDecisionJson());assertFalse(admission.path("forced").asBoolean());
        assertTrue(admission.path("gate").path("passed").asBoolean());assertEquals(2,admission.path("gate").path("evidence").size());
        assertEquals(PipelineAssetService.hash(published.getPublicationDecisionJson()),published.getPublicationDecisionHash());
        var prepared=pipelines.publishedReleaseForTask(asset.getId(),1);
        assertEquals("0.900000000000000001",prepared.release().items().getFirst().numericPredicate().threshold());
        var production=context.getBean(InspectionTaskService.class).createUsingPipelineSnapshot(prepared,"production",List.of(conversation),1,"production_"+suffix);
        // Mutate live data only after the owning materializer has frozen the complete processed conversation.
        db.update("UPDATE iqc_conversation_message SET content='later text',start_ms=10000,end_ms=11000 WHERE id=?",second);
        production=run(production);
        var snapshot=PipelineAssetService.readFrozenTree(json,production.getRuleSnapshotJson());
        assertEquals(PipelineRelease.EXACT_SCHEMA,pipelines.publishedTaskRelease(snapshot).schemaVersion());
        var result=sql.getMapper(ConversationInspectionResultMapper.class).selectLatestForTaskConversation(production.getId(),conversation);
        assertNotNull(result);assertEquals(0,new BigDecimal("80").compareTo(result.getFinalScore()));assertEquals("HIGH",result.getRiskLevel());
        assertTrue(result.getExecutionTraceJson().contains("1000"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM iqc_task_item WHERE execution_id=? AND status='SUCCEEDED'",Integer.class,production.getCurrentExecutionId()));
        assertEquals("later text",db.queryForObject("SELECT content FROM iqc_conversation_message WHERE id=?",String.class,second));
    }
    private InspectionTask run(InspectionTask created) {
        var runner=context.getBean(InspectionExecutionService.class);var queued=runner.queue(created.getId());
        runner.executePersisted(queued.getId(),queued.getCurrentExecutionId());
        var actual=context.getBean(SqlSessionTemplate.class).getMapper(InspectionTaskMapper.class).selectById(created.getId());
        assertEquals("SUCCEEDED",actual.getStatus(),()->actual.getRetryError()+" / "+actual.getTriggerError());return actual;
    }
    @Configuration @Import(IsolatedMysqlTaskExecutionStateIT.DatabaseConfiguration.class)
    static class Config {
        @Bean ObjectMapper json(){return new ObjectMapper().findAndRegisterModules();}
        @Bean IqcDataScope scope(){var s=mock(IqcDataScope.class);when(s.owner()).thenAnswer(call->actor.get());when(s.groupId()).thenReturn("group");when(s.canView(any(),any())).thenReturn(true);return s;}
        @Bean SqlSessionTemplate sql(SqlSessionFactory f){
            for(var type:List.of(ConversationMapper.class,ConversationMessageMapper.class,FindingCatalogMapper.class,FindingCatalogVersionMapper.class,
                    PipelineAssetMapper.class,PipelineAssetVersionMapper.class,QualitySampleMapper.class,QualitySampleVersionMapper.class,
                    EvaluationDatasetMapper.class,EvaluationDatasetVersionMapper.class,EvaluationSampleResultMapper.class,
                    ConversationInspectionResultMapper.class,RuleInspectionResultMapper.class,InspectionEvidenceMapper.class,InspectionResultMapper.class))
                if(!f.getConfiguration().hasMapper(type))f.getConfiguration().addMapper(type);
            return new SqlSessionTemplate(f);
        }
        @Bean FindingCatalogService findings(SqlSessionTemplate s,ObjectMapper j,IqcDataScope scope){return new FindingCatalogService(s.getMapper(FindingCatalogMapper.class),s.getMapper(FindingCatalogVersionMapper.class),j,scope);}
        @Bean(destroyMethod="close") PipelineRuntime runtime(ObjectMapper j){return new PipelineRuntime(List.of(new TranscriptMetricEngine(j)),1,8);}
        @Bean PipelineAssetService pipelines(SqlSessionTemplate s,ObjectMapper j,IqcDataScope scope,PipelineRuntime runtime,FindingCatalogService facts,ObjectProvider<PipelinePublicationGateService> gates){
            return new PipelineAssetService(s.getMapper(PipelineAssetMapper.class),s.getMapper(PipelineAssetVersionMapper.class),null,null,null,null,null,facts,j,scope,runtime,s.getMapper(InspectionTaskMapper.class),s.getMapper(ConversationInspectionResultMapper.class),gates);
        }
        @Bean ProductionInputSnapshotService inputs(SqlSessionTemplate s,ObjectMapper j,IqcDataScope scope){return new ProductionInputSnapshotService(s.getMapper(ConversationMapper.class),s.getMapper(ConversationMessageMapper.class),scope,j);}
        @Bean @Primary InspectionTaskService tasks(SqlSessionTemplate s,ObjectMapper j,IqcDataScope scope,ProductionInputSnapshotService inputs,ObjectProvider<PipelineAssetService> pipelines){return new InspectionTaskService(s.getMapper(InspectionTaskMapper.class),s.getMapper(ConversationMapper.class),null,null,null,j,s.getMapper(TaskExecutionMapper.class),scope,null,null,inputs,pipelines);}
        @Bean GoldSampleService gold(SqlSessionTemplate s,ObjectMapper j,IqcDataScope scope){return new GoldSampleService(s.getMapper(QualitySampleMapper.class),s.getMapper(QualitySampleVersionMapper.class),s.getMapper(ConversationMapper.class),s.getMapper(ConversationMessageMapper.class),scope,j);}
        @Bean EvaluationDatasetService datasets(SqlSessionTemplate s,ObjectMapper j,IqcDataScope scope,GoldSampleService gold){return new EvaluationDatasetService(s.getMapper(EvaluationDatasetMapper.class),s.getMapper(EvaluationDatasetVersionMapper.class),gold,scope,j);}
        @Bean PipelineConversationEvaluator evaluator(PipelineRuntime runtime,ObjectMapper j,SqlSessionTemplate s){return new PipelineConversationEvaluator(runtime,j,s.getMapper(InspectionTaskMapper.class));}
        @Bean PipelineEvaluationService evaluations(EvaluationDatasetService datasets,PipelineAssetService pipelines,InspectionTaskService tasks,SqlSessionTemplate s,PipelineConversationEvaluator evaluator,ObjectMapper j,IqcDataScope scope){return new PipelineEvaluationService(datasets,pipelines,tasks,s.getMapper(InspectionTaskMapper.class),s.getMapper(TaskExecutionMapper.class),s.getMapper(TaskItemMapper.class),s.getMapper(EvaluationSampleResultMapper.class),evaluator,j,scope);}
        @Bean PipelinePublicationGateService gates(PipelineEvaluationService evaluations,ObjectMapper j,SqlSessionTemplate s){return new PipelinePublicationGateService(evaluations,j,s.getMapper(TaskExecutionMapper.class));}
        @Bean HierarchicalResultService hierarchy(SqlSessionTemplate s,ObjectMapper j){return new HierarchicalResultService(s.getMapper(ConversationInspectionResultMapper.class),s.getMapper(RuleInspectionResultMapper.class),s.getMapper(InspectionEvidenceMapper.class),j,null,s.getMapper(InspectionTaskMapper.class),s.getMapper(InspectionResultMapper.class),s.getMapper(TaskItemMapper.class));}
        @Bean InspectionExecutionService runner(SqlSessionTemplate s,ObjectMapper j,IqcDataScope scope,HierarchicalResultService hierarchy,PipelineAssetService pipelines,PipelineConversationEvaluator evaluator,PipelineEvaluationService evaluations){return new InspectionExecutionService(s.getMapper(InspectionTaskMapper.class),s.getMapper(ConversationMapper.class),s.getMapper(ConversationMessageMapper.class),s.getMapper(InspectionResultMapper.class),j,s.getMapper(TaskExecutionMapper.class),s.getMapper(TaskItemMapper.class),scope,mock(io.github.opensabre.iqc.result.llm.LlmQualityProvider.class),mock(io.github.opensabre.governance.usage.UsageCounterRecorder.class),hierarchy,mock(io.github.opensabre.iqc.label.LabelCandidateService.class),mock(io.github.opensabre.iqc.scheme.SchemeDependencyResolver.class),pipelines,evaluator,evaluations);}
    }
}
