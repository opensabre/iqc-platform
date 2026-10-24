package io.github.opensabre.iqc.quality.evaluation;

import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.pipelineasset.PipelineRelease;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Diagnostics of raw model-reported confidence, never a claim that a model has been calibrated. */
public final class NodeConfidenceMetrics {
    public record Node(String nodeId,String engineType,String engineVersion,String findingCode,
                       FindingEvaluationMetrics.ConfidenceDiagnostics diagnostics,
                       @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                       EmpiricalConfidenceCalibration.Artifact calibrationCandidate) {
        public Node(String nodeId,String engineType,String engineVersion,String findingCode,FindingEvaluationMetrics.ConfidenceDiagnostics diagnostics){
            this(nodeId,engineType,engineVersion,findingCode,diagnostics,null);
        }
    }
    public record Summary(String schemaVersion,List<Node> nodes){
        public Summary {nodes=List.copyOf(nodes);}
    }
    private record Source(String engineType,String version,String code,String nodeHash,boolean calibrated) { }
    private final Map<String,Source> sources=new TreeMap<>();
    private final Map<String,FindingEvaluationMetrics.ConfidenceCounter> counters=new TreeMap<>();
    public NodeConfidenceMetrics(PipelineRelease release){this(release,new com.fasterxml.jackson.databind.ObjectMapper());}
    public NodeConfidenceMetrics(PipelineRelease release,com.fasterxml.jackson.databind.ObjectMapper json){
        for(var node:release.pipeline().nodes()){
            if(!List.of("LLM","SMALL_MODEL").contains(node.type()))continue;
            String code=node.config().path("findingDefinition").path("code").asText();
            var source=new Source(node.type(),node.version(),code,io.github.opensabre.iqc.selector.SelectorAssetService.hash(json.valueToTree(node).toString()),
                    release.fusion().calibrationReferences().stream().anyMatch(value->node.nodeId().equals(value.nodeId())));
            if(code.isBlank()||sources.putIfAbsent(node.nodeId(),source)!=null)
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
            if("LLM".equals(source.engineType())||source.calibrated()){
                var attempts=trace.stream().filter(value->entry.getKey().equals(value.nodeId())&&value.attempt()==last).toList();
                confidence=null;
                if(attempts.size()==1&&attempts.getFirst().result().status()==io.github.opensabre.iqc.pipeline.NodeResult.Status.SUCCESS)
                    confidence=CalibrationRuntime.rawConfidence(actual,attempts.getFirst().result(),source.calibrated());
            }
            if(confidence!=null)counter.add(confidence,FindingEvaluationMetrics.equal(expected.value(),actual.value()));
        }
    }
    public Summary summary(){return summary(null,null,null,null,null);}
    public Summary summary(String taskId,String executionId,String datasetHash,String pipelineHash,String evidenceHash){
        return new Summary("iqc-node-confidence-diagnostics-v1",sources.entrySet().stream().map(entry->{
            var source=entry.getValue();return new Node(entry.getKey(),source.engineType(),source.version(),source.code(),
                    counters.get(entry.getKey()).summary("RAW_NODE_VALUE_CORRECTNESS_ON_KNOWN_GOLD"),taskId==null||counters.get(entry.getKey()).scored==0?null:
                    counters.get(entry.getKey()).fit(new EmpiricalConfidenceCalibration.Source(taskId,executionId,datasetHash,
                            pipelineHash,evidenceHash,entry.getKey(),source.nodeHash(),source.code())));
        }).toList());
    }
}
