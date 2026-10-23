package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.pipelineasset.PipelineRelease;
import io.github.opensabre.iqc.pipelinebridge.PipelineConversationEvaluator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises last-trace accounting independently of detector verdicts and physical model attempts. */
class CitationGroundingMetricsTest {
    private final ObjectMapper json=new ObjectMapper();
    private CitationGroundingMetrics metrics(int samples,boolean llm){
        var release=mock(PipelineRelease.class);
        var node=new PipelineDefinition.Node("node",llm?"LLM":"RULE","node","v1",List.of(),Map.of(),null,null,1000,0,PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
        when(release.pipeline()).thenReturn(new PipelineDefinition(PipelineDefinition.SCHEMA,List.of(node)));
        return new CitationGroundingMetrics(release,samples);
    }
    private ObjectNode diagnostic(long quotes,long invalid){return json.createObjectNode()
            .put("schemaVersion","iqc-llm-citation-grounding-v1").put("scope","RAW_EXPLICIT_CONVERSATION_QUOTE_CLAIMS")
            .put("state","MEASURED").put("outputs",1).put("recognizedOutputs",1).put("unmeasuredOutputs",0)
            .put("claimedQuotes",quotes).put("invalidQuotes",invalid).put("malformedContainers",0);}
    private PipelineRuntime.Trace trace(String id,int attempt,NodeResult.Status status,ObjectNode diagnostic){
        return new PipelineRuntime.Trace(id,attempt,Instant.EPOCH,10,new NodeResult(status,List.of(),Map.of(),
                diagnostic==null?Map.of():Map.of("llmCitationGrounding",diagnostic),null));
    }
    private PipelineConversationEvaluator.Evaluation actual(PipelineRuntime.Trace... traces){
        var actual=mock(PipelineConversationEvaluator.Evaluation.class);
        when(actual.run()).thenReturn(new PipelineRuntime.Run(PipelineRuntime.Status.SUCCESS,List.of(traces),List.of(),Map.of(),false));return actual;
    }
    @Test void lastTraceOnlyRetainsErrorDiagnosticsAndMissingSamplesInDenominator(){
        var metrics=metrics(2,true);metrics.add(actual(trace("node",1,NodeResult.Status.ERROR,diagnostic(9,9)),trace("node",2,NodeResult.Status.ERROR,diagnostic(2,1))));
        var s=metrics.summary();assertThat(s.expectedNodeSamples()).isEqualTo(2);assertThat(s.observedNodeSamples()).isEqualTo(1);
        assertThat(s.measuredNodeSamples()).isEqualTo(1);assertThat(s.unmeasuredNodeSamples()).isEqualTo(1);
        assertThat(s.claimedQuotes()).isEqualTo(2);assertThat(s.invalidQuotes()).isEqualTo(1);
        assertThat(s.measurementCoverage()).isEqualByComparingTo("0.5");assertThat(s.observedQuoteErrorRate()).isEqualByComparingTo("0.5");
    }
    @Test void skipsNonLlmUnattemptedAndLastSkippedTraceWithoutReusingEarlierSuccess(){
        var metrics=metrics(3,true);
        metrics.add(actual(trace("node",1,NodeResult.Status.SUCCESS,diagnostic(1,0)),trace("node",2,NodeResult.Status.SKIPPED,null)));
        metrics.add(actual(trace("node",0,NodeResult.Status.ERROR,diagnostic(1,1))));
        metrics.add(actual(trace("other",1,NodeResult.Status.SUCCESS,diagnostic(1,0))));
        assertThat(metrics.summary().observedNodeSamples()).isZero();assertThat(metrics.summary().unmeasuredNodeSamples()).isEqualTo(3);
        assertThat(metrics.summary().observedQuoteErrorRate()).isNull();
    }
    @Test void partialUnknownAndInvalidDiagnosticsDoNotBecomeFullyMeasured(){
        var metrics=metrics(3,true);var partial=diagnostic(2,1).put("state","PARTIAL").put("outputs",2).put("unmeasuredOutputs",1);
        metrics.add(actual(trace("node",1,NodeResult.Status.SUCCESS,partial)));
        metrics.add(actual(trace("node",1,NodeResult.Status.ERROR,diagnostic(1,2))));
        metrics.add(actual(trace("node",1,NodeResult.Status.SUCCESS,null)));
        var s=metrics.summary();assertThat(s.observedNodeSamples()).isEqualTo(3);assertThat(s.partialNodeSamples()).isEqualTo(1);
        assertThat(s.invalidDiagnosticNodeSamples()).isEqualTo(1);assertThat(s.unmeasuredNodeSamples()).isEqualTo(2);
        assertThat(s.measurementCoverage()).isEqualByComparingTo("0");assertThat(s.outputs()).isEqualTo(2);assertThat(s.observedQuoteErrorRate()).isEqualByComparingTo("0.5");
    }
    @Test void zeroQuotesAndNoLlmNeverClaimZeroErrorRate(){
        var measured=metrics(1,true);measured.add(actual(trace("node",1,NodeResult.Status.SUCCESS,diagnostic(0,0))));
        assertThat(measured.summary().measurementCoverage()).isEqualByComparingTo("1");assertThat(measured.summary().observedQuoteErrorRate()).isNull();
        assertThat(metrics(2,false).summary().measurementCoverage()).isNull();assertThat(metrics(0,true).summary().measurementCoverage()).isNull();
    }
    @Test void invalidSchemaCountsAndStateAreRejectedWithoutAggregatingTheirQuotes(){
        var invalids=List.of(diagnostic(1,0).put("schemaVersion","unknown"),diagnostic(1,0).put("scope","unknown"),
                diagnostic(1,0).put("outputs",0),diagnostic(1,0).put("claimedQuotes",1.5),diagnostic(1,0).put("invalidQuotes",-1),
                diagnostic(1,0).put("state","PARTIAL"),diagnostic(1,0).put("unmeasuredOutputs",1));
        var metrics=metrics(invalids.size(),true);for(var value:invalids)metrics.add(actual(trace("node",1,NodeResult.Status.SUCCESS,value)));
        assertThat(metrics.summary().invalidDiagnosticNodeSamples()).isEqualTo(invalids.size());assertThat(metrics.summary().claimedQuotes()).isZero();assertThat(metrics.summary().observedQuoteErrorRate()).isNull();
    }
}
