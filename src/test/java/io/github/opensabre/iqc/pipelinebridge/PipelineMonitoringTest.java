package io.github.opensabre.iqc.pipelinebridge;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.finding.*;
import io.github.opensabre.iqc.fusion.FusionPolicy;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.pipelineasset.PipelineRelease;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real runtime with deterministic adapters; task heartbeat is controlled, no provider or database. */
class PipelineMonitoringTest {
    static class TestRegistry extends SimpleMeterRegistry implements AutoCloseable { }
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final InspectionTaskMapper tasks=mock(InspectionTaskMapper.class);
    @BeforeEach void setup(){
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"pipeline-monitoring"),InspectionTask.class);
        when(tasks.update(isNull(),any())).thenReturn(1);
    }
    PipelineDefinition.Node node(String id,String type,int retries,PipelineDefinition.OnError onError,String fallback,boolean enabled,boolean skip){
        var config=json.createObjectNode();
        config.set("findingDefinition",json.valueToTree(new FindingDefinition(FindingDefinition.SCHEMA,id.toUpperCase(),id,"TEST",null,
                Finding.ValueType.BOOLEAN,List.of(),Finding.Severity.LOW,true,List.of(Finding.EvidenceType.CONVERSATION))));
        var schema=json.createObjectNode().put("type","object").put("additionalProperties",false);schema.putObject("properties");
        var condition=skip?new PipelineDefinition.Condition("/input/absent",PipelineDefinition.Operator.EXISTS,null):null;
        return new PipelineDefinition.Node(id,type,id,"1",List.of(),Map.of(),config,condition,3000,retries,onError,fallback,schema,enabled);
    }
    PipelineRelease release(String schema,List<PipelineDefinition.Node> nodes){
        return new PipelineRelease(PipelineRelease.SCHEMA,new PipelineDefinition(schema,nodes),
                new FusionPolicy("priority",1,FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of()),
                new InspectionScoring.Policy("iqc-score-v2",InspectionScoring.Mode.DEDUCTION,100,60,List.of()),List.of());
    }
    PipelineConversationEvaluator evaluator(PipelineRuntime runtime,MeterRegistry meters){
        ObjectProvider<MeterRegistry> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(meters);
        return new PipelineConversationEvaluator(runtime,json,tasks,provider);
    }
    PipelineConversationEvaluator.Evaluation evaluate(PipelineConversationEvaluator evaluator,PipelineRelease release,PipelineRuntime.Mode mode){
        var task=new InspectionTask();task.setId("private-task");task.setCurrentExecutionId("private-execution");task.setStatus("RUNNING");
        var message=new ConversationMessage();message.setId("private-message");message.setConversationId("private-conversation");
        message.setSequenceNo(1);message.setSpeakerRole("agent");message.setContent("processed private content");
        return evaluator.evaluate(task,release,List.of(message),mode);
    }
    EngineAdapter adapter(java.util.function.Function<NodeContext,NodeResult> execute){
        return new EngineAdapter(){public String type(){return "RULE";}public NodeResult execute(NodeContext context){return execute.apply(context);}};
    }
    NodeResult success(){return new NodeResult(NodeResult.Status.SUCCESS,List.of(),Map.of(),Map.of(),null);}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={PipelineDefinition.SCHEMA,PipelineDefinition.PARALLEL_SCHEMA})
    void countsEveryRetryFallbackAndSkipWithoutTimingUnstartedNodes(String schema){
        var attempts=new AtomicInteger();var engine=adapter(context->{
            if(context.node().nodeId().equals("retry")&&attempts.incrementAndGet()==1)return NodeResult.failure(NodeResult.Status.ERROR,"retry");
            if(context.node().nodeId().equals("primary"))return NodeResult.failure(NodeResult.Status.ERROR,"fallback");
            return success();
        });
        try(var runtime=new PipelineRuntime(List.of(engine),2,8);var meters=new TestRegistry()){
            var release=release(schema,List.of(node("retry","RULE",1,PipelineDefinition.OnError.CONTINUE,null,true,false),
                    node("primary","RULE",0,PipelineDefinition.OnError.FALLBACK,"backup",true,false),
                    node("backup","RULE",0,PipelineDefinition.OnError.CONTINUE,null,false,false),
                    node("skip","RULE",0,PipelineDefinition.OnError.CONTINUE,null,true,true)));
            var result=evaluate(evaluator(runtime,meters),release,PipelineRuntime.Mode.PRODUCTION);
            assertEquals(5,result.run().trace().size());
            assertEquals(5,meters.find("iqc.pipeline.node.observations").counters().stream().mapToDouble(Counter::count).sum());
            assertEquals(4,meters.find("iqc.pipeline.node.duration").timers().stream().mapToLong(Timer::count).sum());
            assertEquals(1,meters.get("iqc.pipeline.node.observations").tags("status","SKIPPED","phase","NOT_STARTED").counter().count());
            assertEquals(2,meters.get("iqc.pipeline.node.observations").tags("status","ERROR","phase","ATTEMPT").counter().count());
            assertEquals(1,meters.get("iqc.pipeline.graph.duration").tag("status",result.run().status().name()).timer().count());
            assertEquals(0,meters.get("iqc.pipeline.graph.active").tag("mode","PRODUCTION").gauge().value());
            verify(tasks,times(5)).update(isNull(),any());
            for(var meter:meters.getMeters())for(var tag:meter.getId().getTags()){
                assertTrue(Set.of("mode","engine","status","phase").contains(tag.getKey()));
                assertFalse(tag.getValue().contains("private"));
            }
        }
    }
    @Test void meterFailuresDoNotSuppressHeartbeatsOrSuccessfulExecution(){
        try(var runtime=new PipelineRuntime(List.of(adapter(context->success())),1,2);var meters=new TestRegistry()){
            meters.config().meterFilter(new io.micrometer.core.instrument.config.MeterFilter(){
                @Override public Meter.Id map(Meter.Id id){throw new IllegalStateException("registry unavailable");}
            });
            var result=evaluate(evaluator(runtime,meters),release(PipelineDefinition.SCHEMA,List.of(node("one","RULE",0,PipelineDefinition.OnError.CONTINUE,null,true,false))),PipelineRuntime.Mode.PRODUCTION);
            assertEquals(PipelineRuntime.Status.SUCCESS,result.run().status());verify(tasks).update(isNull(),any());
        }
    }
    @Test void graphExceptionReturnsActiveGaugeAndRecordsError(){
        try(var runtime=new PipelineRuntime(List.of(adapter(context->success())),1,2);var meters=new TestRegistry()){
            var evaluator=evaluator(runtime,meters);
            var invalid=release("unsupported",List.of(node("one","RULE",0,PipelineDefinition.OnError.CONTINUE,null,true,false)));
            assertThrows(IllegalArgumentException.class,()->evaluate(evaluator,invalid,PipelineRuntime.Mode.EVALUATION));
            assertEquals(0,meters.get("iqc.pipeline.graph.active").tag("mode","EVALUATION").gauge().value());
            assertEquals(1,meters.get("iqc.pipeline.graph.duration").tags("mode","EVALUATION","status","ERROR").timer().count());
            verifyNoInteractions(tasks);
        }
    }
    @Test void observesPhysicalPoolAndLogicalActiveDuringBlockedExecution() throws Exception {
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        try(var runtime=new PipelineRuntime(List.of(adapter(context->{started.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}return success();})),1,4);
            var meters=new TestRegistry()){
            var evaluator=evaluator(runtime,meters);var definition=release(PipelineDefinition.PARALLEL_SCHEMA,List.of(
                    node("one","RULE",0,PipelineDefinition.OnError.CONTINUE,null,true,false)));
            var caller=new Thread(()->{try{evaluate(evaluator,definition,PipelineRuntime.Mode.PRODUCTION);}catch(Throwable ex){failure.set(ex);}});
            var evaluationCaller=new Thread(()->{try{evaluate(evaluator,definition,PipelineRuntime.Mode.EVALUATION);}catch(Throwable ex){failure.set(ex);}});
            caller.start();try{
                assertTrue(started.await(3,TimeUnit.SECONDS));
                evaluationCaller.start();
                assertEquals(1,meters.get("iqc.pipeline.graph.active").tag("mode","PRODUCTION").gauge().value());
                assertEquals(runtime.activeNodeWorkers(),meters.get("iqc.pipeline.executor.active").gauge().value());
                // Each graph bounds admission to worker count; a second graph creates shared physical backlog.
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(runtime.queuedNodeAttempts()!=1&&System.nanoTime()<deadline)Thread.yield();
                assertEquals(1,meters.get("iqc.pipeline.executor.queued").gauge().value());
            }finally{release.countDown();caller.join(5000);evaluationCaller.join(5000);}
            assertFalse(caller.isAlive());assertFalse(evaluationCaller.isAlive());assertNull(failure.get());
            assertEquals(0,meters.get("iqc.pipeline.graph.active").tag("mode","PRODUCTION").gauge().value());
        }
    }
    @Test void cancellationReturnsGraphActiveAndTimesOnlyStartedAttempts() throws Exception {
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        var result=new AtomicReference<PipelineConversationEvaluator.Evaluation>();var failure=new AtomicReference<Throwable>();
        EngineAdapter engine=new EngineAdapter(){public String type(){return "RULE";}
            public NodeResult execute(NodeContext context) throws Exception {started.countDown();release.await(5,TimeUnit.SECONDS);return success();}};
        try(var runtime=new PipelineRuntime(List.of(engine),1,2);var meters=new TestRegistry()){
            var evaluator=evaluator(runtime,meters);var definition=release(PipelineDefinition.PARALLEL_SCHEMA,
                    List.of(node("one","RULE",0,PipelineDefinition.OnError.CONTINUE,null,true,false)));
            var caller=new Thread(()->{try{result.set(evaluate(evaluator,definition,PipelineRuntime.Mode.PRODUCTION));}catch(Throwable ex){failure.set(ex);}});
            caller.start();try{assertTrue(started.await(3,TimeUnit.SECONDS));caller.interrupt();caller.join(4000);}
            finally{release.countDown();caller.interrupt();caller.join(4000);}
            assertFalse(caller.isAlive());assertNull(failure.get());assertNotNull(result.get());
            assertEquals(PipelineRuntime.Status.CANCELLED,result.get().run().status());
            assertEquals(1,meters.get("iqc.pipeline.node.observations").tags("status","CANCELLED","phase","ATTEMPT").counter().count());
            assertEquals(1,meters.get("iqc.pipeline.node.duration").tag("status","CANCELLED").timer().count());
            assertEquals(0,meters.get("iqc.pipeline.graph.active").tag("mode","PRODUCTION").gauge().value());
            assertEquals(1,meters.get("iqc.pipeline.graph.duration").tag("status","CANCELLED").timer().count());
            verify(tasks).update(isNull(),any());
        }
    }
    @Test void customEngineUsesBoundedOtherTagAndEvaluationMode(){
        EngineAdapter engine=new EngineAdapter(){public String type(){return "CUSTOM_PRIVATE_ENGINE";}public NodeResult execute(NodeContext context){return success();}};
        try(var runtime=new PipelineRuntime(List.of(engine),1,2);var meters=new TestRegistry()){
            evaluate(evaluator(runtime,meters),release(PipelineDefinition.SCHEMA,List.of(node("one","CUSTOM_PRIVATE_ENGINE",0,PipelineDefinition.OnError.CONTINUE,null,true,false))),PipelineRuntime.Mode.EVALUATION);
            assertEquals(1,meters.get("iqc.pipeline.node.observations").tags("mode","EVALUATION","engine","OTHER").counter().count());
            assertNull(meters.find("iqc.pipeline.node.observations").tag("engine","CUSTOM_PRIVATE_ENGINE").counter());
        }
    }
    @Test void legacyConstructorAndInstrumentedExecutionKeepInputIdentityAndBusinessProjection() throws Exception {
        try(var runtime=new PipelineRuntime(List.of(adapter(context->success())),1,2);var meters=new TestRegistry()){
            var definition=release(PipelineDefinition.SCHEMA,List.of(node("one","RULE",0,PipelineDefinition.OnError.CONTINUE,null,true,false)));
            var legacy=evaluate(new PipelineConversationEvaluator(runtime,json,tasks),definition,PipelineRuntime.Mode.PRODUCTION);
            var measured=evaluate(evaluator(runtime,meters),definition,PipelineRuntime.Mode.PRODUCTION);
            assertEquals(legacy.inputHash(),measured.inputHash());assertEquals(legacy.fusion(),measured.fusion());
            assertEquals(legacy.scoring(),measured.scoring());assertEquals(legacy.items(),measured.items());
            var tree=json.valueToTree(measured);assertFalse(tree.has("metrics"));assertFalse(tree.has("activeGraphs"));
            assertEquals(json.valueToTree(legacy).properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()),
                    tree.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
        }
    }

}
