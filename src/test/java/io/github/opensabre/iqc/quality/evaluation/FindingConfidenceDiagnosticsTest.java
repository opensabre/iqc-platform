package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.fusion.FindingFusion;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import io.github.opensabre.iqc.pipelineasset.PipelinePublicationGateService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Independent numerical and frozen-report compatibility checks for confidence diagnostics. */
class FindingConfidenceDiagnosticsTest {
    private final ObjectMapper json=new ObjectMapper();
    private FindingFusion.Result actual(Finding.ValueType type,Finding.State state,Object value,String confidence){
        return new FindingFusion.Result("FACT",type,state,value==null?null:json.valueToTree(value),Finding.Severity.NONE,
                List.of(),List.of(),List.of(),List.of(),null,"p",1,confidence==null?null:new BigDecimal(confidence));
    }
    private FindingEvaluationMetrics.Sample sample(String id,Finding.ValueType type,Finding.State state,Object expected,FindingFusion.Result... actual){
        var gold=new GoldConversation(GoldConversation.SCHEMA,id,"c-"+id,
                List.of(new GoldConversation.Message("m","agent","processed text",1,null)),null,
                List.of(new GoldFinding("FACT",type,state,expected==null?null:json.valueToTree(expected),List.of())),
                "annotator","reviewer","reviewed candidate",List.of(),GoldConversation.Difficulty.BASIC,null);
        return new FindingEvaluationMetrics.Sample(gold,List.of(actual));
    }
    @Test void endpointsAndTenthBoundaryMeasureValueCorrectnessIncludingKnownFalse(){
        var result=FindingEvaluationMetrics.evaluate(List.of(
                sample("one",Finding.ValueType.BOOLEAN,Finding.State.KNOWN,false,actual(Finding.ValueType.BOOLEAN,Finding.State.KNOWN,false,"1")),
                sample("zero",Finding.ValueType.BOOLEAN,Finding.State.KNOWN,true,actual(Finding.ValueType.BOOLEAN,Finding.State.KNOWN,false,"0")),
                sample("tenth",Finding.ValueType.BOOLEAN,Finding.State.KNOWN,true,actual(Finding.ValueType.BOOLEAN,Finding.State.KNOWN,true,"0.1"))));
        var d=result.confidenceDiagnostics();assertThat(d.scored()).isEqualTo(3);assertThat(d.coverage()).isEqualByComparingTo("1");
        assertThat(d.brierScore()).isEqualByComparingTo("0.27");assertThat(d.expectedCalibrationError()).isEqualByComparingTo("0.3");
        assertThat(d.bins()).hasSize(10);assertThat(d.bins().get(0).count()).isEqualTo(1);
        assertThat(d.bins().get(1).count()).isEqualTo(1);assertThat(d.bins().get(9).count()).isEqualTo(1);
        assertThat(d.bins().get(9).upperInclusive()).isTrue();assertThat(d.bins().get(0).upperInclusive()).isFalse();
        assertThat(d.bins().get(9).observedAccuracy()).isEqualByComparingTo("1");
        assertThat(result.aggregate().counts().trueNegative()).isEqualTo(1);
    }
    @Test void missingUncertainWrongTypeAndMissingConfidenceStayUnscored(){
        var d=FindingEvaluationMetrics.evaluate(List.of(
                sample("missing",Finding.ValueType.BOOLEAN,Finding.State.KNOWN,true),
                sample("unknown",Finding.ValueType.BOOLEAN,Finding.State.KNOWN,true,actual(Finding.ValueType.BOOLEAN,Finding.State.UNKNOWN,null,null)),
                sample("error",Finding.ValueType.BOOLEAN,Finding.State.KNOWN,true,actual(Finding.ValueType.BOOLEAN,Finding.State.ERROR,null,null)),
                sample("type",Finding.ValueType.BOOLEAN,Finding.State.KNOWN,true,actual(Finding.ValueType.STRING,Finding.State.KNOWN,"true","1")),
                sample("confidence",Finding.ValueType.BOOLEAN,Finding.State.KNOWN,true,actual(Finding.ValueType.BOOLEAN,Finding.State.KNOWN,true,null))
        )).confidenceDiagnostics();
        assertThat(d.eligibleKnownGold()).isEqualTo(5);assertThat(d.scored()).isZero();assertThat(d.unscored()).isEqualTo(5);
        assertThat(d.coverage()).isEqualByComparingTo("0");assertThat(d.brierScore()).isNull();assertThat(d.expectedCalibrationError()).isNull();
        assertThat(d.bins()).allSatisfy(bin->{assertThat(bin.count()).isZero();assertThat(bin.meanConfidence()).isNull();assertThat(bin.observedAccuracy()).isNull();});
    }
    @Test void uncertainGoldDoesNotInventDenominators(){
        var d=FindingEvaluationMetrics.evaluate(List.of(sample("uncertain",Finding.ValueType.BOOLEAN,Finding.State.UNKNOWN,null))).confidenceDiagnostics();
        assertThat(d.eligibleKnownGold()).isZero();assertThat(d.unscored()).isZero();assertThat(d.coverage()).isNull();
        assertThat(d.brierScore()).isNull();assertThat(d.expectedCalibrationError()).isNull();
    }
    @Test void exactNumericDifferenceRemainsWrongWhileScaleDifferenceMatches() throws Exception {
        var exact=new BigDecimal("0.900000000000000001");
        var samples=List.of(
                sample("different",Finding.ValueType.NUMBER,Finding.State.KNOWN,exact,actual(Finding.ValueType.NUMBER,Finding.State.KNOWN,new BigDecimal("0.9"),"1")),
                sample("same",Finding.ValueType.NUMBER,Finding.State.KNOWN,exact,actual(Finding.ValueType.NUMBER,Finding.State.KNOWN,new BigDecimal("0.9000000000000000010"),"1")));
        String original=json.writeValueAsString(samples);var metrics=FindingEvaluationMetrics.evaluate(samples);
        assertThat(metrics.confidenceDiagnostics().brierScore()).isEqualByComparingTo("0.5");
        assertThat(metrics.confidenceDiagnostics().expectedCalibrationError()).isEqualByComparingTo("0.5");
        assertThat(metrics.aggregate().counts().valueMatches()).isEqualTo(1);assertThat(json.writeValueAsString(samples)).isEqualTo(original);
    }
    @Test void historicalReportEvidenceWithoutDiagnosticsKeepsSerializedBytesAndHash() throws Exception {
        var measured=FindingEvaluationMetrics.evaluate(List.of(sample("legacy",Finding.ValueType.BOOLEAN,Finding.State.KNOWN,true)));
        var legacy=new FindingEvaluationMetrics.Result(measured.schemaVersion(),1,measured.aggregate(),measured.byCode(),0);
        var report=new PipelineEvaluationService.Report("iqc-report-v1","task","eid","hash",1,1,1,true,List.of(),Map.of(),legacy,null,null,null,null);
        var evidence=new PipelinePublicationGateService.ReportEvidence("QUALITY","task","eid","dataset",1,"dataset-hash","report-hash",report);
        String body=json.writeValueAsString(evidence);assertThat(body).doesNotContain("confidenceDiagnostics");
        var restored=json.readValue(body,PipelinePublicationGateService.ReportEvidence.class);
        assertThat(restored.report().findingMetrics().confidenceDiagnostics()).isNull();
        String replay=json.writeValueAsString(restored);assertThat(replay).isEqualTo(body);
        assertThat(PipelineAssetService.hash(replay)).isEqualTo(PipelineAssetService.hash(body));
    }
}
