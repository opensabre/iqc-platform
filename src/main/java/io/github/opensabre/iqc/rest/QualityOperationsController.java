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
@RestController @RequestMapping("/api/iqc/quality-operations") @RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class QualityOperationsController {
    private final QualityOperationsService service;
    private final BusinessItemReviewService businessReviews;
    private final BusinessQualityReportService businessReports;
    private final LabelResultReviewService labelReviews;
    private final io.github.opensabre.iqc.quality.BusinessReviewSampleService businessSamples;

    private final io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive;
    private final io.github.opensabre.iqc.privacy.ResultReadProjection resultProjection;

    /** Preserve embedded callers; sensitive response endpoints require the complete authorization wiring. */
    public QualityOperationsController(QualityOperationsService service, BusinessItemReviewService businessReviews,
            BusinessQualityReportService businessReports, LabelResultReviewService labelReviews,
            io.github.opensabre.iqc.quality.BusinessReviewSampleService businessSamples) {
        this(service, businessReviews, businessReports, labelReviews, businessSamples, null, null);
    }

    /** Refresh fixed server-selected grants before operations returning sensitive data. */
    private io.github.opensabre.iqc.privacy.SensitiveReadAccessService.Access readAccess(String resource) {
        if (sensitive == null || resultProjection == null)
            throw IqcException.invalidState("敏感读取授权组件不可用");
        var access = sensitive.current(java.util.Set.of(resource));
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes servlet
                && servlet.getResponse() != null) {
            servlet.getResponse().setHeader("Cache-Control", "private, no-store");
            servlet.getResponse().setHeader("X-Content-Type-Options", "nosniff");
        }
        return access;
    }

    /** Preserves embedded callers that only create legacy message samples. */
    public QualityOperationsController(QualityOperationsService service, BusinessItemReviewService businessReviews,
            BusinessQualityReportService businessReports, LabelResultReviewService labelReviews) {
        this(service, businessReviews, businessReports, labelReviews, null);
    }

    /** Label reviews use the same permission family and audit stream without overloading message/business IDs. */
    @PostMapping("/label-results/{labelResultId}/reviews")
    @ResourcePermission(code="iqc:review:label:create", name="发起标签值复核", type="iqc", description="为冻结标签值发起复核")
    @Audit(operationType=OperationType.CREATE, description="发起 IQC 标签值复核", module="IQC_REVIEW", request=false, response=false, key="#labelResultId")
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
    @Audit(operationType=OperationType.QUERY,description="读取标签复核队列",module="IQC_PRIVACY",request=false,response=false)
    public io.github.opensabre.iqc.privacy.ResultReadProjection.ReviewQueuePage<io.github.opensabre.iqc.label.model.LabelReviewQueueItem> labelReviewQueue(
            @RequestParam(required=false) String status, @RequestParam(required=false) String taskId,
            @RequestParam(defaultValue="1") int current, @RequestParam(defaultValue="20") int size) {
        var access=readAccess("iqc:review:label:queue");
        return resultProjection.labelReviewQueue(labelReviews.queue(status, taskId, current, size),access);
    }

    @PostMapping("/label-reviews/{reviewId}/decision")
    @ResourcePermission(code="iqc:review:label:decide", name="处理标签值复核", type="iqc", description="修正或退回标签值复核")
    @Audit(operationType=OperationType.UPDATE, description="处理 IQC 标签值复核", module="IQC_REVIEW", request=false, response=false, key="#reviewId")
    public ResultReview decideLabelReview(@PathVariable String reviewId, @RequestBody LabelReviewDecision request) {
        if (request == null || request.expectedRevision() == null
                || (!"COMPLETED".equals(request.decision()) && !"REJECTED".equals(request.decision())))
            throw IqcException.invalidArgument("标签复核必须指定修订与完成/退回决定");
        return labelReviews.decide(reviewId, request.expectedRevision(), "REJECTED".equals(request.decision()),
                request.labelDecision(), request.comment());
    }

    @PostMapping("/results/{resultId}/feedback")
    @ResourcePermission(code="iqc:result:feedback", name="标注质检结果", type="iqc", description="标记正确、误判或漏判")
    @Audit(operationType=OperationType.UPDATE, description="标注 IQC 质检结果", module="IQC_RESULT", request=false, response=false, key="#resultId")
    @RateLimit(sceneCode="iqc-result-feedback", maxCount=60, period=60)
    public ResultFeedback feedback(@PathVariable String resultId, @RequestBody FeedbackRequest request) {
        var access = readAccess("iqc:result:feedback");
        return resultProjection.feedback(service.feedback(resultId, request.feedbackType(), request.comment(), request.evidenceJson()), access);
    }
    @GetMapping("/feedback")
    @ResourcePermission(code="iqc:result:view", name="查看质检反馈", type="iqc", description="查询误判漏判反馈")
    @Audit(operationType=OperationType.QUERY, description="查询 IQC 质检反馈", module="IQC_RESULT", request=false, response=false)
    public List<ResultFeedback> feedbacks(@RequestParam(required=false) String resultId, @RequestParam(required=false) String type) {
        var access = readAccess("iqc:result:view");
        return service.feedbacks(resultId, type).stream().map(row -> resultProjection.feedback(row, access)).toList();
    }

    @PostMapping("/results/{resultId}/reviews")
    @ResourcePermission(code="iqc:review:create", name="发起人工复核", type="iqc", description="为质检结果发起复核")
    @Audit(operationType=OperationType.CREATE, description="发起 IQC 人工复核", module="IQC_REVIEW", request=false, response=false, key="#resultId")
    public ResultReview requestReview(@PathVariable String resultId, @RequestBody(required=false) ReviewRequest request) {
        if (request != null && business(request.targetType())) {
            if (request.expectedRevision() == null) throw IqcException.invalidArgument("业务复核必须指定预期修订");
            if(request.stage()!=null||request.parentReviewId()!=null)return businessReviews.request(resultId,request.expectedRevision(),request.requestId(),request.comment(),request.stage(),request.parentReviewId());
            return businessReviews.request(resultId, request.expectedRevision(), request.requestId(), request.comment());
        }
        if(request!=null&&(request.stage()!=null||request.parentReviewId()!=null))throw IqcException.invalidArgument("消息复核不接受业务阶段升级");
        return service.requestReview(resultId, request == null ? null : request.comment());
    }
    @GetMapping("/reviews")
    @ResourcePermission(code="iqc:review:view", name="查看人工复核", type="iqc", description="查询人工复核工作台")
    @Audit(operationType=OperationType.QUERY,description="读取人工复核视图",module="IQC_PRIVACY",request=false,response=false,key="#resultId")
    public Object reviews(@RequestParam(required=false) String status,
                                      @RequestParam(defaultValue="MESSAGE") String targetType,
                                      @RequestParam(required=false) String resultId,
                                      @RequestParam(defaultValue="HISTORY") String view,
                                      @RequestParam(required=false) String taskId,
                                      @RequestParam(defaultValue="1") int current,
                                      @RequestParam(defaultValue="20") int size) {
        var access=readAccess("iqc:review:view");
        if("SCORE_CONTEXT".equals(view)){
            if(!business(targetType)||resultId==null||resultId.isBlank()||status!=null||taskId!=null)
                throw IqcException.invalidArgument("评分复核上下文必须指定单一业务结果");
            return businessReviews.scoreContext(resultId);
        }
        if("FINDING_CONTEXT".equals(view)){
            if(!business(targetType)||resultId==null||resultId.isBlank()||status!=null||taskId!=null)
                throw IqcException.invalidArgument("事实复核上下文必须指定单一业务结果");
            return resultProjection.findingReviewContext(businessReviews.findingContext(resultId),access);
        }
        if ("QUEUE".equals(view)) {
            if (!business(targetType) || resultId != null) throw IqcException.invalidArgument("待办视图只支持业务复核，不能指定单结果历史");
            return resultProjection.businessReviewQueue(businessReviews.queue(status, taskId, current, size),access);
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
    @Audit(operationType=OperationType.UPDATE, description="处理 IQC 人工复核", module="IQC_REVIEW", request=false, response=false, key="#reviewId")
    public ResultReview decide(@PathVariable String reviewId, @RequestBody ReviewDecision request) {
        if (business(request.targetType())) {
            if (request.expectedRevision() == null || (!"COMPLETED".equals(request.decision()) && !"REJECTED".equals(request.decision())))
                throw IqcException.invalidArgument("业务复核必须指定修订与完成/退回决定");
            if (request.finalScore() != null || request.finalStatus() != null || request.finalRiskLevel() != null)
                throw IqcException.invalidArgument("业务复核不能手工覆盖总分、整体状态或风险");
            if(request.scoreAdjustment()!=null)return businessReviews.decide(reviewId,request.expectedRevision(),"REJECTED".equals(request.decision()),
                    request.items(),request.findingDecisions(),request.scoreAdjustment(),request.comment());
            return businessReviews.decide(reviewId, request.expectedRevision(), "REJECTED".equals(request.decision()), request.items(),request.findingDecisions(),request.comment());
        }
        if(request.scoreAdjustment()!=null)throw IqcException.invalidArgument("消息复核不接受业务人工改分命令");
        if(request.findingDecisions()!=null&&!request.findingDecisions().isEmpty())throw IqcException.invalidArgument("消息复核不接受Finding裁决");
        return service.decideReview(reviewId, request.decision(), request.finalStatus(), request.finalScore(), request.finalRiskLevel(), request.comment());
    }

    @PostMapping("/results/{resultId}/samples")
    @ResourcePermission(code="iqc:sample:manage", name="管理质检样本", type="iqc", description="从质检结果沉淀样本")
    @Audit(operationType=OperationType.CREATE, description="创建 IQC 质检样本", module="IQC_SAMPLE", request=false, response=false, key="#resultId")
    public QualitySample createSample(@PathVariable String resultId, @RequestBody SampleRequest request) {
        var access = readAccess("iqc:sample:manage");
        if (request == null) throw IqcException.invalidArgument("样本请求不能为空");
        if (business(request.targetType())) {
            if (request.expectedJson() != null) throw IqcException.invalidArgument("业务候选的期望结果必须来自已完成复核");
            if (businessSamples == null) throw IqcException.invalidState("会话候选服务未配置");
            return resultProjection.sampleMetadata(businessSamples.create(resultId, request.reviewId(), request.requestId(), request.name(), request.sampleType(), request.tagsJson()), access);
        }
        if (request.reviewId() != null || request.requestId() != null) throw IqcException.invalidArgument("消息样本不接受业务复核参数");
        return resultProjection.sampleMetadata(service.createSample(resultId, request.name(), request.sampleType(), request.expectedJson(), request.tagsJson()), access);
    }
    @GetMapping("/samples")
    @ResourcePermission(code="iqc:sample:view", name="查看质检样本", type="iqc", description="查询质检样本库")
    @Audit(operationType=OperationType.QUERY, description="读取 IQC 样本列表", module="IQC_PRIVACY", request=false, response=false)
    public List<QualitySample> samples(@RequestParam(required=false) String type, @RequestParam(required=false) String status) {
        var access = readAccess("iqc:sample:view");
        return resultProjection.samples(service.samples(type, status), access);
    }

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
    public record ReviewRequest(String comment,String targetType,Integer expectedRevision,String requestId,
            BusinessItemReviewEvaluator.ReviewStage stage,String parentReviewId){
        public ReviewRequest(String comment,String targetType,Integer expectedRevision,String requestId){this(comment,targetType,expectedRevision,requestId,null,null);}
    }
    public record ReviewDecision(String decision, String finalStatus, Integer finalScore, String finalRiskLevel, String comment,
                                 String targetType, Integer expectedRevision, List<BusinessItemReviewEvaluator.Decision> items,
                                 List<io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.Decision> findingDecisions,
                                 BusinessItemReviewEvaluator.ScoreAdjustment scoreAdjustment) {
        public ReviewDecision(String decision,String finalStatus,Integer finalScore,String finalRiskLevel,String comment,
                String targetType,Integer expectedRevision,List<BusinessItemReviewEvaluator.Decision> items,
                List<io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.Decision> findingDecisions){
            this(decision,finalStatus,finalScore,finalRiskLevel,comment,targetType,expectedRevision,items,findingDecisions,null);
        }
        public ReviewDecision(String decision,String finalStatus,Integer finalScore,String finalRiskLevel,String comment,
                String targetType,Integer expectedRevision,List<BusinessItemReviewEvaluator.Decision> items){
            this(decision,finalStatus,finalScore,finalRiskLevel,comment,targetType,expectedRevision,items,null,null);
        }
    }
    public record LabelReviewRequest(String comment, Integer expectedRevision, String requestId) { }
    public record LabelReviewDecision(String decision, Integer expectedRevision, LabelResultReviewService.Decision labelDecision,
                                      String comment) { }
    private boolean business(String targetType) {
        if (targetType == null || "MESSAGE".equals(targetType)) return false;
        if ("BUSINESS".equals(targetType)) return true;
        throw IqcException.invalidArgument("不支持的复核目标类型");
    }
    public record SampleRequest(String name, String sampleType, String expectedJson, String tagsJson,
                                String targetType, String reviewId, String requestId) {
        public SampleRequest(String name, String sampleType, String expectedJson, String tagsJson) {
            this(name, sampleType, expectedJson, tagsJson, null, null, null);
        }
    }
}
