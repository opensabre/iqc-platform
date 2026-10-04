package io.github.opensabre.iqc.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.finding.Finding;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Bounded shared executor. Adapters must honor interruption and enforce provider transport deadlines. */
public final class PipelineRuntime implements AutoCloseable {
    public enum Mode { PRODUCTION, EVALUATION }
    public enum Status { SUCCESS, PARTIAL_SUCCESS, FAILED, CANCELLED }
    public record Trace(String nodeId, int attempt, Instant startedAt, long durationMs, NodeResult result) { }
    public record Run(Status status, List<Trace> trace, List<Finding> findings,
                      Map<String,JsonNode> variables, boolean reviewRequired) {
        public Run { trace=List.copyOf(trace); findings=List.copyOf(findings); variables=NodeContext.snapshot(variables); }
        @Override public Map<String,JsonNode> variables() { return NodeContext.snapshot(variables); }
    }
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Map<String,EngineAdapter> adapters;
    private final ThreadPoolExecutor executor;
    public PipelineRuntime(Collection<EngineAdapter> engines, int maxWorkers, int maxQueued) {
        if(maxWorkers<1 || maxWorkers>128 || maxQueued<1 || maxQueued>10000) throw new IllegalArgumentException("Invalid executor bounds");
        var registered = new HashMap<String,EngineAdapter>();
        for(var engine:engines) if(registered.putIfAbsent(engine.type(),engine)!=null) throw new IllegalArgumentException("Duplicate engine: "+engine.type());
        adapters=Map.copyOf(registered);
        executor=new ThreadPoolExecutor(maxWorkers,maxWorkers,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(maxQueued),r->{
            var thread=new Thread(r,"iqc-pipeline-node"); thread.setDaemon(true); return thread;
        },new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
    }
    public void validate(PipelineDefinition definition) {
        if(!PipelineDefinition.SCHEMA.equals(definition.schemaVersion()) || definition.nodes().isEmpty() || definition.nodes().size()>200)
            throw new IllegalArgumentException("Unsupported pipeline schema or node count");
        var preceding=new HashSet<String>();
        var indexed=new HashMap<String,PipelineDefinition.Node>();
        for(var node:definition.nodes()) {
            if(node.nodeId()==null || node.nodeId().isBlank() || node.version()==null || node.version().isBlank() || !adapters.containsKey(node.type()))
                throw new IllegalArgumentException("Missing node identity, version or engine");
            if(node.timeoutMs()<1 || node.timeoutMs()>300000 || node.retries()<0 || node.retries()>3 || node.onError()==null)
                throw new IllegalArgumentException("Invalid node execution bounds");
            if(!preceding.containsAll(node.dependsOn()) || !preceding.add(node.nodeId())) throw new IllegalArgumentException("Dependencies must reference distinct preceding nodes");
            node.inputMapping().values().forEach(PipelineRuntime::validatePointer);
            if(node.condition()!=null) {
                validatePointer(node.condition().pointer()); Objects.requireNonNull(node.condition().operator());
                if(node.condition().operator()!=PipelineDefinition.Operator.EXISTS && node.condition().value()==null)
                    throw new IllegalArgumentException("Comparison condition requires value");
                if((node.condition().operator()==PipelineDefinition.Operator.GREATER_OR_EQUAL || node.condition().operator()==PipelineDefinition.Operator.LESS_OR_EQUAL)
                    && !node.condition().value().isNumber()) throw new IllegalArgumentException("Numeric condition requires number");
            }
            ValueSchema.validateSchema(node.outputSchema());
            indexed.put(node.nodeId(),node);
        }
        for(var node:definition.nodes()) if(node.onError()==PipelineDefinition.OnError.FALLBACK) {
            var fallback=indexed.get(node.fallbackNodeId());
            if(fallback==null || fallback.enabled() || fallback.nodeId().equals(node.nodeId()) || fallback.onError()==PipelineDefinition.OnError.FALLBACK)
                throw new IllegalArgumentException("Fallback must reference a disabled non-recursive node");
            if(!node.dependsOn().containsAll(fallback.dependsOn())) throw new IllegalArgumentException("Fallback dependencies must be available to source node");
        }
    }
    public Run execute(PipelineDefinition definition, JsonNode input, Map<String,JsonNode> initialVariables, Mode mode) {
        validate(definition); Objects.requireNonNull(input); Objects.requireNonNull(mode);
        var vars=new LinkedHashMap<>(NodeContext.snapshot(initialVariables==null?Map.of():initialVariables));
        var upstream=new LinkedHashMap<String,NodeResult>(); var trace=new ArrayList<Trace>(); var findings=new ArrayList<Finding>();
        var nodes=new HashMap<String,PipelineDefinition.Node>(); definition.nodes().forEach(n->nodes.put(n.nodeId(),n));
        boolean partial=false, review=false;
        for(var node:definition.nodes()) {
            if(Thread.currentThread().isInterrupted()) return new Run(Status.CANCELLED,trace,findings,vars,review);
            if(!node.enabled()) continue;
            var context=context(input,vars,upstream);
            if(node.dependsOn().stream().anyMatch(id->!upstream.containsKey(id) || upstream.get(id).status()!=NodeResult.Status.SUCCESS)) {
                upstream.put(node.nodeId(),NodeResult.failure(NodeResult.Status.SKIPPED,"Dependency unavailable"));
                trace.add(new Trace(node.nodeId(),0,Instant.now(),0,upstream.get(node.nodeId()))); partial=true; review=true; continue;
            }
            if(!matches(node.condition(),context)) {
                upstream.put(node.nodeId(),NodeResult.failure(NodeResult.Status.SKIPPED,"Condition not met"));
                trace.add(new Trace(node.nodeId(),0,Instant.now(),0,upstream.get(node.nodeId()))); continue;
            }
            var result=invoke(node,input,vars,upstream,mode,trace);
            if(result.status()==NodeResult.Status.CANCELLED) return new Run(Status.CANCELLED,trace,findings,vars,review);
            if(result.status()!=NodeResult.Status.SUCCESS) {
                if(node.onError()==PipelineDefinition.OnError.FALLBACK) result=invoke(nodes.get(node.fallbackNodeId()),input,vars,upstream,mode,trace);
                if(result.status()==NodeResult.Status.CANCELLED) return new Run(Status.CANCELLED,trace,findings,vars,review);
                if(result.status()!=NodeResult.Status.SUCCESS) {
                    partial=true; review|=node.onError()==PipelineDefinition.OnError.MARK_REVIEW || node.onError()==PipelineDefinition.OnError.FALLBACK;
                    upstream.put(node.nodeId(),result);
                    if(node.onError()==PipelineDefinition.OnError.FAIL_PIPELINE) return new Run(Status.FAILED,trace,findings,vars,review);
                    continue;
                }
            }
            upstream.put(node.nodeId(),result); findings.addAll(result.findings()); vars.putAll(result.variables());
        }
        return new Run(partial?Status.PARTIAL_SUCCESS:Status.SUCCESS,trace,findings,vars,review);
    }
    private NodeResult invoke(PipelineDefinition.Node node,JsonNode input,Map<String,JsonNode> vars,
                              Map<String,NodeResult> upstream,Mode mode,List<Trace> trace) {
        var adapter=adapters.get(node.type());
        for(int attempt=1;attempt<=node.retries()+1;attempt++) {
            Instant start=Instant.now(); long tick=System.nanoTime(); NodeResult result; Future<NodeResult> future=null;
            try {
                // Business actions belong to Action execution, never a detection pipeline.
                if(adapter.sideEffecting()) throw new IllegalArgumentException("Side-effect engine is forbidden in "+mode);
                JsonNode mapped=input.deepCopy();
                if(!node.inputMapping().isEmpty()) {
                    ObjectNode object=JSON.createObjectNode(); JsonNode context=context(input,vars,upstream);
                    for(var mapping:node.inputMapping().entrySet()) {
                        var value=context.at(mapping.getValue());
                        if(value.isMissingNode()) throw new IllegalArgumentException("Missing mapped input: "+mapping.getKey());
                        object.set(mapping.getKey(),value.deepCopy());
                    }
                    mapped=object;
                }
                var request=new NodeContext(node,mapped,vars,upstream,mode,attempt);
                future=executor.submit(()->adapter.execute(request));
                result=Objects.requireNonNull(future.get(node.timeoutMs(),TimeUnit.MILLISECONDS));
                if(result.status()==NodeResult.Status.SUCCESS) ValueSchema.check(node.outputSchema(),result.variables());
            } catch(InterruptedException ex) {
                if(future!=null) future.cancel(true); Thread.currentThread().interrupt();
                result=NodeResult.failure(NodeResult.Status.CANCELLED,"Execution interrupted");
            } catch(TimeoutException ex) {
                if(future!=null) future.cancel(true);
                result=NodeResult.failure(NodeResult.Status.TIMEOUT,"Node deadline exceeded");
            } catch(Exception ex) {
                if(future!=null) future.cancel(true);
                result=NodeResult.failure(NodeResult.Status.ERROR,ex instanceof RejectedExecutionException?"Executor capacity exhausted":"Node execution or output validation failed");
            }
            trace.add(new Trace(node.nodeId(),attempt,start,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-tick),result));
            if(result.status()==NodeResult.Status.SUCCESS || result.status()==NodeResult.Status.CANCELLED) return result;
            // Avoid concurrent retries against an adapter which ignores cancellation.
            if(result.status()==NodeResult.Status.TIMEOUT) return result;
        }
        return trace.getLast().result();
    }
    private static ObjectNode context(JsonNode input,Map<String,JsonNode> vars,Map<String,NodeResult> upstream) {
        var object=JSON.createObjectNode(); object.set("input",input.deepCopy()); object.set("variables",JSON.valueToTree(vars));
        object.set("upstream",JSON.valueToTree(upstream)); return object;
    }
    private static boolean matches(PipelineDefinition.Condition condition,JsonNode context) {
        if(condition==null) return true;
        JsonNode actual=context.at(condition.pointer()); JsonNode expected=condition.value();
        return switch(condition.operator()) {
            case EXISTS -> !actual.isMissingNode() && !actual.isNull();
            case EQUALS -> !actual.isMissingNode() && actual.equals(expected);
            case NOT_EQUALS -> !actual.isMissingNode() && !actual.isNull() && expected!=null && !actual.equals(expected);
            case GREATER_OR_EQUAL -> actual.isNumber() && expected!=null && expected.isNumber() && actual.decimalValue().compareTo(expected.decimalValue())>=0;
            case LESS_OR_EQUAL -> actual.isNumber() && expected!=null && expected.isNumber() && actual.decimalValue().compareTo(expected.decimalValue())<=0;
        };
    }
    private static void validatePointer(String pointer) {
        if(pointer==null || (!pointer.isEmpty() && !pointer.startsWith("/"))) throw new IllegalArgumentException("Expected JSON pointer");
        com.fasterxml.jackson.core.JsonPointer.compile(pointer);
    }
    @Override public void close() { executor.shutdownNow(); }
}
