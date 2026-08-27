package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.governance.ratelimit.annotations.RateLimit;
import io.github.opensabre.iqc.quality.QualityOperationsService;
import io.github.opensabre.iqc.quality.model.QualitySample;
import io.github.opensabre.iqc.quality.model.ResultFeedback;
import io.github.opensabre.iqc.quality.model.ResultReview;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Quality operations APIs for result feedback, human review and sample governance. */
@RestController @RequestMapping("/api/iqc/quality-operations") @RequiredArgsConstructor
public class QualityOperationsController {
    private final QualityOperationsService service;

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
    public ResultReview requestReview(@PathVariable String resultId, @RequestBody(required=false) ReviewRequest request) { return service.requestReview(resultId, request == null ? null : request.comment()); }
    @GetMapping("/reviews")
    @ResourcePermission(code="iqc:review:view", name="查看人工复核", type="iqc", description="查询人工复核工作台")
    public List<ResultReview> reviews(@RequestParam(required=false) String status) { return service.reviews(status); }
    @PostMapping("/reviews/{reviewId}/decision")
    @ResourcePermission(code="iqc:review:decide", name="处理人工复核", type="iqc", description="通过、修正或退回复核")
    @Audit(operationType=OperationType.UPDATE, description="处理 IQC 人工复核", module="IQC_REVIEW")
    public ResultReview decide(@PathVariable String reviewId, @RequestBody ReviewDecision request) {
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
    @Audit(operationType=OperationType.QUERY, description="查询 IQC 质量运营报表", module="IQC_QUALITY_OPERATIONS")
    public Map<String, Object> report() { return service.report(); }

    public record FeedbackRequest(String feedbackType, String comment, String evidenceJson) { }
    public record ReviewRequest(String comment) { }
    public record ReviewDecision(String decision, String finalStatus, Integer finalScore, String finalRiskLevel, String comment) { }
    public record SampleRequest(String name, String sampleType, String expectedJson, String tagsJson) { }
}
