package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.pipeline.NodeResult;
import io.github.opensabre.iqc.pipelineasset.PipelineRelease;
import io.github.opensabre.iqc.pipelinebridge.PipelineConversationEvaluator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

/** Read-only summary of the last trace for each frozen sample/LLM-node pair. */
public final class CitationGroundingMetrics {
    private final Set<String> nodes=new LinkedHashSet<>();
    private final long expected;
    private long observed,measured,partial,invalidDiagnostics,outputs,recognized,unknown,quotes,invalid,malformed;
    public record Summary(String schemaVersion,String scope,long expectedNodeSamples,long observedNodeSamples,
                          long measuredNodeSamples,long partialNodeSamples,long unmeasuredNodeSamples,
                          long invalidDiagnosticNodeSamples,long outputs,long recognizedOutputs,long unmeasuredOutputs,
                          long claimedQuotes,long invalidQuotes,long malformedContainers,
                          BigDecimal measurementCoverage,BigDecimal observedQuoteErrorRate) { }
    public CitationGroundingMetrics(PipelineRelease release,int expectedSamples){
        if(expectedSamples<0)throw new IllegalArgumentException("Negative sample count");
        release.pipeline().nodes().stream().filter(node->"LLM".equals(node.type())).forEach(node->nodes.add(node.nodeId()));
        expected=(long)expectedSamples*nodes.size();
    }
    public void add(PipelineConversationEvaluator.Evaluation actual){
        var last=new LinkedHashMap<String,io.github.opensabre.iqc.pipeline.PipelineRuntime.Trace>();
        for(var trace:actual.run().trace())if(nodes.contains(trace.nodeId()))last.put(trace.nodeId(),trace);
        for(var trace:last.values()){
            if(trace.result()==null||trace.attempt()<=0||trace.result().status()==NodeResult.Status.SKIPPED)continue;
            observed++;
            JsonNode value=trace.result().metrics().get("llmCitationGrounding");
            if(value==null)continue;
            long[] counts=validated(value);
            if(counts==null){invalidDiagnostics++;continue;}
            String state=value.path("state").asText();
            if("MEASURED".equals(state))measured++;
            else if("PARTIAL".equals(state))partial++;
            outputs=add(outputs,counts[0]);recognized=add(recognized,counts[1]);unknown=add(unknown,counts[2]);
            quotes=add(quotes,counts[3]);invalid=add(invalid,counts[4]);malformed=add(malformed,counts[5]);
        }
    }
    private static long[] validated(JsonNode value){
        if(!"iqc-llm-citation-grounding-v1".equals(value.path("schemaVersion").asText())
                ||!"RAW_EXPLICIT_CONVERSATION_QUOTE_CLAIMS".equals(value.path("scope").asText()))return null;
        String[] keys={"outputs","recognizedOutputs","unmeasuredOutputs","claimedQuotes","invalidQuotes","malformedContainers"};
        long[] c=new long[keys.length];
        for(int i=0;i<keys.length;i++){
            JsonNode field=value.path(keys[i]);
            if(!field.isIntegralNumber()||!field.canConvertToLong()||field.longValue()<0)return null;
            c[i]=field.longValue();
        }
        if(c[0]<1||c[1]>c[0]||c[2]!=c[0]-c[1]||c[4]>c[3])return null;
        if(c[1]==0&&(c[3]!=0||c[5]!=0))return null;
        String state=c[1]==0?"UNMEASURED":c[2]>0||c[5]>0?"PARTIAL":"MEASURED";
        return state.equals(value.path("state").asText())?c:null;
    }
    private static long add(long a,long b){
        try{return Math.addExact(a,b);}catch(ArithmeticException overflow){throw IqcException.invalidState("引用诊断计数溢出");}
    }
    public Summary summary(){
        if(observed>expected||measured+partial>observed)throw IqcException.invalidState("引用诊断样本节点分母无效");
        return new Summary("iqc-citation-grounding-report-v1","LAST_TRACE_PER_FROZEN_SAMPLE_LLM_NODE",expected,observed,
                measured,partial,expected-measured-partial,invalidDiagnostics,outputs,recognized,unknown,quotes,invalid,malformed,
                ratio(measured,expected),ratio(invalid,quotes));
    }
    private static BigDecimal ratio(long numerator,long denominator){
        return denominator==0?null:BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator),6,RoundingMode.HALF_UP);
    }
}
