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
                       EmpiricalConfidenceCalibration.Artifact calibrationCandidate,
                       @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                       CalibrationComparison calibrationComparison) {
        public Node(String nodeId,String engineType,String engineVersion,String findingCode,
                    FindingEvaluationMetrics.ConfidenceDiagnostics diagnostics,EmpiricalConfidenceCalibration.Artifact calibrationCandidate){
            this(nodeId,engineType,engineVersion,findingCode,diagnostics,calibrationCandidate,null);
        }
        public Node(String nodeId,String engineType,String engineVersion,String findingCode,FindingEvaluationMetrics.ConfidenceDiagnostics diagnostics){
            this(nodeId,engineType,engineVersion,findingCode,diagnostics,null);
        }
    }
    public record CalibrationComparison(String schemaVersion,String artifactHash,long originalMeasured,
            java.math.BigDecimal mappedCoverage,FindingEvaluationMetrics.ConfidenceDiagnostics pairedRaw,
            FindingEvaluationMetrics.ConfidenceDiagnostics pairedCalibrated,java.math.BigDecimal brierDelta,
            java.math.BigDecimal expectedCalibrationErrorDelta) { }
    public record Summary(String schemaVersion,List<Node> nodes){
        public Summary {nodes=List.copyOf(nodes);}
    }
    private record Source(String engineType,String version,String code,String nodeHash,String calibrationHash) {
        boolean calibrated(){return calibrationHash!=null;}
    }
    private final Map<String,Source> sources=new TreeMap<>();
    private final Map<String,FindingEvaluationMetrics.ConfidenceCounter> counters=new TreeMap<>();
    private final Map<String,FindingEvaluationMetrics.ConfidenceCounter> pairedRaw=new TreeMap<>(),pairedCalibrated=new TreeMap<>();
    public NodeConfidenceMetrics(PipelineRelease release){this(release,new com.fasterxml.jackson.databind.ObjectMapper());}
    public NodeConfidenceMetrics(PipelineRelease release,com.fasterxml.jackson.databind.ObjectMapper json){
        for(var node:release.pipeline().nodes()){
            if(!List.of("LLM","SMALL_MODEL").contains(node.type()))continue;
            String code=node.config().path("findingDefinition").path("code").asText();
            var source=new Source(node.type(),node.version(),code,io.github.opensabre.iqc.selector.SelectorAssetService.hash(json.valueToTree(node).toString()),
                    release.fusion().calibrationReferences().stream().filter(value->node.nodeId().equals(value.nodeId())).map(EmpiricalConfidenceCalibration.Reference::contentHash).findFirst().orElse(null));
            if(code.isBlank()||sources.putIfAbsent(node.nodeId(),source)!=null)
                throw new IllegalArgumentException("model node confidence requires unique declared node and finding code");
            counters.put(node.nodeId(),new FindingEvaluationMetrics.ConfidenceCounter());
            if(source.calibrated()){
                pairedRaw.put(node.nodeId(),new FindingEvaluationMetrics.ConfidenceCounter());
                pairedCalibrated.put(node.nodeId(),new FindingEvaluationMetrics.ConfidenceCounter());
            }
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
            if(source.calibrated()){pairedRaw.get(entry.getKey()).eligible++;pairedCalibrated.get(entry.getKey()).eligible++;}
            var matches=findings.stream().filter(value->entry.getKey().equals(value.source().nodeId())&&source.code().equals(value.code())).toList();
            int last=matches.stream().mapToInt(value->value.source().runIndex()).max().orElse(0);
            var finalAttempt=matches.stream().filter(value->value.source().runIndex()==last).toList();
            // Multiple outputs for the same node/code/attempt are ambiguous, never cherry-pick the correct or confident one.
            if(finalAttempt.size()!=1)continue;
            var actual=finalAttempt.getFirst();
            if(actual.state()!=Finding.State.KNOWN||actual.valueType()!=expected.valueType()
                    ||!source.engineType().equals(actual.source().engineType())||!source.version().equals(actual.source().engineVersion()))continue;
            var confidence=actual.confidence();
            io.github.opensabre.iqc.pipeline.NodeResult outcome=null;
            if("LLM".equals(source.engineType())||source.calibrated()){
                var attempts=trace.stream().filter(value->entry.getKey().equals(value.nodeId())&&value.attempt()==last).toList();
                confidence=null;
                if(attempts.size()==1&&attempts.getFirst().result().status()==io.github.opensabre.iqc.pipeline.NodeResult.Status.SUCCESS){
                    outcome=attempts.getFirst().result();confidence=CalibrationRuntime.rawConfidence(actual,outcome,source.calibrated());
                }
            }
            if(confidence!=null){
                boolean correct=FindingEvaluationMetrics.equal(expected.value(),actual.value());counter.add(confidence,correct);
                if(source.calibrated()&&outcome!=null&&actual.confidence()!=null&&bound(source,outcome,confidence,actual)){
                    pairedRaw.get(entry.getKey()).add(confidence,correct);
                    pairedCalibrated.get(entry.getKey()).add(actual.confidence(),correct);
                }
            }
        }
    }
    public Summary summary(){return summary(null,null,null,null,null);}
    public Summary summary(String taskId,String executionId,String datasetHash,String pipelineHash,String evidenceHash){
        return new Summary("iqc-node-confidence-diagnostics-v1",sources.entrySet().stream().map(entry->{
            var source=entry.getValue();return new Node(entry.getKey(),source.engineType(),source.version(),source.code(),
                    counters.get(entry.getKey()).summary("RAW_NODE_VALUE_CORRECTNESS_ON_KNOWN_GOLD"),taskId==null||counters.get(entry.getKey()).scored==0?null:
                    counters.get(entry.getKey()).fit(new EmpiricalConfidenceCalibration.Source(taskId,executionId,datasetHash,
                            pipelineHash,evidenceHash,entry.getKey(),source.nodeHash(),source.code())),comparison(entry.getKey(),source));
        }).toList());
    }
    private CalibrationComparison comparison(String nodeId,Source source){
        if(!source.calibrated())return null;
        var raw=pairedRaw.get(nodeId).summary("PAIRED_RAW_NODE_VALUE_CORRECTNESS_ON_KNOWN_GOLD");
        var calibrated=pairedCalibrated.get(nodeId).summary("PAIRED_CALIBRATED_NODE_VALUE_CORRECTNESS_ON_KNOWN_GOLD");
        long measured=counters.get(nodeId).scored;
        return new CalibrationComparison("iqc-calibration-paired-diagnostics-v1",source.calibrationHash(),measured,
                FindingEvaluationMetrics.ratio(raw.scored(),measured),raw,calibrated,
                difference(calibrated.brierScore(),raw.brierScore()),difference(calibrated.expectedCalibrationError(),raw.expectedCalibrationError()));
    }
    private static java.math.BigDecimal difference(java.math.BigDecimal after,java.math.BigDecimal before){
        return after==null||before==null?null:after.subtract(before);
    }
    private static boolean bound(Source source,io.github.opensabre.iqc.pipeline.NodeResult outcome,java.math.BigDecimal raw,Finding actual){
        if(outcome.findings().size()!=1)return false;
        var traced=outcome.findings().getFirst();
        if(!actual.id().equals(traced.id())||!actual.source().equals(traced.source())||actual.state()!=traced.state()
                ||actual.valueType()!=traced.valueType()||!FindingEvaluationMetrics.equal(actual.value(),traced.value()))return false;
        var proof=outcome.metrics().get(CalibrationRuntime.KEY);
        return proof!=null&&proof.isObject()&&proof.size()==6&&CalibrationRuntime.SCHEMA.equals(proof.path("schemaVersion").asText())
                &&actual.id().equals(proof.path("findingId").textValue())
                &&source.calibrationHash().equals(proof.path("artifactHash").textValue())
                &&proof.path("supported").isBoolean()&&proof.path("supported").booleanValue()
                &&matches(proof.path("rawConfidence"),raw)&&matches(proof.path("calibratedConfidence"),actual.confidence());
    }
    private static boolean matches(com.fasterxml.jackson.databind.JsonNode value,java.math.BigDecimal expected){
        return value.isNumber()&&Double.isFinite(value.doubleValue())&&value.decimalValue().compareTo(expected)==0;
    }

}
