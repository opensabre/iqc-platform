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
    public enum CompletionReason { POLICY_SATISFIED }
    private static final String POLICY_SATISFIED_REASON = "Completion policy satisfied";
    public record Trace(String nodeId, int attempt, Instant startedAt, long durationMs, NodeResult result) { }
    public record Run(Status status, List<Trace> trace, List<Finding> findings,
                      Map<String,JsonNode> variables, boolean reviewRequired,
                      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                      CompletionReason completionReason) {
        public Run(Status status,List<Trace> trace,List<Finding> findings,Map<String,JsonNode> variables,boolean reviewRequired) {
            this(status,trace,findings,variables,reviewRequired,null);
        }
        public Run { trace=List.copyOf(trace); findings=List.copyOf(findings); var visible=new LinkedHashMap<String,JsonNode>();
            variables.forEach((key,value)->{if(!key.startsWith("__")) visible.put(key,value);});
            variables=NodeContext.snapshot(visible); }
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
        validateDefinition(definition,true);
    }
    /** Historical snapshots retain graph/contract checks without requiring today's engine registry. */
    public void validateStructure(PipelineDefinition definition) { validateDefinition(definition,false); }
    private void validateDefinition(PipelineDefinition definition,boolean requireCurrentEngines) {
        if(!(PipelineDefinition.SCHEMA.equals(definition.schemaVersion()) || PipelineDefinition.PARALLEL_SCHEMA.equals(definition.schemaVersion()) || PipelineDefinition.PARALLEL_POLICY_SCHEMA.equals(definition.schemaVersion())) || definition.nodes().isEmpty() || definition.nodes().size()>200)
            throw new IllegalArgumentException("Unsupported pipeline schema or node count");
        var preceding=new HashSet<String>();
        var indexed=new HashMap<String,PipelineDefinition.Node>();
        for(var node:definition.nodes()) {
            if(node.nodeId()==null || node.nodeId().isBlank() || node.version()==null || node.version().isBlank() || node.type()==null || node.type().isBlank()
                    || requireCurrentEngines && !adapters.containsKey(node.type()))
                throw new IllegalArgumentException("Missing node identity, version or engine");
            if(node.timeoutMs()<1 || node.timeoutMs()>300000 || node.retries()<0 || node.retries()>3 || node.onError()==null)
                throw new IllegalArgumentException("Invalid node execution bounds");
            if(!preceding.containsAll(node.dependsOn()) || !preceding.add(node.nodeId())) throw new IllegalArgumentException("Dependencies must reference distinct preceding nodes");
            node.inputMapping().values().forEach(PipelineRuntime::validatePointer);
            if(node.condition()!=null) {
                validatePointer(node.condition().pointer()); Objects.requireNonNull(node.condition().operator());
                if(node.condition().operator()!=PipelineDefinition.Operator.EXISTS && node.condition().value()==null)
                    throw new IllegalArgumentException("Comparison condition requires value");
                if((node.condition().operator()==PipelineDefinition.Operator.GREATER_OR_EQUAL || node.condition().operator()==PipelineDefinition.Operator.LESS_OR_EQUAL
                            || node.condition().operator()==PipelineDefinition.Operator.GREATER_THAN || node.condition().operator()==PipelineDefinition.Operator.LESS_THAN)
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
        if(PipelineDefinition.PARALLEL_SCHEMA.equals(definition.schemaVersion()) || PipelineDefinition.PARALLEL_POLICY_SCHEMA.equals(definition.schemaVersion())) {
            var contract=parallelContract(definition);
            validateCompletionPolicy(definition,indexed,contract);
        } else if(definition.completionPolicy()!=null) throw new IllegalArgumentException("Completion policy requires parallel-v2");
    }
    public Run execute(PipelineDefinition definition, JsonNode input, Map<String,JsonNode> initialVariables, Mode mode) {
        return execute(definition,input,initialVariables,mode,trace -> { });
    }
    public Run execute(PipelineDefinition definition, JsonNode input, Map<String,JsonNode> initialVariables, Mode mode,
                       java.util.function.Consumer<Trace> progress) {
        Objects.requireNonNull(progress);
        validate(definition); Objects.requireNonNull(input); Objects.requireNonNull(mode);
        if(PipelineDefinition.PARALLEL_POLICY_SCHEMA.equals(definition.schemaVersion()))
            return executeParallelPolicy(definition,input,initialVariables,mode,progress);
        if(PipelineDefinition.PARALLEL_SCHEMA.equals(definition.schemaVersion()))
            return executeParallel(definition,input,initialVariables,mode,progress);
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
                record(trace,new Trace(node.nodeId(),0,Instant.now(),0,upstream.get(node.nodeId())),progress); partial=true; review=true; continue;
            }
            if(!matches(node.condition(),context)) {
                upstream.put(node.nodeId(),NodeResult.failure(NodeResult.Status.SKIPPED,"Condition not met"));
                record(trace,new Trace(node.nodeId(),0,Instant.now(),0,upstream.get(node.nodeId())),progress); continue;
            }
            var result=invoke(node,input,vars,upstream,mode,trace,progress);
            if(result.status()==NodeResult.Status.CANCELLED) return new Run(Status.CANCELLED,trace,findings,vars,review);
            if(result.status()!=NodeResult.Status.SUCCESS) {
                if(node.onError()==PipelineDefinition.OnError.FALLBACK) result=invoke(nodes.get(node.fallbackNodeId()),input,vars,upstream,mode,trace,progress);
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
    private record ParallelContract(Map<String,Set<String>> ancestors,Map<String,String> owners) { }
    /** Reuses output schemas to prove ownership, rather than inventing a second variable declaration model. */
    private static ParallelContract parallelContract(PipelineDefinition definition) {
        var indexed=new LinkedHashMap<String,PipelineDefinition.Node>();definition.nodes().forEach(node->indexed.put(node.nodeId(),node));
        var ancestors=new LinkedHashMap<String,Set<String>>();var owners=new LinkedHashMap<String,String>();
        for(var node:definition.nodes()) {
            if(new HashSet<>(node.dependsOn()).size()!=node.dependsOn().size())throw new IllegalArgumentException("Duplicate DAG dependency");
            var previous=new LinkedHashSet<String>();
            for(var dependency:node.dependsOn()){previous.add(dependency);previous.addAll(ancestors.get(dependency));}
            ancestors.put(node.nodeId(),Set.copyOf(previous));
            if(!node.enabled())continue;
            if(node.dependsOn().stream().anyMatch(id->!indexed.get(id).enabled()))throw new IllegalArgumentException("Enabled DAG node cannot depend on a disabled fallback");
            registerExports(node,node.nodeId(),owners);
            if(node.onError()==PipelineDefinition.OnError.FALLBACK)registerExports(indexed.get(node.fallbackNodeId()),node.nodeId(),owners);
        }
        for(var node:definition.nodes())if(node.enabled()) {
            validateReads(node,ancestors.get(node.nodeId()),owners);
            if(node.onError()==PipelineDefinition.OnError.FALLBACK)validateReads(indexed.get(node.fallbackNodeId()),ancestors.get(node.nodeId()),owners);
        }
        return new ParallelContract(Map.copyOf(ancestors),Map.copyOf(owners));
    }
    private static void registerExports(PipelineDefinition.Node node,String producer,Map<String,String> owners) {
        var schema=node.outputSchema();
        if(schema==null || !"object".equals(schema.path("type").asText()) || !schema.path("properties").isObject()
                || !schema.path("additionalProperties").isBoolean() || schema.path("additionalProperties").booleanValue())
            throw new IllegalArgumentException("Parallel outputs require an explicit closed object schema");
        for(var property:schema.path("properties").properties()) {
            if(property.getKey().isBlank() || property.getKey().startsWith("__"))throw new IllegalArgumentException("Invalid parallel export name");
            var prior=owners.putIfAbsent(property.getKey(),producer);
            if(prior!=null && !prior.equals(producer))throw new IllegalArgumentException("Parallel variable has multiple producers: "+property.getKey());
        }
    }
    private static void validateReads(PipelineDefinition.Node node,Set<String> ancestors,Map<String,String> owners) {
        var reads=new ArrayList<String>(node.inputMapping().values());if(node.condition()!=null)reads.add(node.condition().pointer());
        for(String pointer:reads) {
            var path=com.fasterxml.jackson.core.JsonPointer.compile(pointer);String root=path.getMatchingProperty();
            if("input".equals(root))continue;
            if((!"variables".equals(root) && !"upstream".equals(root)) || path.tail().matches())throw new IllegalArgumentException("Parallel reads require input or an explicit variable/upstream key");
            String key=path.tail().getMatchingProperty();String producer="upstream".equals(root)?key:owners.get(key);
            if(producer!=null && !ancestors.contains(producer))throw new IllegalArgumentException("Parallel read requires an explicit ancestor dependency: "+pointer);
        }
    }
    private static void validateCompletionPolicy(PipelineDefinition definition,Map<String,PipelineDefinition.Node> indexed,ParallelContract contract) {
        var policy=definition.completionPolicy();
        if(!PipelineDefinition.PARALLEL_POLICY_SCHEMA.equals(definition.schemaVersion())) {
            if(policy!=null)throw new IllegalArgumentException("Completion policy requires parallel-v2");
            return;
        }
        if(policy==null || policy.requiredNodes().isEmpty() || policy.requiredNodes().size()>200
                || new HashSet<>(policy.requiredNodes()).size()!=policy.requiredNodes().size())
            throw new IllegalArgumentException("Completion policy requires distinct enabled nodes");
        for(var id:policy.requiredNodes())if(!indexed.containsKey(id) || !indexed.get(id).enabled())
            throw new IllegalArgumentException("Completion policy references an unavailable node");
        var condition=policy.condition();if(condition==null)return;
        validatePointer(condition.pointer());Objects.requireNonNull(condition.operator());
        if(condition.operator()!=PipelineDefinition.Operator.EXISTS && condition.value()==null)
            throw new IllegalArgumentException("Completion comparison requires a value");
        if(Set.of(PipelineDefinition.Operator.GREATER_OR_EQUAL,PipelineDefinition.Operator.LESS_OR_EQUAL,
                PipelineDefinition.Operator.GREATER_THAN,PipelineDefinition.Operator.LESS_THAN).contains(condition.operator())
                && !condition.value().isNumber())throw new IllegalArgumentException("Completion numeric comparison requires a number");
        var path=com.fasterxml.jackson.core.JsonPointer.compile(condition.pointer());var root=path.getMatchingProperty();
        if("input".equals(root))return;
        if((!"variables".equals(root) && !"upstream".equals(root)) || path.tail().matches())
            throw new IllegalArgumentException("Completion condition requires a declared input or completed output");
        String key=path.tail().getMatchingProperty();String producer="upstream".equals(root)?key:contract.owners().get(key);
        if(producer==null || !policy.requiredNodes().contains(producer))
            throw new IllegalArgumentException("Completion condition must reference a required completed node");
    }
    private static boolean completionSatisfied(PipelineDefinition.CompletionPolicy policy,JsonNode input,
            Map<String,JsonNode> vars,Map<String,NodeResult> upstream) {
        return policy.requiredNodes().stream().allMatch(id->upstream.containsKey(id)
                && upstream.get(id).status()==NodeResult.Status.SUCCESS) && matches(policy.condition(),context(input,vars,upstream));
    }
    private record TimedOutcome(NodeResult result,Exception failure,long finished) { }
    private record Attempt(PipelineDefinition.Node node,int number,Instant start,long tick,java.util.concurrent.atomic.AtomicLong finished,Future<TimedOutcome> future,NodeResult early) { }
    private Attempt beginParallel(PipelineDefinition.Node node,int number,JsonNode input,Map<String,JsonNode> vars,Map<String,NodeResult> upstream,Mode mode) {
        return beginParallel(node,number,input,vars,upstream,mode,null);
    }
    private Attempt beginParallel(PipelineDefinition.Node node,int number,JsonNode input,Map<String,JsonNode> vars,
            Map<String,NodeResult> upstream,Mode mode,BlockingQueue<String> completions) {
        Instant start=Instant.now();long tick=System.nanoTime();var finished=new java.util.concurrent.atomic.AtomicLong();
        try {
            var adapter=adapters.get(node.type());if(adapter.sideEffecting())throw new IllegalArgumentException("Detection cannot execute side effects");
            JsonNode mapped=input.deepCopy();
            if(!node.inputMapping().isEmpty()) {
                var object=JSON.createObjectNode();var available=context(input,vars,upstream);
                for(var entry:node.inputMapping().entrySet()) {
                    var value=available.at(entry.getValue());if(value.isMissingNode())throw new IllegalArgumentException("Missing mapped input");
                    object.set(entry.getKey(),value.deepCopy());
                }
                mapped=object;
            }
            var request=new NodeContext(node,mapped,vars,upstream,mode,number);
            var future=new FutureTask<TimedOutcome>(()->{
                try {
                    try {return new TimedOutcome(Objects.requireNonNull(adapter.execute(request)),null,System.nanoTime());}
                    catch(Exception failure){return new TimedOutcome(null,failure,System.nanoTime());}
                } finally {finished.set(System.nanoTime());}
            }) {
                @Override protected void done() { if(completions!=null)completions.offer(node.nodeId()); }
            };
            executor.execute(future);
            return new Attempt(node,number,start,tick,finished,future,null);
        } catch(Exception failure) {
            finished.set(System.nanoTime());
            return new Attempt(node,number,start,tick,finished,null,NodeResult.failure(NodeResult.Status.ERROR,
                    failure instanceof RejectedExecutionException?"Executor capacity exhausted":"Node input or admission failed"));
        }
    }
    private NodeResult awaitParallel(Attempt attempt) {
        if(attempt.early()!=null)return attempt.early();
        try {
            long remaining=TimeUnit.MILLISECONDS.toNanos(attempt.node().timeoutMs())-(System.nanoTime()-attempt.tick());
            if(remaining<=0 && !attempt.future().isDone())throw new TimeoutException();
            var outcome=attempt.future().isDone()?attempt.future().get():attempt.future().get(remaining,TimeUnit.NANOSECONDS);
            if(outcome.finished()-attempt.tick()>TimeUnit.MILLISECONDS.toNanos(attempt.node().timeoutMs()))throw new TimeoutException();
            if(outcome.failure()!=null)throw outcome.failure();
            if(outcome.result().status()==NodeResult.Status.SUCCESS)ValueSchema.check(attempt.node().outputSchema(),outcome.result().variables());
            return outcome.result();
        } catch(InterruptedException interrupted) {
            attempt.future().cancel(true);Thread.currentThread().interrupt();return NodeResult.failure(NodeResult.Status.CANCELLED,"Execution interrupted");
        } catch(TimeoutException timeout) {
            attempt.future().cancel(true);return NodeResult.failure(NodeResult.Status.TIMEOUT,"Node deadline exceeded");
        } catch(CancellationException cancelled) {
            return NodeResult.failure(NodeResult.Status.CANCELLED,"Attempt cancelled");
        } catch(Exception failure) {
            var wait=io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException.budgetWait(failure);if(wait!=null)throw wait;
            attempt.future().cancel(true);return NodeResult.failure(NodeResult.Status.ERROR,"Node execution or output validation failed");
        }
    }
    private static long parallelDuration(Attempt attempt,NodeResult result) {
        long end=attempt.finished().get();if(end==0)end=System.nanoTime();
        long elapsed=TimeUnit.NANOSECONDS.toMillis(Math.max(0,end-attempt.tick()));
        return result.status()==NodeResult.Status.TIMEOUT?Math.min(elapsed,attempt.node().timeoutMs()):elapsed;
    }
    private NodeResult finishParallel(Attempt first,JsonNode input,Map<String,JsonNode> vars,Map<String,NodeResult> upstream,Mode mode,
            List<Trace> trace,java.util.function.Consumer<Trace> progress,Set<Attempt> outstanding) {
        Attempt attempt=first;
        while(true) {
            var result=awaitParallel(attempt);outstanding.remove(attempt);
            record(trace,new Trace(attempt.node().nodeId(),attempt.number(),attempt.start(),parallelDuration(attempt,result),result),progress);
            if(result.status()==NodeResult.Status.SUCCESS || result.status()==NodeResult.Status.CANCELLED || result.status()==NodeResult.Status.TIMEOUT
                    || attempt.number()>attempt.node().retries())return result;
            attempt=beginParallel(attempt.node(),attempt.number()+1,input,vars,upstream,mode);outstanding.add(attempt);
        }
    }
    private record PolicyAttempt(String sourceId,Attempt attempt,Map<String,JsonNode> variables,
                                 Map<String,NodeResult> upstream,boolean fallback) { }
    /** Caller-thread coordinator; the existing executor alone runs adapters. */
    private Run executeParallelPolicy(PipelineDefinition definition,JsonNode input,Map<String,JsonNode> initialVariables,Mode mode,
            java.util.function.Consumer<Trace> progress) {
        var contract=parallelContract(definition);var initial=NodeContext.snapshot(initialVariables==null?Map.of():initialVariables);
        for(var key:contract.owners().keySet())if(initial.containsKey(key))throw new IllegalArgumentException("Parallel exports cannot overwrite initial variables");
        var vars=new LinkedHashMap<>(initial);var upstream=new LinkedHashMap<String,NodeResult>();
        var trace=new ArrayList<Trace>();var findings=new ArrayList<Finding>();
        var nodes=new LinkedHashMap<String,PipelineDefinition.Node>();definition.nodes().forEach(node->nodes.put(node.nodeId(),node));
        var remaining=new LinkedHashSet<String>();definition.nodes().stream().filter(PipelineDefinition.Node::enabled).forEach(node->remaining.add(node.nodeId()));
        var active=new LinkedHashMap<String,PolicyAttempt>();
        // At most 200 sources, four attempts each and one four-attempt fallback per source.
        BlockingQueue<String> completions=new LinkedBlockingQueue<>(1600);
        boolean partial=false,review=false,satisfied=false;Status finalStatus=null;RuntimeException executionFailure=null;
        try {
            execution: while(!remaining.isEmpty() || !active.isEmpty()) {
                if(Thread.currentThread().isInterrupted()){finalStatus=Status.CANCELLED;break;}
                // Observe completed work before filling a newly vacant slot: satisfaction must prevent new calls.
                PolicyAttempt observed=readyPolicyAttempt(active.values());
                if(observed==null) {
                    boolean advanced=false;
                    for(var iterator=remaining.iterator();iterator.hasNext() && active.size()<executor.getMaximumPoolSize();) {
                        if(Thread.currentThread().isInterrupted()){finalStatus=Status.CANCELLED;break execution;}
                        String id=iterator.next();var node=nodes.get(id);
                        if(!upstream.keySet().containsAll(node.dependsOn()))continue;
                        iterator.remove();advanced=true;
                        var scopedVars=new LinkedHashMap<>(initial);var scopedUpstream=new LinkedHashMap<String,NodeResult>();
                        for(var ancestor:contract.ancestors().get(id)) {
                            var prior=upstream.get(ancestor);
                            if(prior!=null){scopedUpstream.put(ancestor,prior);if(prior.status()==NodeResult.Status.SUCCESS)scopedVars.putAll(prior.variables());}
                        }
                        NodeResult skipped=null;
                        if(node.dependsOn().stream().anyMatch(dependency->upstream.get(dependency).status()!=NodeResult.Status.SUCCESS)) {
                            skipped=NodeResult.failure(NodeResult.Status.SKIPPED,"Dependency unavailable");partial=true;review=true;
                        } else if(!matches(node.condition(),context(input,scopedVars,scopedUpstream)))
                            skipped=NodeResult.failure(NodeResult.Status.SKIPPED,"Condition not met");
                        if(skipped!=null){upstream.put(id,skipped);record(trace,new Trace(id,0,Instant.now(),0,skipped),progress);}
                        else {
                            var attempt=beginParallel(node,1,input,scopedVars,scopedUpstream,mode,completions);
                            active.put(id,new PolicyAttempt(id,attempt,NodeContext.snapshot(scopedVars),Map.copyOf(scopedUpstream),false));
                        }
                        // A fast adapter may complete during submission; inspect it before launching another node.
                        if(readyPolicyAttempt(active.values())!=null)break;
                    }
                    observed=readyPolicyAttempt(active.values());
                    if(observed==null) {
                        if(active.isEmpty()) {
                            if(!remaining.isEmpty() && !advanced)throw new IllegalStateException("Parallel DAG cannot make progress");
                            continue;
                        }
                        long wait=Long.MAX_VALUE;
                        for(var pending:active.values())wait=Math.min(wait,deadline(pending.attempt())-System.nanoTime());
                        try {completions.poll(Math.max(1,wait),TimeUnit.NANOSECONDS);}
                        catch(InterruptedException interrupted){Thread.currentThread().interrupt();finalStatus=Status.CANCELLED;break;}
                        continue;
                    }
                }
                var attempt=observed.attempt();var result=awaitParallel(attempt);
                active.remove(observed.sourceId());
                var item=new Trace(attempt.node().nodeId(),attempt.number(),attempt.start(),parallelDuration(attempt,result),result);
                trace.add(item);
                // Preserve successful facts even when the caller's lease/progress callback throws.
                if(result.status()==NodeResult.Status.SUCCESS) {
                    upstream.put(observed.sourceId(),result);findings.addAll(result.findings());vars.putAll(result.variables());
                }
                progress.accept(item);
                if(Thread.currentThread().isInterrupted()){finalStatus=Status.CANCELLED;break;}
                if(result.status()==NodeResult.Status.CANCELLED){finalStatus=Status.CANCELLED;break;}
                if(result.status()!=NodeResult.Status.SUCCESS) {
                    if(result.status()!=NodeResult.Status.TIMEOUT && attempt.number()<=attempt.node().retries()) {
                        var retry=beginParallel(attempt.node(),attempt.number()+1,input,observed.variables(),observed.upstream(),mode,completions);
                        active.put(observed.sourceId(),new PolicyAttempt(observed.sourceId(),retry,observed.variables(),observed.upstream(),observed.fallback()));
                        continue;
                    }
                    var source=nodes.get(observed.sourceId());
                    if(!observed.fallback() && source.onError()==PipelineDefinition.OnError.FALLBACK) {
                        var fallback=beginParallel(nodes.get(source.fallbackNodeId()),1,input,observed.variables(),observed.upstream(),mode,completions);
                        active.put(observed.sourceId(),new PolicyAttempt(observed.sourceId(),fallback,observed.variables(),observed.upstream(),true));
                        continue;
                    }
                    upstream.put(observed.sourceId(),result);partial=true;
                    review|=source.onError()==PipelineDefinition.OnError.MARK_REVIEW || source.onError()==PipelineDefinition.OnError.FALLBACK;
                    if(source.onError()==PipelineDefinition.OnError.FAIL_PIPELINE){finalStatus=Status.FAILED;break execution;}
                }
                if(completionSatisfied(definition.completionPolicy(),input,vars,upstream)){satisfied=true;break;}
            }
        } catch(RuntimeException failure){executionFailure=failure;}
        finally {
            for(var pending:active.values()) {
                var attempt=pending.attempt();NodeResult abandoned;
                // A slow progress callback must not turn an already expired attempt into planned cancellation.
                // Observe completed/expired attempts through the same deadline and output checks as normal work.
                if(attempt.future()!=null && !attempt.future().isDone()
                        && System.nanoTime()-deadline(attempt)<0 && attempt.future().cancel(true))
                    abandoned=NodeResult.failure(NodeResult.Status.CANCELLED,satisfied?POLICY_SATISFIED_REASON:"Parallel execution stopped");
                else {
                    try {abandoned=awaitParallel(attempt);}
                    catch(RuntimeException failure) {
                        if(executionFailure==null)executionFailure=failure;else if(executionFailure!=failure)executionFailure.addSuppressed(failure);
                        abandoned=NodeResult.failure(NodeResult.Status.ERROR,"Attempt interrupted by execution deferral");
                    }
                }
                var item=new Trace(attempt.node().nodeId(),attempt.number(),attempt.start(),parallelDuration(attempt,abandoned),abandoned);
                trace.add(item);
                if(abandoned.status()==NodeResult.Status.SUCCESS){findings.addAll(abandoned.findings());vars.putAll(abandoned.variables());}
                else if(abandoned.status()!=NodeResult.Status.CANCELLED) {
                    partial=true;var source=nodes.get(pending.sourceId());
                    review|=source.onError()==PipelineDefinition.OnError.MARK_REVIEW || source.onError()==PipelineDefinition.OnError.FALLBACK;
                    if(source.onError()==PipelineDefinition.OnError.FAIL_PIPELINE)finalStatus=Status.FAILED;
                }
                try {progress.accept(item);}
                catch(RuntimeException failure){if(executionFailure==null)executionFailure=failure;else if(executionFailure!=failure)executionFailure.addSuppressed(failure);}
            }
            if(satisfied)for(String id:remaining) {
                var item=new Trace(id,0,Instant.now(),0,NodeResult.failure(NodeResult.Status.SKIPPED,POLICY_SATISFIED_REASON));
                trace.add(item);
                try {progress.accept(item);}
                catch(RuntimeException failure){if(executionFailure==null)executionFailure=failure;else if(executionFailure!=failure)executionFailure.addSuppressed(failure);}
            }
        }
        if(executionFailure!=null)throw executionFailure;
        if(Thread.currentThread().isInterrupted() && finalStatus!=Status.FAILED)finalStatus=Status.CANCELLED;
        return new Run(finalStatus!=null?finalStatus:partial?Status.PARTIAL_SUCCESS:Status.SUCCESS,trace,findings,vars,review,
                satisfied?CompletionReason.POLICY_SATISFIED:null);
    }
    /** Planned policy cancellation retains its trace while leaving the missing fact unevaluated. */
    public static boolean policySkipped(Run run,Trace trace) {
        return run.completionReason()==CompletionReason.POLICY_SATISFIED && trace!=null
                && trace.result().status()==NodeResult.Status.CANCELLED && POLICY_SATISFIED_REASON.equals(trace.result().error());
    }
    private static long deadline(Attempt attempt) {return attempt.tick()+TimeUnit.MILLISECONDS.toNanos(attempt.node().timeoutMs());}
    private static PolicyAttempt readyPolicyAttempt(Collection<PolicyAttempt> active) {
        long now=System.nanoTime();PolicyAttempt selected=null;long first=Long.MAX_VALUE;
        for(var pending:active) {
            var attempt=pending.attempt();boolean done=attempt.early()!=null || attempt.future().isDone();
            if(!done && now-deadline(attempt)<0)continue;
            long event=done && attempt.finished().get()!=0?Math.min(attempt.finished().get(),deadline(attempt)):deadline(attempt);
            if(selected==null || event<first){selected=pending;first=event;}
        }
        return selected;
    }
    private Run executeParallel(PipelineDefinition definition,JsonNode input,Map<String,JsonNode> initialVariables,Mode mode,
            java.util.function.Consumer<Trace> progress) {
        var contract=parallelContract(definition);var initial=NodeContext.snapshot(initialVariables==null?Map.of():initialVariables);
        for(var key:contract.owners().keySet())if(initial.containsKey(key))throw new IllegalArgumentException("Parallel exports cannot overwrite initial variables");
        var vars=new LinkedHashMap<>(initial);var upstream=new LinkedHashMap<String,NodeResult>();var trace=new ArrayList<Trace>();var findings=new ArrayList<Finding>();
        var nodes=new LinkedHashMap<String,PipelineDefinition.Node>();definition.nodes().forEach(node->nodes.put(node.nodeId(),node));
        var remaining=new LinkedHashSet<String>();definition.nodes().stream().filter(PipelineDefinition.Node::enabled).forEach(node->remaining.add(node.nodeId()));
        var outstanding=new LinkedHashSet<Attempt>();boolean partial=false,review=false;Status finalStatus=null;RuntimeException executionFailure=null;
        try {
            execution: while(!remaining.isEmpty()) {
                if(Thread.currentThread().isInterrupted()){finalStatus=Status.CANCELLED;break execution;}
                var ready=remaining.stream().filter(id->upstream.keySet().containsAll(nodes.get(id).dependsOn())).limit(executor.getMaximumPoolSize()).toList();
                if(ready.isEmpty())throw new IllegalStateException("Parallel DAG cannot make progress");
                var started=new LinkedHashMap<String,Attempt>();var snapshots=new HashMap<String,Map<String,JsonNode>>();var previous=new HashMap<String,Map<String,NodeResult>>();
                for(var id:ready) {
                    var node=nodes.get(id);var scopedVars=new LinkedHashMap<>(initial);var scopedUpstream=new LinkedHashMap<String,NodeResult>();
                    for(var ancestor:contract.ancestors().get(id)) {
                        var prior=upstream.get(ancestor);if(prior!=null){scopedUpstream.put(ancestor,prior);if(prior.status()==NodeResult.Status.SUCCESS)scopedVars.putAll(prior.variables());}
                    }
                    if(node.dependsOn().stream().anyMatch(dependency->upstream.get(dependency).status()!=NodeResult.Status.SUCCESS)) {
                        var skipped=NodeResult.failure(NodeResult.Status.SKIPPED,"Dependency unavailable");upstream.put(id,skipped);
                        record(trace,new Trace(id,0,Instant.now(),0,skipped),progress);partial=true;review=true;continue;
                    }
                    if(!matches(node.condition(),context(input,scopedVars,scopedUpstream))) {
                        var skipped=NodeResult.failure(NodeResult.Status.SKIPPED,"Condition not met");upstream.put(id,skipped);record(trace,new Trace(id,0,Instant.now(),0,skipped),progress);continue;
                    }
                    snapshots.put(id,NodeContext.snapshot(scopedVars));previous.put(id,Map.copyOf(scopedUpstream));
                    var attempt=beginParallel(node,1,input,scopedVars,scopedUpstream,mode);started.put(id,attempt);outstanding.add(attempt);
                }
                for(var entry:started.entrySet()) {
                    var node=nodes.get(entry.getKey());var scopedVars=snapshots.get(node.nodeId());var scopedUpstream=previous.get(node.nodeId());
                    var result=finishParallel(entry.getValue(),input,scopedVars,scopedUpstream,mode,trace,progress,outstanding);
                    if(result.status()==NodeResult.Status.CANCELLED){finalStatus=Status.CANCELLED;break execution;}
                    if(result.status()!=NodeResult.Status.SUCCESS && node.onError()==PipelineDefinition.OnError.FALLBACK) {
                        var fallback=beginParallel(nodes.get(node.fallbackNodeId()),1,input,scopedVars,scopedUpstream,mode);outstanding.add(fallback);
                        result=finishParallel(fallback,input,scopedVars,scopedUpstream,mode,trace,progress,outstanding);
                    }
                    if(result.status()==NodeResult.Status.CANCELLED){finalStatus=Status.CANCELLED;break execution;}
                    upstream.put(node.nodeId(),result);
                    if(result.status()!=NodeResult.Status.SUCCESS) {
                        partial=true;review|=node.onError()==PipelineDefinition.OnError.MARK_REVIEW || node.onError()==PipelineDefinition.OnError.FALLBACK;
                        if(node.onError()==PipelineDefinition.OnError.FAIL_PIPELINE){finalStatus=Status.FAILED;break execution;}
                    } else {findings.addAll(result.findings());vars.putAll(result.variables());}
                }
                remaining.removeAll(ready);
            }
        } catch(RuntimeException failure) {executionFailure=failure;}
        finally {
            // Every submitted attempt is cancelled or observed. Completion callbacks stay on the caller thread.
            for(var attempt:outstanding) {
                NodeResult abandoned;
                if(attempt.future()!=null && !attempt.future().isDone() && attempt.future().cancel(true)){abandoned=NodeResult.failure(NodeResult.Status.CANCELLED,"Parallel execution stopped");}
                else {try{abandoned=awaitParallel(attempt);}catch(RuntimeException failure){abandoned=NodeResult.failure(NodeResult.Status.ERROR,"Attempt interrupted by execution deferral");}}
                var item=new Trace(attempt.node().nodeId(),attempt.number(),attempt.start(),parallelDuration(attempt,abandoned),abandoned);
                trace.add(item);
                if(abandoned.status()==NodeResult.Status.SUCCESS){findings.addAll(abandoned.findings());vars.putAll(abandoned.variables());}
                try {progress.accept(item);}
                catch(RuntimeException callbackFailure){if(executionFailure==null)executionFailure=callbackFailure;else if(executionFailure!=callbackFailure)executionFailure.addSuppressed(callbackFailure);}
            }
        }
        if(executionFailure!=null)throw executionFailure;
        return new Run(finalStatus!=null?finalStatus:partial?Status.PARTIAL_SUCCESS:Status.SUCCESS,trace,findings,vars,review);
    }
    private NodeResult invoke(PipelineDefinition.Node node,JsonNode input,Map<String,JsonNode> vars,
                              Map<String,NodeResult> upstream,Mode mode,List<Trace> trace,java.util.function.Consumer<Trace> progress) {
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
                var budgetWait=io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException.budgetWait(ex);
                if(budgetWait!=null)throw budgetWait;
                if(future!=null) future.cancel(true);
                result=NodeResult.failure(NodeResult.Status.ERROR,ex instanceof RejectedExecutionException?"Executor capacity exhausted":"Node execution or output validation failed");
            }
            record(trace,new Trace(node.nodeId(),attempt,start,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-tick),result),progress);
            if(result.status()==NodeResult.Status.SUCCESS || result.status()==NodeResult.Status.CANCELLED) return result;
            // Avoid concurrent retries against an adapter which ignores cancellation.
            if(result.status()==NodeResult.Status.TIMEOUT) return result;
        }
        return trace.getLast().result();
    }
    private static void record(List<Trace> trace,Trace value,java.util.function.Consumer<Trace> progress) {
        trace.add(value); progress.accept(value);
    }
    private static ObjectNode context(JsonNode input,Map<String,JsonNode> vars,Map<String,NodeResult> upstream) {
        var object=JSON.createObjectNode(); object.set("input",input.deepCopy()); object.set("variables",JSON.valueToTree(vars));
        object.set("upstream",JSON.valueToTree(upstream)); return object;
    }
    /** Shared pure comparison used by node branching and numeric business predicates. */
    public static boolean matches(PipelineDefinition.Condition condition,JsonNode context) {
        if(condition==null) return true;
        JsonNode actual=context.at(condition.pointer()); JsonNode expected=condition.value();
        return switch(condition.operator()) {
            case EXISTS -> !actual.isMissingNode() && !actual.isNull();
            case EQUALS -> !actual.isMissingNode() && equalValue(actual,expected);
            case NOT_EQUALS -> !actual.isMissingNode() && !actual.isNull() && expected!=null && !equalValue(actual,expected);
            case GREATER_OR_EQUAL -> actual.isNumber() && expected!=null && expected.isNumber() && actual.decimalValue().compareTo(expected.decimalValue())>=0;
            case GREATER_THAN -> actual.isNumber() && expected!=null && expected.isNumber() && actual.decimalValue().compareTo(expected.decimalValue())>0;
            case LESS_THAN -> actual.isNumber() && expected!=null && expected.isNumber() && actual.decimalValue().compareTo(expected.decimalValue())<0;
            case LESS_OR_EQUAL -> actual.isNumber() && expected!=null && expected.isNumber() && actual.decimalValue().compareTo(expected.decimalValue())<=0;
        };
    }
    /** Scalar numeric equality follows the existing action-condition decimal comparison contract. */
    private static boolean equalValue(JsonNode actual,JsonNode expected) {
        return actual.isNumber() && expected!=null && expected.isNumber()
                ?actual.decimalValue().compareTo(expected.decimalValue())==0:actual.equals(expected);
    }
    private static void validatePointer(String pointer) {
        if(pointer==null || (!pointer.isEmpty() && !pointer.startsWith("/"))) throw new IllegalArgumentException("Expected JSON pointer");
        com.fasterxml.jackson.core.JsonPointer.compile(pointer);
    }
    @Override public void close() { executor.shutdownNow(); }
}
