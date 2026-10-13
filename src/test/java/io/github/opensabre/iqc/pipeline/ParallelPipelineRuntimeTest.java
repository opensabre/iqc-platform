package io.github.opensabre.iqc.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real shared executor concurrency and graph validation; deterministic adapters replace providers. */
class ParallelPipelineRuntimeTest {
    private final ObjectMapper json=new ObjectMapper();
    private PipelineDefinition.Node node(String id,List<String> dependencies,Map<String,String> mapping){
        var schema=json.createObjectNode().put("type","object").put("additionalProperties",false);
        schema.putObject("properties").putObject(id+"_value").put("type","number");
        return new PipelineDefinition.Node(id,"TEST",id,"1",dependencies,mapping,null,null,5000,0,
                PipelineDefinition.OnError.FAIL_PIPELINE,null,schema,true);
    }
    @Test void independentNodesOverlapAndDependentSeesBothMergedOutputs() {
        var started=new CountDownLatch(2);
        EngineAdapter adapter=new EngineAdapter(){
            public String type(){return "TEST";}
            public NodeResult execute(NodeContext context) throws Exception {
                if(!context.node().nodeId().equals("joined")){
                    started.countDown();
                    if(!started.await(2,TimeUnit.SECONDS))throw new IllegalStateException("siblings did not overlap");
                }else{
                    assertEquals(1,context.input().path("left").intValue());
                    assertEquals(1,context.input().path("right").intValue());
                }
                return new NodeResult(NodeResult.Status.SUCCESS,List.of(),
                        Map.of(context.node().nodeId()+"_value",json.getNodeFactory().numberNode(1)),Map.of(),null);
            }
        };
        var definition=new PipelineDefinition(PipelineDefinition.PARALLEL_SCHEMA,List.of(
                node("left",List.of(),Map.of()),node("right",List.of(),Map.of()),
                node("joined",List.of("left","right"),Map.of("left","/variables/left_value","right","/variables/right_value"))));
        try(var runtime=new PipelineRuntime(List.of(adapter),2,4)){
            var run=runtime.execute(definition,json.createObjectNode(),Map.of(),PipelineRuntime.Mode.PRODUCTION);
            assertEquals(PipelineRuntime.Status.SUCCESS,run.status());assertEquals(3,run.trace().size());
            assertEquals(1,run.variables().get("joined_value").intValue());
        }
    }
    @Test void undeclaredSiblingReadIsRejectedBeforeAnyExecution() {
        EngineAdapter adapter=new EngineAdapter(){
            public String type(){return "TEST";}
            public NodeResult execute(NodeContext context){throw new AssertionError("invalid graph must not execute");}
        };
        try(var runtime=new PipelineRuntime(List.of(adapter),2,4)){
            var definition=new PipelineDefinition(PipelineDefinition.PARALLEL_SCHEMA,List.of(
                    node("left",List.of(),Map.of()),node("right",List.of(),Map.of("left","/variables/left_value"))));
            assertThrows(IllegalArgumentException.class,()->runtime.execute(definition,json.createObjectNode(),Map.of(),PipelineRuntime.Mode.PRODUCTION));
        }
    }
    @Test void timeoutRetainsTimelyCompletedSiblingTraceAndOutput() {
        var siblingFinished=new CountDownLatch(1);
        var blocked=new CountDownLatch(1);
        EngineAdapter adapter=new EngineAdapter(){
            public String type(){return "TEST";}
            public NodeResult execute(NodeContext context) throws Exception {
                if(context.node().nodeId().equals("slow")){
                    if(!siblingFinished.await(5,TimeUnit.SECONDS))throw new IllegalStateException("sibling unavailable");
                    blocked.await(5,TimeUnit.SECONDS);
                }
                var result=new NodeResult(NodeResult.Status.SUCCESS,List.of(),
                        Map.of(context.node().nodeId()+"_value",json.getNodeFactory().numberNode(1)),Map.of(),null);
                siblingFinished.countDown();return result;
            }
        };
        var original=node("slow",List.of(),Map.of());
        var slow=new PipelineDefinition.Node(original.nodeId(),original.type(),original.name(),original.version(),
                original.dependsOn(),original.inputMapping(),null,null,2000,2,original.onError(),null,original.outputSchema(),true);
        try(var runtime=new PipelineRuntime(List.of(adapter),2,4)){
            var run=runtime.execute(new PipelineDefinition(PipelineDefinition.PARALLEL_SCHEMA,
                    List.of(slow,node("fast",List.of(),Map.of()))),json.createObjectNode(),Map.of(),PipelineRuntime.Mode.PRODUCTION);
            assertEquals(PipelineRuntime.Status.FAILED,run.status());assertEquals(2,run.trace().size());
            assertEquals(NodeResult.Status.TIMEOUT,run.trace().getFirst().result().status());
            assertEquals(1,run.trace().getFirst().attempt());
            assertEquals(NodeResult.Status.SUCCESS,run.trace().getLast().result().status());
            assertEquals(1,run.variables().get("fast_value").intValue());assertFalse(run.variables().containsKey("slow_value"));
        }finally{blocked.countDown();}
    }
    @Test void callerInterruptionRecordsEveryStartedAttemptAsCancelled() throws Exception {
        var started=new CountDownLatch(2);var release=new CountDownLatch(1);
        EngineAdapter adapter=new EngineAdapter(){
            public String type(){return "TEST";}
            public NodeResult execute(NodeContext context) throws Exception {
                started.countDown();release.await(5,TimeUnit.SECONDS);
                return new NodeResult(NodeResult.Status.SUCCESS,List.of(),Map.of(),Map.of(),null);
            }
        };
        var result=new java.util.concurrent.atomic.AtomicReference<PipelineRuntime.Run>();
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        try(var runtime=new PipelineRuntime(List.of(adapter),2,4)){
            var caller=new Thread(()->{
                try{result.set(runtime.execute(new PipelineDefinition(PipelineDefinition.PARALLEL_SCHEMA,
                        List.of(node("left",List.of(),Map.of()),node("right",List.of(),Map.of()))),
                        json.createObjectNode(),Map.of(),PipelineRuntime.Mode.PRODUCTION));}
                catch(Throwable problem){failure.set(problem);}
            },"parallel-cancellation-test");
            caller.start();
            try{
                assertTrue(started.await(3,TimeUnit.SECONDS));caller.interrupt();caller.join(3000);
                assertFalse(caller.isAlive());assertNull(failure.get());assertNotNull(result.get());
                assertEquals(PipelineRuntime.Status.CANCELLED,result.get().status());assertEquals(2,result.get().trace().size());
                assertTrue(result.get().trace().stream().allMatch(trace->trace.result().status()==NodeResult.Status.CANCELLED));
                assertTrue(result.get().variables().isEmpty());
            }finally{release.countDown();caller.interrupt();caller.join(3000);}
        }
    }
}
