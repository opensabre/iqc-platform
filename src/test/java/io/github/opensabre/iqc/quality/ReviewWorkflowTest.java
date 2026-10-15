package io.github.opensabre.iqc.quality;

import io.github.opensabre.iqc.quality.model.ResultReview;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static io.github.opensabre.iqc.quality.BusinessItemReviewEvaluator.*;
import static org.assertj.core.api.Assertions.*;

/** Staged adjudication binds immutable upstream decisions and distinct reviewers. */
class ReviewWorkflowTest {
    private ResultReview parent() {
        var value = new ResultReview();
        value.setId("initial"); value.setTargetType("BUSINESS"); value.setBusinessResultId("result");
        value.setSourceHash("source"); value.setStatus("COMPLETED"); value.setReviewRevision(1);
        value.setReviewerId("first"); value.setReviewedTime(LocalDateTime.now());
        value.setReviewedResultJson("{\"score\":90.25}");
        return value;
    }
    @Test void escalationBindsParentIdentityRevisionAndExactDecisionHash() {
        var parent = parent();
        var second = escalation("result", "source", ReviewStage.SECOND_REVIEW, parent, null);
        assertThat(second.parentReviewId()).isEqualTo("initial");
        assertThat(second.parentRevision()).isEqualTo(1);
        assertThat(second.parentDecisionHash()).isEqualTo(SelectorAssetService.hash(parent.getReviewedResultJson()));
        parent.setReviewedResultJson("{\"score\":90.26}");
        assertThat(escalation("result", "source", ReviewStage.SECOND_REVIEW, parent, null)).isNotEqualTo(second);
        parent.setId("second"); parent.setReviewRevision(2);
        assertThat(escalation("result", "source", ReviewStage.ARBITRATION, parent, second).stage()).isEqualTo(ReviewStage.ARBITRATION);
    }
    @Test void rejectsCrossSourceIncompleteAndSkippedStages() {
        var parent = parent();
        assertThatThrownBy(() -> escalation("other", "source", ReviewStage.SECOND_REVIEW, parent, null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> escalation("result", "changed", ReviewStage.SECOND_REVIEW, parent, null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> escalation("result", "source", ReviewStage.ARBITRATION, parent, null)).hasMessageContaining("跳级");
        parent.setStatus("PENDING");
        assertThatThrownBy(() -> escalation("result", "source", ReviewStage.SECOND_REVIEW, parent, null)).hasMessageContaining("已完成裁决无效");
    }
    @Test void independentReviewerCannotBeRequesterOrAnyUpstreamReviewer() {
        var second = escalation("result", "source", ReviewStage.SECOND_REVIEW, parent(), null);
        assertThatCode(() -> requireIndependentReviewer(second, "second", "applicant", List.of("first"))).doesNotThrowAnyException();
        for (var actor : List.of("first", "applicant", "system", ""))
            assertThatThrownBy(() -> requireIndependentReviewer(second, actor, "applicant", List.of("first"))).isInstanceOf(RuntimeException.class);
        var arbitration = new ReviewWorkflow(ReviewWorkflow.SCHEMA, ReviewStage.ARBITRATION, "second", 2, "a".repeat(64));
        assertThatCode(() -> requireIndependentReviewer(arbitration, "arbiter", "applicant", List.of("first", "second"))).doesNotThrowAnyException();
        for (var actor : List.of("first", "second", "applicant"))
            assertThatThrownBy(() -> requireIndependentReviewer(arbitration, actor, "applicant", List.of("first", "second"))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> requireIndependentReviewer(arbitration, "arbiter", "applicant", List.of("first", "first"))).isInstanceOf(RuntimeException.class);
    }
    @Test void completedArbitrationIsFinalAndWorkflowRequiresCompleteBinding() {
        assertThatCode(() -> requireReviewNotFinal(null)).doesNotThrowAnyException();
        var second = escalation("result", "source", ReviewStage.SECOND_REVIEW, parent(), null);
        assertThatCode(() -> requireReviewNotFinal(second)).doesNotThrowAnyException();
        var arbitration = new ReviewWorkflow(ReviewWorkflow.SCHEMA, ReviewStage.ARBITRATION, "second", 2, "a".repeat(64));
        assertThatThrownBy(() -> requireReviewNotFinal(arbitration)).hasMessageContaining("终局");
        assertThatThrownBy(() -> new ReviewWorkflow(ReviewWorkflow.SCHEMA, ReviewStage.INITIAL, "parent", 1, "a".repeat(64))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new ReviewWorkflow(ReviewWorkflow.SCHEMA, ReviewStage.SECOND_REVIEW, "parent", 0, "a".repeat(64))).isInstanceOf(RuntimeException.class);
    }
}
