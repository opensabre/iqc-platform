package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.pipeline.NodeResult;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Semantic node-output agreement over independently stored, validated evaluation artifacts. */
public final class ModelConsistencyMetrics {
    private ModelConsistencyMetrics() { }
    public record Observation(String artifactId,NodeResult result) { }
    public record Pair(Observation selected,Observation reference) { }
    public record Report(String schemaVersion,String scope,int expectedPairs,int comparablePairs,int agreeingPairs,
                         BigDecimal comparisonCoverage,BigDecimal agreementRate,Map<String,Integer> excludedReasons) { }
    /** Missing evidence never becomes a successful comparison; callers own artifact and frozen-input validation. */
    public static Report evaluate(List<Pair> pairs,ObjectMapper json){
        int comparable=0,agreeing=0;var reasons=new TreeMap<String,Integer>();
        for(var pair:pairs){
            String reason=excluded(pair);
            if(reason!=null){reasons.merge(reason,1,Integer::sum);continue;}
            comparable++;
            if(meaning(pair.selected().result(),json).equals(meaning(pair.reference().result(),json)))agreeing++;
        }
        return new Report("iqc-model-consistency-v1","INDEPENDENT_SAME_MODEL_INPUT_NODE_FINDING_VALUE_STATE_SEVERITY",pairs.size(),
                comparable,agreeing,ratio(comparable,pairs.size()),ratio(agreeing,comparable),Collections.unmodifiableMap(reasons));
    }
    private static String excluded(Pair pair){
        if(pair.selected()==null||pair.reference()==null)return "MISSING_ARTIFACT";
        if(Objects.equals(pair.selected().artifactId(),pair.reference().artifactId()))return "SAME_ARTIFACT";
        var left=pair.selected().result();var right=pair.reference().result();
        if(left==null||right==null)return "MISSING_NODE";
        if(left.status()!=NodeResult.Status.SUCCESS||right.status()!=NodeResult.Status.SUCCESS)return "UNSUCCESSFUL_NODE";
        if(left.findings().isEmpty()||right.findings().isEmpty()||java.util.stream.Stream.concat(left.findings().stream(),right.findings().stream())
                .anyMatch(f->f.state()!=Finding.State.KNOWN))return "UNRESOLVED_OUTPUT";
        var a=fingerprints(left);var b=fingerprints(right);
        if(a==null||b==null)return "UNMEASURED_INVOCATION";
        return a.equals(b)?null:"DIFFERENT_MODEL_OR_INPUT";
    }
    private static List<String> fingerprints(NodeResult result){
        var metrics=result.metrics().get("llmStructuredOutput");
        if(metrics==null||!"iqc-llm-structured-metrics-v1".equals(metrics.path("schemaVersion").asText()))return null;
        var calls=metrics.path("logicalInvocations");var valid=metrics.path("validOutputs");var failed=metrics.path("invalidOrFailedOutputs");
        var values=metrics.path("invocationFingerprints");
        if(!calls.isIntegralNumber()||!calls.canConvertToInt()||calls.intValue()<1||calls.intValue()>10000
                ||!valid.isIntegralNumber()||!valid.canConvertToInt()||valid.intValue()!=calls.intValue()
                ||!failed.isIntegralNumber()||!failed.canConvertToInt()||failed.intValue()!=0
                ||!values.isArray()||values.size()!=calls.intValue())return null;
        var fingerprints=new ArrayList<String>();
        for(var value:values){
            if(!"iqc-llm-invocation-fingerprint-v1".equals(value.path("schemaVersion").asText())
                    ||!value.path("modelKey").asText().matches("[a-f0-9]{64}")||!value.path("inputHash").asText().matches("[a-f0-9]{64}"))return null;
            fingerprints.add(value.path("modelKey").asText()+":"+value.path("inputHash").asText());
        }
        return fingerprints;
    }
    private static List<String> meaning(NodeResult result,ObjectMapper json){
        return result.findings().stream().map(f->{var value=new LinkedHashMap<String,Object>();
            value.put("code",f.code());value.put("type",f.valueType());value.put("state",f.state());value.put("value",f.value());
            value.put("severity",f.severity());return SelectorAssetService.hash(json.valueToTree(value).toString());
        }).sorted().toList();
    }
    private static BigDecimal ratio(int n,int d){return d==0?null:BigDecimal.valueOf(n).divide(BigDecimal.valueOf(d),6,RoundingMode.HALF_UP);}
}
