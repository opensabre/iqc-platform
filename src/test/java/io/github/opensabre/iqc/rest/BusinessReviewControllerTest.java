package io.github.opensabre.iqc.rest;

import io.github.opensabre.iqc.quality.BusinessItemReviewService;
import io.github.opensabre.iqc.quality.BusinessQualityReportService;
import io.github.opensabre.iqc.quality.QualityOperationsService;
import io.github.opensabre.iqc.label.LabelResultReviewService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessReviewControllerTest {
    private final QualityOperationsService legacy = mock(QualityOperationsService.class);
    private final BusinessItemReviewService business = mock(BusinessItemReviewService.class);
    private final BusinessQualityReportService reports = mock(BusinessQualityReportService.class);
    private final LabelResultReviewService labels = mock(LabelResultReviewService.class);
    private final QualityOperationsController controller = new QualityOperationsController(legacy, business, reports, labels);

    @Test
    void labelReviewUsesDedicatedResultIdentityWithoutChangingLegacyContract() {
        controller.requestLabelReview("label", new QualityOperationsController.LabelReviewRequest("纠错", 0, "request-token-1234"));
        verify(labels).request("label", 0, "request-token-1234", "纠错");
        controller.labelReviewHistory("label"); verify(labels).history("label");
        var decision = new LabelResultReviewService.Decision("KNOWN", new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(false), List.of("m1"));
        controller.decideLabelReview("review", new QualityOperationsController.LabelReviewDecision("COMPLETED", 1, decision, "确认否认"));
        verify(labels).decide("review", 1, false, decision, "确认否认");
        assertThatThrownBy(() -> controller.requestLabelReview("label", new QualityOperationsController.LabelReviewRequest("原因", null, "key")))
                .hasMessageContaining("预期修订");
        verifyNoInteractions(legacy, business);
    }

    @Test
    void labelReviewWorklistUsesReviewViewPermissionAndDedicatedQueue() throws Exception {
        controller.labelReviewQueue("PENDING", "task", 2, 10);
        verify(labels).queue("PENDING", "task", 2, 10);
        var method = QualityOperationsController.class.getMethod("labelReviewQueue", String.class, String.class, int.class, int.class);
        assertThat(method.getAnnotation(io.github.opensabre.boot.annotations.ResourcePermission.class).code())
                .isEqualTo("iqc:review:label:queue");
        verifyNoInteractions(legacy, business);
    }

    @Test
    void labelReviewEndpointsHaveDistinctResourceIdentitiesForGatewayRegistration() throws Exception {
        var methods = List.of(
                QualityOperationsController.class.getMethod("requestLabelReview", String.class, QualityOperationsController.LabelReviewRequest.class),
                QualityOperationsController.class.getMethod("labelReviewHistory", String.class),
                QualityOperationsController.class.getMethod("labelReviewQueue", String.class, String.class, int.class, int.class),
                QualityOperationsController.class.getMethod("decideLabelReview", String.class, QualityOperationsController.LabelReviewDecision.class));
        var codes = methods.stream().map(method -> method.getAnnotation(io.github.opensabre.boot.annotations.ResourcePermission.class).code()).toList();
        assertThat(codes).containsExactly("iqc:review:label:create", "iqc:review:label:history",
                "iqc:review:label:queue", "iqc:review:label:decide");
        assertThat(codes).doesNotHaveDuplicates().doesNotContain("iqc:review:create", "iqc:review:view", "iqc:review:decide");
        var reviewCodes = java.util.Arrays.stream(QualityOperationsController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(io.github.opensabre.boot.annotations.ResourcePermission.class))
                .filter(java.util.Objects::nonNull).map(io.github.opensabre.boot.annotations.ResourcePermission::code)
                .filter(code -> code.startsWith("iqc:review:")).toList();
        assertThat(reviewCodes).hasSize(7).doesNotHaveDuplicates();
    }

    @Test
    void reportsKeepLegacyDefaultAndRequireExplicitBusinessView() {
        controller.report("MESSAGE", null);
        verify(legacy).report();
        controller.report("BUSINESS", List.of("task"));
        verify(reports).report(List.of("task"), null, "SELECTED_RUNS");
        assertThatThrownBy(() -> controller.report("MESSAGE", List.of("task"))).hasMessageContaining("统计视图");
        assertThatThrownBy(() -> controller.report("UNKNOWN", null)).hasMessageContaining("统计视图");
        verifyNoMoreInteractions(legacy, reports);
    }

    @Test
    void routesBusinessOperationsThroughExistingReviewEndpoints() {
        controller.requestReview("result", new QualityOperationsController.ReviewRequest("申请", "BUSINESS", 0, "request-token-1234"));
        verify(business).request("result", 0, "request-token-1234", "申请");
        controller.reviews(null, "BUSINESS", "result", "HISTORY", null, 1, 20); verify(business).history("result");
        controller.decide("review", new QualityOperationsController.ReviewDecision("REJECTED", null, null, null, "退回", "BUSINESS", 1, List.of()));
        verify(business).decide("review", 1, true, List.of(), "退回");
        verifyNoInteractions(legacy);
    }

    @Test
    void omittedTargetPreservesMessageReviewContract() {
        controller.requestReview("message", null); verify(legacy).requestReview("message", null);
        controller.reviews("PENDING", "MESSAGE", null, "HISTORY", null, 1, 20); verify(legacy).reviews("PENDING");
        controller.decide("review", new QualityOperationsController.ReviewDecision("CORRECTED", "HIT", 80, "LOW", "修正", null, null, null));
        verify(legacy).decideReview("review", "CORRECTED", "HIT", 80, "LOW", "修正");
        verifyNoInteractions(business);
    }

    @Test
    void rejectsManualScoresUnknownTargetsAndIncompleteSelectors() {
        assertThatThrownBy(() -> controller.decide("review", new QualityOperationsController.ReviewDecision("COMPLETED", null, 100, null, "原因", "BUSINESS", 1, List.of())))
                .hasMessageContaining("不能手工覆盖");
        assertThatThrownBy(() -> controller.decide("review", new QualityOperationsController.ReviewDecision(null, null, null, null, "原因", "BUSINESS", 1, List.of())))
                .hasMessageContaining("完成/退回");
        assertThatThrownBy(() -> controller.requestReview("r", new QualityOperationsController.ReviewRequest("原因", "UNKNOWN", 0, "key")))
                .hasMessageContaining("目标类型");
        assertThatThrownBy(() -> controller.requestReview("r", new QualityOperationsController.ReviewRequest("原因", "BUSINESS", null, "key")))
                .hasMessageContaining("预期修订");
        assertThatThrownBy(() -> controller.reviews(null, "BUSINESS", null, "HISTORY", null, 1, 20)).hasMessageContaining("指定会话结果");
        assertThatThrownBy(() -> controller.reviews("PENDING", "BUSINESS", "r", "HISTORY", null, 1, 20)).hasMessageContaining("不支持状态筛选");
        verifyNoInteractions(business, legacy);
    }

    @Test
    void queueIsAnExplicitPaginatedBusinessViewWithoutBreakingHistory() {
        controller.reviews("PENDING", "BUSINESS", null, "QUEUE", "task", 2, 10);
        verify(business).queue("PENDING", "task", 2, 10);
        assertThatThrownBy(() -> controller.reviews(null, "MESSAGE", null, "QUEUE", null, 1, 20)).hasMessageContaining("只支持业务");
        assertThatThrownBy(() -> controller.reviews(null, "BUSINESS", "result", "QUEUE", null, 1, 20)).hasMessageContaining("不能指定");
    }
}
