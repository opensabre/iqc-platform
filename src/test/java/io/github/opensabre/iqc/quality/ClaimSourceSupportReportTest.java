package io.github.opensabre.iqc.quality;

import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.fusion.FindingFusion;
import io.github.opensabre.iqc.quality.model.ResultReview;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import java.util.*;
import org.junit.jupiter.api.Test;
import static io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real report aggregation with controlled validated-source and organization seams. */
class ClaimSourceSupportReportTest {
    private final BusinessFindingDistributionTest fixture=new BusinessFindingDistributionTest();
    private Finding raw(String id,String engine,Finding.State state) {
        return new Finding(id,"CHECK","BUSINESS",Finding.ValueType.BOOLEAN,
                state==Finding.State.KNOWN?fixture.json.getNodeFactory().booleanNode(true):null,state,null,
                Finding.Severity.LOW,"private model reason",List.of(new Finding.Evidence("e-"+id,
                Finding.EvidenceType.CONVERSATION,"private message",null)),new Finding.Source("node-"+id,engine,"v1",1,"private input hash"));
    }
    private FindingFusion.Result fact(Finding... raw) {
        var original=fixture.fact("CHECK",Finding.State.KNOWN,true);
        return new FindingFusion.Result(original.code(),original.valueType(),original.state(),original.value(),original.severity(),
                List.of(raw),original.selectedIds(),original.rejectedIds(),original.evidence(),original.explanation(),original.policyId(),original.policyVersion());
    }
    private Finding known(String id){return raw(id,"LLM",Finding.State.KNOWN);}
    private ClaimSourceSupport support(String id,SourceSupport verdict){
        return new ClaimSourceSupport(id,verdict,List.of("e-"+id),List.of(),"private human reason");
    }
    private Decision decision(ConversationInspectionResult source,List<ClaimSourceSupport> claims){
        return new Decision(source.getId(),"CHECK","frozen-hash",Finding.State.KNOWN,
                fixture.json.getNodeFactory().booleanNode(true),Finding.Severity.LOW,"manual reason",List.of(),List.of(),null,claims);
    }
    private void completed(ConversationInspectionResult source,List<FindingFusion.Result> facts,List<Decision> decisions) {
        var review=new ResultReview();review.setStatus("COMPLETED");
        when(fixture.reviews.latest(source.getId(),true)).thenReturn(review);
        var projection=new Projection(facts,facts,decisions);
        var policy=new PolicyProjection(projection,List.of(),fixture.evaluation().scoring(),null,"LOW",Map.of(),null,"QUALIFIED");
        when(fixture.reviews.validatedProjection(eq(review),eq(source),any())).thenReturn(
                new BusinessItemReviewEvaluator.Projection(source.getId(),fixture.evaluation(),fixture.evaluation(),List.of(),policy));
    }
    private BusinessQualityReportService.ClaimSourceSupportSummary summary(){return fixture.distribution().sourceSupport();}

    @Test void countsRawKnownModelObservationsAndKeepsVerdictsUnannotatedAndPrivacySeparate() throws Exception {
        fixture.fixture(2);
        var first=fact(known("a"),known("b"),known("c"),known("d"),raw("rule","RULE",Finding.State.KNOWN),raw("unknown","LLM",Finding.State.UNKNOWN));
        var source=fixture.source(0,List.of(first));fixture.source(1,List.of(fact(known("a"),known("b"))));
        completed(source,List.of(first),List.of(decision(source,List.of(support("a",SourceSupport.SUPPORTED),
                support("b",SourceSupport.CONTRADICTED),support("c",SourceSupport.INSUFFICIENT_SOURCE)))));
        var report=fixture.report();var summary=report.findingDistributions().values().iterator().next().sourceSupport();
        assertThat(summary.knownClaims()).isEqualTo("6");assertThat(summary.annotatedClaims()).isEqualTo("3");
        assertThat(summary.unannotatedClaims()).isEqualTo("3");assertThat(summary.supportedClaims()).isEqualTo("1");
        assertThat(summary.contradictedClaims()).isEqualTo("1");assertThat(summary.insufficientSourceClaims()).isEqualTo("1");
        assertThat(summary.state()).isEqualTo("PARTIAL");assertThat(summary.annotationCoverage().toPlainString()).isEqualTo("0.500000");
        assertThat(summary.scope()).isEqualTo("KNOWN_RAW_LLM_FINDINGS_IN_SELECTED_RESULT_RUNS");
        assertThat(fixture.json.writeValueAsString(report)).doesNotContain("private model reason","private human reason","private input hash","private message");
    }

    @Test void missingResultsAndNonKnownOrNonModelObservationsDoNotInventAZeroHallucinationRate() throws Exception {
        fixture.fixture(2);fixture.source(0,List.of(fact(raw("rule","RULE",Finding.State.KNOWN),raw("unknown","LLM",Finding.State.UNKNOWN),
                raw("failed","LLM",Finding.State.ERROR),raw("conflict","LLM",Finding.State.CONFLICT),raw("not-run","LLM",Finding.State.NOT_EVALUATED))));
        var summary=summary();assertThat(summary.knownClaims()).isEqualTo("0");assertThat(summary.annotatedClaims()).isEqualTo("0");
        assertThat(summary.state()).isEqualTo("UNMEASURED");assertThat(summary.annotationCoverage()).isNull();
    }

    @Test void knownButUnreviewedClaimsAreExplicitlyUnannotated() throws Exception {
        fixture.fixture(1);fixture.source(0,List.of(fact(known("a"),known("b"))));var summary=summary();
        assertThat(summary.state()).isEqualTo("UNANNOTATED");assertThat(summary.unannotatedClaims()).isEqualTo("2");
        assertThat(summary.annotationCoverage().toPlainString()).isEqualTo("0.000000");
    }

    @Test void pendingLaterRoundKeepsLatestCompletedAssessmentAndCoverageUsesSixDecimalPlaces() throws Exception {
        fixture.fixture(1);var facts=List.of(fact(known("a"),known("b"),known("c")));var source=fixture.source(0,facts);
        completed(source,facts,List.of(decision(source,List.of(support("a",SourceSupport.SUPPORTED)))));
        var pending=new ResultReview();pending.setStatus("PENDING");when(fixture.reviews.latest(source.getId(),false)).thenReturn(pending);
        var summary=summary();assertThat(summary.annotatedClaims()).isEqualTo("1");assertThat(summary.unannotatedClaims()).isEqualTo("2");
        assertThat(summary.annotationCoverage().toPlainString()).isEqualTo("0.333333");assertThat(summary.state()).isEqualTo("PARTIAL");
        verify(fixture.reviews,never()).validatedProjection(eq(pending),any(),any());
    }

    @Test void allAnnotatedKnownClaimsAreCompleteWithoutCombiningContradictionAndInsufficiency() throws Exception {
        fixture.fixture(1);var facts=List.of(fact(known("a"),known("b")));var source=fixture.source(0,facts);
        completed(source,facts,List.of(decision(source,List.of(support("a",SourceSupport.CONTRADICTED),support("b",SourceSupport.INSUFFICIENT_SOURCE)))));
        var summary=summary();assertThat(summary.state()).isEqualTo("COMPLETE");assertThat(summary.supportedClaims()).isEqualTo("0");
        assertThat(summary.contradictedClaims()).isEqualTo("1");assertThat(summary.insufficientSourceClaims()).isEqualTo("1");
        assertThat(summary.unannotatedClaims()).isEqualTo("0");assertThat(summary.annotationCoverage().toPlainString()).isEqualTo("1.000000");
    }

    @Test void duplicateRawModelIdentityIsRejectedRatherThanCountedTwice() throws Exception {
        fixture.fixture(1);fixture.source(0,List.of(fact(known("a"),known("a"))));
        assertThatThrownBy(this::summary).hasMessageContaining("模型观察身份重复");
    }

    @Test void foreignOrRepeatedAssessmentCannotProducePartialStatistics() throws Exception {
        fixture.fixture(1);var facts=List.of(fact(known("a")));var source=fixture.source(0,facts);
        completed(source,facts,List.of(decision(source,List.of(support("foreign",SourceSupport.SUPPORTED)))));
        assertThatThrownBy(this::summary).hasMessageContaining("标注与已验证模型观察不一致");
        var repeated=decision(source,List.of(support("a",SourceSupport.SUPPORTED)));
        completed(source,facts,List.of(repeated,repeated));
        assertThatThrownBy(this::summary).hasMessageContaining("标注与已验证模型观察不一致");
    }
}
