package io.github.opensabre.iqc.quality.evaluation;

import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.pipelineasset.PipelineRelease;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Diagnostics of raw model-reported confidence, never a claim that a model has been calibrated. */
public final class NodeConfidenceMetrics {
    public record Node(String nodeId,String engineType,String engineVersion,String findingCode,
                       FindingEvaluationMetrics.ConfidenceDiagnostics diagnostics) { }
    public record Summary(String schemaVersion,List<Node> nodes){
        public Summary {nodes=List.copyOf(nodes);}
    }
    private record Source(String engineType,String version,String code) { }
    private final Map<String,Source> sources=new TreeMap<>();
    private final Map<String,FindingEvaluationMetrics.ConfidenceCounter> counters=new TreeMap<>();
    public NodeConfidenceMetrics(PipelineRelease release){
        for(var node:release.pipeline().nodes()){
            if(!List.of("LLM","SMALL_MODEL").contains(node.type()))continue;
            String code=node.config().path("findingDefinition").path("code").asText();
            if(code.isBlank()||sources.putIfAbsent(node.nodeId(),new Source(node.type(),node.version(),code))!=null)
                throw new IllegalArgumentException("model node confidence requires unique declared node and finding code");
            counters.put(node.nodeId(),new FindingEvaluationMetrics.ConfidenceCounter());
        }
    }
    public void add(GoldConversation gold,List<Finding> findings){add(gold,findings,List.of());}
    public void add(GoldConversation gold,List<Finding> findings,List<io.github.opensabre.iqc.pipeline.PipelineRuntime.Trace> trace){
        for(var entry:sources.entrySet()){
            var source=entry.getValue();var counter=counters.get(entry.getKey());
            var expected=gold.findings().stream().filter(value->source.code().equals(value.code())&&value.state()==Finding.State.KNOWN)
                    .findFirst().orElse(null);
            if(expected==null)continue;
            counter.eligible++;
            var matches=findings.stream().filter(value->entry.getKey().equals(value.source().nodeId())&&source.code().equals(value.code())).toList();
            int last=matches.stream().mapToInt(value->value.source().runIndex()).max().orElse(0);
            var finalAttempt=matches.stream().filter(value->value.source().runIndex()==last).toList();
            // Multiple outputs for the same node/code/attempt are ambiguous, never cherry-pick the correct or confident one.
            if(finalAttempt.size()!=1)continue;
            var actual=finalAttempt.getFirst();
            if(actual.state()!=Finding.State.KNOWN||actual.valueType()!=expected.valueType()
                    ||!source.engineType().equals(actual.source().engineType())||!source.version().equals(actual.source().engineVersion()))continue;
            var confidence=actual.confidence();
            if("LLM".equals(source.engineType())){
                // Self-reported LLM confidence is diagnostic metadata, never silently promoted into calibrated fusion input.
                var attempts=trace.stream().filter(value->entry.getKey().equals(value.nodeId())&&value.attempt()==last).toList();
                confidence=null;
                if(attempts.size()==1&&attempts.getFirst().result().status()==io.github.opensabre.iqc.pipeline.NodeResult.Status.SUCCESS){
                    var metrics=attempts.getFirst().result().metrics().get("llmStructuredOutput");
                    if(metrics!=null&&metrics.path("logicalInvocations").isIntegralNumber()&&metrics.path("logicalInvocations").canConvertToLong()&&metrics.path("logicalInvocations").longValue()==1
                            &&metrics.path("validOutputs").isIntegralNumber()&&metrics.path("validOutputs").canConvertToLong()&&metrics.path("validOutputs").longValue()==1
                            &&metrics.path("reportedConfidence").isNumber()&&Double.isFinite(metrics.path("reportedConfidence").doubleValue())){
                        var value=metrics.path("reportedConfidence").decimalValue();
                        if(value.signum()>=0&&value.compareTo(java.math.BigDecimal.ONE)<=0)confidence=value;
                    }
                }
            }
            if(confidence!=null)counter.add(confidence,FindingEvaluationMetrics.equal(expected.value(),actual.value()));
        }
    }
    public Summary summary(){
        return new Summary("iqc-node-confidence-diagnostics-v1",sources.entrySet().stream().map(entry->{
            var source=entry.getValue();return new Node(entry.getKey(),source.engineType(),source.version(),source.code(),
                    counters.get(entry.getKey()).summary("RAW_NODE_VALUE_CORRECTNESS_ON_KNOWN_GOLD"));
        }).toList());
    }
}
