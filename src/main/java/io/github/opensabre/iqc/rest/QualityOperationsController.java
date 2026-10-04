package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.governance.ratelimit.annotations.RateLimit;
import io.github.opensabre.iqc.quality.QualityOperationsService;
import io.github.opensabre.iqc.quality.BusinessItemReviewService;
import io.github.opensabre.iqc.quality.BusinessQualityReportService;
import io.github.opensabre.iqc.quality.BusinessItemReviewEvaluator;
import io.github.opensabre.iqc.label.LabelResultReviewService;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.quality.model.QualitySample;
import io.github.opensabre.iqc.quality.model.ResultFeedback;
import io.github.opensabre.iqc.quality.model.ResultReview;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Quality operations APIs for result feedback, human review and sample governance. */
@RestController @RequestMapping("/api/iqc/quality-operations") @RequiredArgsConstructor
public class QualityOperationsController {
    private final QualityOperationsService service;
    private final BusinessItemReviewService businessReviews;
    private final BusinessQualityReportService businessReports;
    private final LabelResultReviewService labelReviews;

    /** Label reviews use the same permission family and audit stream without overloading message/business IDs. */
    @PostMapping("/label-results/{labelResultId}/reviews")
    @ResourcePermission(code="iqc:review:label:create", name="发起标签值复核", type="iqc", description="为冻结标签值发起复核")
    @Audit(operationType=OperationType.CREATE, description="发起 IQC 标签值复核", module="IQC_REVIEW")
    public ResultReview requestLabelReview(@PathVariable String labelResultId, @RequestBody LabelReviewRequest request) {
        if (request == null || request.expectedRevision() == null)
            throw IqcException.invalidArgument("标签复核必须指定预期修订");
        return labelReviews.request(labelResultId, request.expectedRevision(), request.requestId(), request.comment());
    }

    @GetMapping("/label-results/{labelResultId}/reviews")
    @ResourcePermission(code="iqc:review:label:history", name="查看标签值复核", type="iqc", description="查看标签值复核轮次")
    public List<ResultReview> labelReviewHistory(@PathVariable String labelResultId) {
        return labelReviews.history(labelResultId);
    }

    /** Scoped, paginated label rounds for the quality operator's worklist. */
    @GetMapping("/label-reviews")
    @ResourcePermission(code="iqc:review:label:queue", name="查看标签值复核待办", type="iqc", description="按任务范围查询标签复核轮次")
    public io.github.opensabre.iqc.shared.IqcPage<io.github.opensabre.iqc.label.model.LabelReviewQueueItem> labelReviewQueue(
            @RequestParam(required=false) String status, @RequestParam(required=false) String taskId,
            @RequestParam(defaultValue="1") int current, @RequestParam(defaultValue="20") int size) {
        return labelReviews.queue(status, taskId, current, size);
    }

    @PostMapping("/label-reviews/{reviewId}/decision")
    @ResourcePermission(code="iqc:review:label:decide", name="处理标签值复核", type="iqc", description="修正或退回标签值复核")
    @Audit(operationType=OperationType.UPDATE, description="处理 IQC 标签值复核", module="IQC_REVIEW")
    public ResultReview decideLabelReview(@PathVariable String reviewId, @RequestBody LabelReviewDecision request) {
        if (request == null || request.expectedRevision() == null
                || (!"COMPLETED".equals(request.decision()) && !"REJECTED".equals(request.decision())))
            throw IqcException.invalidArgument("标签复核必须指定修订与完成/退回决定");
        return labelReviews.decide(reviewId, request.expectedRevision(), "REJECTED".equals(request.decision()),
                request.labelDecision(), request.comment());
    }

    @PostMapping("/results/{resultId}/feedback")
    @ResourcePermission(code="iqc:result:feedback", name="标注质检结果", type="iqc", description="标记正确、误判或漏判")
    @Audit(operationType=OperationType.UPDATE, description="标注 IQC 质检结果", module="IQC_RESULT")
    @RateLimit(sceneCode="iqc-result-feedback", maxCount=60, period=60)
    public ResultFeedback feedback(@PathVariable String resultId, @RequestBody FeedbackRequest request) {
        return service.feedback(resultId, request.feedbackType(), request.comment(), request.evidenceJson());
    }
    @GetMapping("/feedback")
    @ResourcePermission(code="iqc:result:view", name="查看质检反馈", type="iqc", description="查询误判漏判反馈")
    public List<ResultFeedback> feedbacks(@RequestParam(required=false) String resultId, @RequestParam(required=false) String type) { return service.feedbacks(resultId, type); }

    @PostMapping("/results/{resultId}/reviews")
    @ResourcePermission(code="iqc:review:create", name="发起人工复核", type="iqc", description="为质检结果发起复核")
    @Audit(operationType=OperationType.CREATE, description="发起 IQC 人工复核", module="IQC_REVIEW")
    public ResultReview requestReview(@PathVariable String resultId, @RequestBody(required=false) ReviewRequest request) {
        if (request != null && business(request.targetType())) {
            if (request.expectedRevision() == null) throw IqcException.invalidArgument("业务复核必须指定预期修订");
            return businessReviews.request(resultId, request.expectedRevision(), request.requestId(), request.comment());
        }
        return service.requestReview(resultId, request == null ? null : request.comment());
    }
    @GetMapping("/reviews")
    @ResourcePermission(code="iqc:review:view", name="查看人工复核", type="iqc", description="查询人工复核工作台")
    public Object reviews(@RequestParam(required=false) String status,
                                      @RequestParam(defaultValue="MESSAGE") String targetType,
                                      @RequestParam(required=false) String resultId,
                                      @RequestParam(defaultValue="HISTORY") String view,
                                      @RequestParam(required=false) String taskId,
                                      @RequestParam(defaultValue="1") int current,
                                      @RequestParam(defaultValue="20") int size) {
        if ("QUEUE".equals(view)) {
            if (!business(targetType) || resultId != null) throw IqcException.invalidArgument("待办视图只支持业务复核，不能指定单结果历史");
            return businessReviews.queue(status, taskId, current, size);
        }
        if (!"HISTORY".equals(view) || taskId != null) throw IqcException.invalidArgument("不支持的复核视图或筛选");
        if (business(targetType)) {
            if (resultId == null || resultId.isBlank()) throw IqcException.invalidArgument("业务复核历史必须指定会话结果");
            if (status != null) throw IqcException.invalidArgument("业务复核历史按完整轮次返回，不支持状态筛选");
            return businessReviews.history(resultId);
        }
        return service.reviews(status);
    }
    @PostMapping("/reviews/{reviewId}/decision")
    @ResourcePermission(code="iqc:review:decide", name="处理人工复核", type="iqc", description="通过、修正或退回复核")
    @Audit(operationType=OperationType.UPDATE, description="处理 IQC 人工复核", module="IQC_REVIEW")
    public ResultReview decide(@PathVariable String reviewId, @RequestBody ReviewDecision request) {
        if (business(request.targetType())) {
            if (request.expectedRevision() == null || (!"COMPLETED".equals(request.decision()) && !"REJECTED".equals(request.decision())))
                throw IqcException.invalidArgument("业务复核必须指定修订与完成/退回决定");
            if (request.finalScore() != null || request.finalStatus() != null || request.finalRiskLevel() != null)
                throw IqcException.invalidArgument("业务复核不能手工覆盖总分、整体状态或风险");
            return businessReviews.decide(reviewId, request.expectedRevision(), "REJECTED".equals(request.decision()), request.items(), request.comment());
        }
        return service.decideReview(reviewId, request.decision(), request.finalStatus(), request.finalScore(), request.finalRiskLevel(), request.comment());
    }

    @PostMapping("/results/{resultId}/samples")
    @ResourcePermission(code="iqc:sample:manage", name="管理质检样本", type="iqc", description="从质检结果沉淀样本")
    @Audit(operationType=OperationType.CREATE, description="创建 IQC 质检样本", module="IQC_SAMPLE")
    public QualitySample createSample(@PathVariable String resultId, @RequestBody SampleRequest request) {
        return service.createSample(resultId, request.name(), request.sampleType(), request.expectedJson(), request.tagsJson());
    }
    @GetMapping("/samples")
    @ResourcePermission(code="iqc:sample:view", name="查看质检样本", type="iqc", description="查询质检样本库")
    public List<QualitySample> samples(@RequestParam(required=false) String type, @RequestParam(required=false) String status) { return service.samples(type, status); }

    @GetMapping("/report")
    @ResourcePermission(code="iqc:report:view", name="查看质检报表", type="iqc", description="查看基于人工复核的质量报表")
    @RateLimit(sceneCode="iqc-quality-report", maxCount=30, period=60)
    public Object report(@RequestParam(defaultValue="MESSAGE") String view,
                         @RequestParam(required=false) List<String> taskIds,
                         @RequestParam(required=false) String executionSelections,
                         @RequestParam(defaultValue="SELECTED_RUNS") String runPolicy) {
        if ("BUSINESS".equals(view)) return businessReports.report(taskIds, executionSelections, runPolicy);
        if (!"MESSAGE".equals(view) || taskIds != null || executionSelections != null
                || !"SELECTED_RUNS".equals(runPolicy)) throw IqcException.invalidArgument("不支持的统计视图或筛选");
        return service.report();
    }

    /** Retains the direct-call signature used by existing controller callers and tests. */
    public Object report(String view, List<String> taskIds) {
        return report(view, taskIds, null, "SELECTED_RUNS");
    }

    public record FeedbackRequest(String feedbackType, String comment, String evidenceJson) { }
    /** Missing targetType preserves the existing message-review HTTP contract. */
    public record ReviewRequest(String comment, String targetType, Integer expectedRevision, String requestId) { }
    public record ReviewDecision(String decision, String finalStatus, Integer finalScore, String finalRiskLevel, String comment,
                                 String targetType, Integer expectedRevision, List<BusinessItemReviewEvaluator.Decision> items) { }
    public record LabelReviewRequest(String comment, Integer expectedRevision, String requestId) { }
    public record LabelReviewDecision(String decision, Integer expectedRevision, LabelResultReviewService.Decision labelDecision,
                                      String comment) { }
    private boolean business(String targetType) {
        if (targetType == null || "MESSAGE".equals(targetType)) return false;
        if ("BUSINESS".equals(targetType)) return true;
        throw IqcException.invalidArgument("不支持的复核目标类型");
    }
    public record SampleRequest(String name, String sampleType, String expectedJson, String tagsJson) { }
}
