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
    public void add(GoldConversation gold,List<Finding> findings){
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
            if(actual.state()!=Finding.State.KNOWN||actual.valueType()!=expected.valueType()||actual.confidence()==null
                    ||!source.engineType().equals(actual.source().engineType())||!source.version().equals(actual.source().engineVersion()))continue;
            counter.add(actual.confidence(),FindingEvaluationMetrics.equal(expected.value(),actual.value()));
        }
    }
    public Summary summary(){
        return new Summary("iqc-node-confidence-diagnostics-v1",sources.entrySet().stream().map(entry->{
            var source=entry.getValue();return new Node(entry.getKey(),source.engineType(),source.version(),source.code(),
                    counters.get(entry.getKey()).summary("RAW_NODE_VALUE_CORRECTNESS_ON_KNOWN_GOLD"));
        }).toList());
    }
}
