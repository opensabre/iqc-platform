package io.github.opensabre.iqc.pipelinebridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.pipelineasset.*;
import io.github.opensabre.iqc.finding.*;
import io.github.opensabre.iqc.fusion.FindingFusion;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.scheme.SchemeDefinition;
import io.github.opensabre.iqc.scheme.SchemeResultEvaluator;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;

/** Produces the existing business score projection from frozen pipeline facts, never from rule prices. */
@Service
@Slf4j
public class PipelineConversationEvaluator {
    public record Evaluation(String schemaVersion,String inputHash,PipelineRuntime.Run run,
            List<FindingFusion.Result> fusion,List<SchemeResultEvaluator.ItemResult> items,InspectionScoring.Result scoring,String riskLevel,PipelineRuntime.Mode mode,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            io.github.opensabre.iqc.pipelineasset.RiskPolicy.Decision riskDecision,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            io.github.opensabre.iqc.pipelineasset.ReviewPolicy.Decision reviewDecision) {
        public Evaluation(String schemaVersion,String inputHash,PipelineRuntime.Run run,List<FindingFusion.Result> fusion,
                List<SchemeResultEvaluator.ItemResult> items,InspectionScoring.Result scoring,String riskLevel,PipelineRuntime.Mode mode,
                io.github.opensabre.iqc.pipelineasset.RiskPolicy.Decision riskDecision) {
            this(schemaVersion,inputHash,run,fusion,items,scoring,riskLevel,mode,riskDecision,null);
        }
        public Evaluation(String schemaVersion,String inputHash,PipelineRuntime.Run run,List<FindingFusion.Result> fusion,
                List<SchemeResultEvaluator.ItemResult> items,InspectionScoring.Result scoring,String riskLevel,PipelineRuntime.Mode mode) {
            this(schemaVersion,inputHash,run,fusion,items,scoring,riskLevel,mode,null);
        }
    }
    private final PipelineRuntime runtime;
    private final ObjectMapper json;
    private final io.github.opensabre.iqc.task.dao.InspectionTaskMapper tasks;
    private final io.micrometer.core.instrument.MeterRegistry meters;
    private final EnumMap<PipelineRuntime.Mode,java.util.concurrent.atomic.AtomicInteger> activeGraphs=new EnumMap<>(PipelineRuntime.Mode.class);
    private static final Set<String> METRIC_ENGINES=Set.of("RULE","DLS","LLM","METRIC","SESSION","SKILL_API","SMALL_MODEL");
    @org.springframework.beans.factory.annotation.Autowired
    public PipelineConversationEvaluator(PipelineRuntime runtime,ObjectMapper json,io.github.opensabre.iqc.task.dao.InspectionTaskMapper tasks,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters){
        this.runtime=runtime;this.json=json;this.tasks=tasks;this.meters=meters==null?null:meters.getIfAvailable();
        for(var mode:PipelineRuntime.Mode.values())activeGraphs.put(mode,new java.util.concurrent.atomic.AtomicInteger());
        if(this.meters!=null)try{
            for(var entry:activeGraphs.entrySet())io.micrometer.core.instrument.Gauge.builder("iqc.pipeline.graph.active",entry.getValue(),java.util.concurrent.atomic.AtomicInteger::get)
                    .tag("mode",entry.getKey().name()).register(this.meters);
            io.micrometer.core.instrument.Gauge.builder("iqc.pipeline.executor.active",runtime,PipelineRuntime::activeNodeWorkers).register(this.meters);
            io.micrometer.core.instrument.Gauge.builder("iqc.pipeline.executor.queued",runtime,PipelineRuntime::queuedNodeAttempts).register(this.meters);
        }catch(RuntimeException unavailable){log.debug("Pipeline gauge unavailable type={}",unavailable.getClass().getSimpleName());}
    }
    public PipelineConversationEvaluator(PipelineRuntime runtime,ObjectMapper json,io.github.opensabre.iqc.task.dao.InspectionTaskMapper tasks){
        this(runtime,json,tasks,null);
    }
    private PipelineRuntime.Run executeGraph(InspectionTask task,PipelineRelease release,List<ConversationMessage> messages,
            PipelineRuntime.Mode mode,com.fasterxml.jackson.databind.JsonNode input){
        var engines=new HashMap<String,String>();release.pipeline().nodes().forEach(node->engines.put(node.nodeId(),node.type()));
        var active=activeGraphs.get(mode);if(active!=null)active.incrementAndGet();
        long started=System.nanoTime();String outcome="ERROR";
        try{
            var run=runtime.execute(release.pipeline(),input,Map.of("__task",json.valueToTree(task),"__messages",json.valueToTree(messages)),mode,trace->{
                recordNode(trace,engines.get(trace.nodeId()),mode);
                int renewed=tasks.update(null,com.baomidou.mybatisplus.core.toolkit.Wrappers.<InspectionTask>lambdaUpdate()
                        .set(InspectionTask::getUpdatedTime,new java.util.Date()).eq(InspectionTask::getId,task.getId())
                        .eq(InspectionTask::getCurrentExecutionId,task.getCurrentExecutionId()).eq(InspectionTask::getStatus,"RUNNING"));
                if(renewed!=1)Thread.currentThread().interrupt();
            });
            outcome=run.status().name();return run;
        }finally{
            if(active!=null)active.decrementAndGet();
            if(meters!=null&&mode!=null)try{
                io.micrometer.core.instrument.Timer.builder("iqc.pipeline.graph.duration")
                        .description("Logical graph execution duration including node scheduling and progress callbacks; excludes fusion and persistence")
                        .tags("mode",mode.name(),"status",outcome).publishPercentileHistogram()
                        .minimumExpectedValue(java.time.Duration.ofMillis(1)).maximumExpectedValue(java.time.Duration.ofHours(1))
                        .register(meters).record(Math.max(0,System.nanoTime()-started),java.util.concurrent.TimeUnit.NANOSECONDS);
            }catch(RuntimeException unavailable){log.debug("Pipeline graph metric unavailable type={}",unavailable.getClass().getSimpleName());}
        }
    }
    private void recordNode(PipelineRuntime.Trace trace,String engine,PipelineRuntime.Mode mode){
        if(meters==null||mode==null)return;
        try{
            String kind=engine!=null&&METRIC_ENGINES.contains(engine)?engine:"OTHER";
            String status=trace.result().status().name();String phase=trace.attempt()>0?"ATTEMPT":"NOT_STARTED";
            meters.counter("iqc.pipeline.node.observations","mode",mode.name(),"engine",kind,"status",status,"phase",phase).increment();
            if(trace.attempt()>0&&trace.durationMs()>=0)io.micrometer.core.instrument.Timer.builder("iqc.pipeline.node.duration")
                    .description("Logical node attempt duration at runtime settlement; excludes unstarted skipped nodes")
                    .tags("mode",mode.name(),"engine",kind,"status",status).publishPercentileHistogram()
                    .minimumExpectedValue(java.time.Duration.ofMillis(1)).maximumExpectedValue(java.time.Duration.ofMinutes(5))
                    .register(meters).record(trace.durationMs(),java.util.concurrent.TimeUnit.MILLISECONDS);
        }catch(RuntimeException unavailable){log.debug("Pipeline node metric unavailable type={}",unavailable.getClass().getSimpleName());}
    }
    public Evaluation evaluate(InspectionTask task,PipelineRelease release,List<ConversationMessage> messages) {
        return evaluate(task,release,messages,PipelineRuntime.Mode.PRODUCTION);
    }
    public Evaluation evaluate(InspectionTask task,PipelineRelease release,List<ConversationMessage> messages,PipelineRuntime.Mode mode) {
        return evaluate(task,release,messages,mode,null);
    }
    public com.fasterxml.jackson.databind.JsonNode executionInput(List<ConversationMessage> messages,com.fasterxml.jackson.databind.JsonNode businessContext) {
        return executionInput(messages,businessContext,json);
    }
    public static com.fasterxml.jackson.databind.JsonNode executionInput(List<ConversationMessage> messages,com.fasterxml.jackson.databind.JsonNode businessContext,ObjectMapper json) {
        var input=json.createObjectNode(); input.set("messages",json.valueToTree(messages));
        if(businessContext!=null) input.set("businessContext",businessContext.deepCopy());
        return input;
    }
    /** Input identity is selected by the frozen release protocol, never by incidental input fields. */
    public static String executionInputHash(String releaseSchema,com.fasterxml.jackson.databind.JsonNode input) {
        if(PipelineRelease.EXACT_SCHEMA.equals(releaseSchema))
            return io.github.opensabre.iqc.selector.SelectorAssetService.hash(input.toString());
        if(PipelineRelease.SCHEMA.equals(releaseSchema))return PipelineAssetService.hash(input.toString());
        throw new IllegalArgumentException("Unsupported frozen pipeline input hash protocol");
    }
    public static String samplingInputHash(PipelineRelease release,List<ConversationMessage> messages,com.fasterxml.jackson.databind.JsonNode businessContext,ObjectMapper json) {
        return release.reviewPolicy()!=null&&io.github.opensabre.iqc.pipelineasset.ReviewPolicy.EXACT_SCHEMA.equals(release.reviewPolicy().schemaVersion())?samplingInputHash(messages,businessContext,json):null;
    }
    public static String samplingInputHash(List<ConversationMessage> messages,com.fasterxml.jackson.databind.JsonNode businessContext,ObjectMapper json) {
        return io.github.opensabre.iqc.selector.SelectorAssetService.hash(executionInput(messages,businessContext,json).toString());
    }
    public Evaluation evaluate(InspectionTask task,PipelineRelease release,List<ConversationMessage> messages,PipelineRuntime.Mode mode,
            com.fasterxml.jackson.databind.JsonNode businessContext) {
        var input=executionInput(messages,businessContext);
        var run=executeGraph(task,release,messages,mode,input);
        io.github.opensabre.iqc.quality.evaluation.CalibrationRuntime.verify(task,run,json);
        var observations=new ArrayList<>(run.findings());
        for(var node:release.pipeline().nodes()) {
            boolean produced=observations.stream().anyMatch(value->node.nodeId().equals(value.source().nodeId()));
            if(produced) continue;
            var trace=run.trace().stream().filter(value->node.nodeId().equals(value.nodeId())).reduce((a,b)->b).orElse(null);
            var state=trace==null || trace.result().status()==NodeResult.Status.SKIPPED || PipelineRuntime.policySkipped(run,trace)?Finding.State.NOT_EVALUATED:Finding.State.ERROR;
            FindingDefinition definition;
            try { definition=json.treeToValue(node.config().path("findingDefinition"),FindingDefinition.class); }
            catch(Exception ex) { throw new IllegalStateException("Frozen finding definition is invalid",ex); }
            String identity=task.getCurrentExecutionId()+":"+messages.getFirst().getConversationId()+":"+node.nodeId()+":missing";
            var source=new Finding.Source(node.nodeId(),node.type(),node.version(),trace==null?1:Math.max(1,trace.attempt()),null);
            observations.add(new Finding(PipelineAssetService.hash(json.valueToTree(identity).toString()),definition.code(),definition.category(),
                    definition.valueType(),null,state,null,definition.defaultSeverity(),trace==null?"Node not executed":trace.result().error(),List.of(),source));
        }
        var fused=FindingFusion.fuse(observations,release.fusion());
        var byCode=new HashMap<String,FindingFusion.Result>(); fused.forEach(value->byCode.put(value.code(),value));
        var items=new ArrayList<SchemeResultEvaluator.ItemResult>();
        var decisions=PipelineDecisionEvaluator.decisions(release.items(),fused);
        var statuses=new HashMap<String,InspectionScoring.ItemStatus>();
        decisions.forEach(value->statuses.put(value.itemCode(),value.status()));
        for(var mapping:release.items()) {
            var fact=byCode.get(mapping.findingCode());
            var status=statuses.get(mapping.itemCode());
            var matched=fact==null || status==InspectionScoring.ItemStatus.NOT_APPLICABLE?List.<String>of():fact.evidence().stream().filter(value->value.type()==Finding.EvidenceType.CONVERSATION)
                    .map(Finding.Evidence::reference).filter(id->messages.stream().anyMatch(message->id.equals(message.getId()))).distinct().toList();
            var producer=release.pipeline().nodes().stream().filter(node->mapping.findingCode().equals(node.config().path("findingDefinition").path("code").asText()))
                    .findFirst().orElseThrow();
            var rule=producer.config().path("ruleSnapshot");
            items.add(new SchemeResultEvaluator.ItemResult(mapping.itemCode(),mapping.itemCode(),rule.path("id").asText(),rule.path("versionNo").asInt(),status,matched));
        }
        var score=PipelineDecisionEvaluator.evaluate(release,fused,businessContext);
        var riskDecision=PipelineDecisionEvaluator.riskDecision(release,fused,score,businessContext);
        String inputHash=executionInputHash(release.schemaVersion(),input);
        String risk=riskDecision==null?PipelineDecisionEvaluator.riskLevel(release.items(),fused,score):riskDecision.level();
        var review=PipelineDecisionEvaluator.reviewDecision(release,fused,score,risk,PipelineDecisionEvaluator.resultStatus(run,items,score),inputHash,businessContext,release.reviewPolicy()!=null&&io.github.opensabre.iqc.pipelineasset.ReviewPolicy.EXACT_SCHEMA.equals(release.reviewPolicy().schemaVersion())?io.github.opensabre.iqc.selector.SelectorAssetService.hash(input.toString()):null,json);
        return new Evaluation("iqc-pipeline-execution-v1",inputHash,run,fused,List.copyOf(items),score,risk,mode,riskDecision,review);
    }
}
