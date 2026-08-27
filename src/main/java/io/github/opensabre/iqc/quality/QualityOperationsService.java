package io.github.opensabre.iqc.quality;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.quality.dao.QualitySampleMapper;
import io.github.opensabre.iqc.quality.dao.ResultFeedbackMapper;
import io.github.opensabre.iqc.quality.dao.ResultReviewMapper;
import io.github.opensabre.iqc.quality.model.QualitySample;
import io.github.opensabre.iqc.quality.model.ResultFeedback;
import io.github.opensabre.iqc.quality.model.ResultReview;
import io.github.opensabre.iqc.result.dao.InspectionResultMapper;
import io.github.opensabre.iqc.result.model.InspectionResult;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Implements the append-only feedback, human review and governed sample workflow. */
@Service
@RequiredArgsConstructor
public class QualityOperationsService {
    private static final Set<String> FEEDBACK_TYPES = Set.of("CORRECT", "FALSE_POSITIVE", "FALSE_NEGATIVE");
    private static final Set<String> REVIEW_DECISIONS = Set.of("APPROVED", "CORRECTED", "REJECTED");
    private static final Set<String> RESULT_STATUSES = Set.of("HIT", "NOT_HIT", "ERROR", "PARTIAL_ERROR");
    private static final Set<String> RISK_LEVELS = Set.of("LOW", "MEDIUM", "HIGH");
    private final ResultFeedbackMapper feedbackMapper;
    private final ResultReviewMapper reviewMapper;
    private final QualitySampleMapper sampleMapper;
    private final InspectionResultMapper resultMapper;
    private final InspectionTaskMapper taskMapper;
    private final ConversationMessageMapper messageMapper;
    private final IqcDataScope dataScope;

    /** Records a user assertion without mutating the original AI result. */
    @Transactional
    public ResultFeedback feedback(String resultId, String type, String comment, String evidenceJson) {
        ResultContext context = requireResult(resultId);
        String normalized = upper(type);
        if (!FEEDBACK_TYPES.contains(normalized)) throw IqcException.invalidArgument("反馈类型必须是 CORRECT、FALSE_POSITIVE 或 FALSE_NEGATIVE");
        ResultFeedback feedback = new ResultFeedback();
        feedback.setResultId(resultId); feedback.setFeedbackType(normalized); feedback.setComment(trim(comment, 1000));
        feedback.setEvidenceJson(evidenceJson); feedback.setStatus("OPEN"); feedback.setOwnerGroupId(context.task().getOwnerGroupId());
        feedbackMapper.insert(feedback);
        return feedback;
    }

    public List<ResultFeedback> feedbacks(String resultId, String type) {
        if (resultId != null && !resultId.isBlank()) requireResult(resultId);
        var query = Wrappers.<ResultFeedback>lambdaQuery().eq(resultId != null && !resultId.isBlank(), ResultFeedback::getResultId, resultId)
                .eq(type != null && !type.isBlank(), ResultFeedback::getFeedbackType, upper(type)).orderByDesc(ResultFeedback::getCreatedTime);
        if (!dataScope.canViewAll()) query.and(q -> q.eq(ResultFeedback::getCreatedBy, dataScope.owner())
                .or(dataScope.groupId() != null, nested -> nested.eq(ResultFeedback::getOwnerGroupId, dataScope.groupId())));
        return feedbackMapper.selectList(query);
    }

    /** Opens one idempotent review per result. */
    @Transactional
    public ResultReview requestReview(String resultId, String comment) {
        ResultContext context = requireResult(resultId);
        ResultReview existing = reviewMapper.selectOne(Wrappers.<ResultReview>lambdaQuery().eq(ResultReview::getResultId, resultId));
        if (existing != null) return existing;
        InspectionResult result = context.result();
        ResultReview review = new ResultReview(); review.setResultId(resultId); review.setStatus("PENDING");
        review.setOriginalStatus(result.getResultStatus()); review.setOriginalScore(result.getScore()); review.setOriginalRiskLevel(result.getRiskLevel());
        review.setReviewComment(trim(comment, 1000)); review.setOwnerGroupId(context.task().getOwnerGroupId()); reviewMapper.insert(review);
        return review;
    }

    /** Completes a review while preserving the original fields for audit and comparison. */
    @Transactional
    public ResultReview decideReview(String reviewId, String decision, String finalStatus, Integer finalScore,
                                     String finalRiskLevel, String comment) {
        ResultReview review = reviewMapper.selectById(reviewId);
        if (review == null) throw IqcException.notFound("复核记录不存在: " + reviewId);
        requireResult(review.getResultId());
        String normalized = upper(decision);
        if (!REVIEW_DECISIONS.contains(normalized)) throw IqcException.invalidArgument("复核决定无效");
        if ("CORRECTED".equals(normalized)) {
            if (!RESULT_STATUSES.contains(upper(finalStatus))) throw IqcException.invalidArgument("修正后的结果状态无效");
            if (finalScore == null || finalScore < 0 || finalScore > 100) throw IqcException.invalidArgument("修正分数必须在 0 到 100 之间");
            if (!RISK_LEVELS.contains(upper(finalRiskLevel))) throw IqcException.invalidArgument("修正风险等级无效");
            review.setFinalStatus(upper(finalStatus)); review.setFinalScore(finalScore); review.setFinalRiskLevel(upper(finalRiskLevel));
        } else {
            review.setFinalStatus(review.getOriginalStatus()); review.setFinalScore(review.getOriginalScore()); review.setFinalRiskLevel(review.getOriginalRiskLevel());
        }
        review.setStatus(normalized); review.setReviewComment(trim(comment, 1000)); review.setReviewerId(dataScope.owner());
        review.setReviewedTime(LocalDateTime.now()); reviewMapper.updateById(review); return review;
    }

    public List<ResultReview> reviews(String status) {
        var query = Wrappers.<ResultReview>lambdaQuery().eq(status != null && !status.isBlank(), ResultReview::getStatus, upper(status))
                .orderByDesc(ResultReview::getCreatedTime);
        if (!dataScope.canViewAll()) query.and(q -> q.eq(ResultReview::getCreatedBy, dataScope.owner())
                .or(dataScope.groupId() != null, nested -> nested.eq(ResultReview::getOwnerGroupId, dataScope.groupId())));
        return reviewMapper.selectList(query);
    }

    /** Materializes a stable sample from a visible result and its current reviewed conclusion. */
    @Transactional
    public QualitySample createSample(String resultId, String name, String sampleType, String expectedJson, String tagsJson) {
        ResultContext context = requireResult(resultId);
        ConversationMessage message = messageMapper.selectById(context.result().getMessageId());
        if (message == null) throw IqcException.notFound("质检消息不存在");
        String type = upper(sampleType);
        if (!Set.of("POSITIVE", "NEGATIVE", "FALSE_POSITIVE", "FALSE_NEGATIVE").contains(type)) throw IqcException.invalidArgument("样本类型无效");
        QualitySample sample = new QualitySample(); sample.setName(name == null || name.isBlank() ? "质检样本-" + resultId : trim(name, 128));
        sample.setSampleType(type); sample.setSourceResultId(resultId); sample.setConversationId(context.result().getConversationId());
        sample.setMessageId(message.getId()); sample.setContentSnapshot(message.getContent()); sample.setExpectedJson(expectedJson);
        sample.setTagsJson(tagsJson); sample.setStatus("ENABLED"); sample.setOwnerGroupId(context.task().getOwnerGroupId()); sampleMapper.insert(sample); return sample;
    }

    public List<QualitySample> samples(String type, String status) {
        var query = Wrappers.<QualitySample>lambdaQuery().eq(type != null && !type.isBlank(), QualitySample::getSampleType, upper(type))
                .eq(status != null && !status.isBlank(), QualitySample::getStatus, upper(status)).orderByDesc(QualitySample::getCreatedTime);
        if (!dataScope.canViewAll()) query.and(q -> q.eq(QualitySample::getCreatedBy, dataScope.owner())
                .or(dataScope.groupId() != null, nested -> nested.eq(QualitySample::getOwnerGroupId, dataScope.groupId())));
        return sampleMapper.selectList(query);
    }

    /** Review-backed indicators; unreviewed AI output is not treated as ground truth. */
    public Map<String, Object> report() {
        List<ResultFeedback> feedbacks = feedbacks(null, null);
        List<ResultReview> reviews = reviews(null);
        List<QualitySample> samples = samples(null, null);
        long completed = reviews.stream().filter(item -> !"PENDING".equals(item.getStatus())).count();
        long corrected = reviews.stream().filter(item -> "CORRECTED".equals(item.getStatus())).count();
        long falsePositive = feedbacks.stream().filter(item -> "FALSE_POSITIVE".equals(item.getFeedbackType())).count();
        long falseNegative = feedbacks.stream().filter(item -> "FALSE_NEGATIVE".equals(item.getFeedbackType())).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pendingReviewCount", reviews.stream().filter(item -> "PENDING".equals(item.getStatus())).count());
        result.put("completedReviewCount", completed);
        result.put("correctedReviewCount", corrected);
        result.put("reviewCorrectionRate", completed == 0 ? java.math.BigDecimal.ZERO
                : java.math.BigDecimal.valueOf(corrected * 100.0 / completed).setScale(1, java.math.RoundingMode.HALF_UP));
        result.put("falsePositiveCount", falsePositive);
        result.put("falseNegativeCount", falseNegative);
        result.put("confirmedFeedbackCount", falsePositive + falseNegative);
        result.put("sampleCount", samples.size());
        return result;
    }

    private ResultContext requireResult(String resultId) {
        InspectionResult result = resultMapper.selectById(resultId);
        if (result == null) throw IqcException.notFound("质检结果不存在: " + resultId);
        InspectionTask task = taskMapper.selectById(result.getTaskId());
        if (task == null || !dataScope.canView(task.getCreatedBy(), task.getOwnerGroupId())) throw IqcException.accessDenied("无权操作该质检结果");
        return new ResultContext(result, task);
    }
    private String upper(String value) { return value == null ? "" : value.trim().toUpperCase(); }
    private String trim(String value, int max) { if (value == null) return null; String trimmed = value.trim(); return trimmed.substring(0, Math.min(max, trimmed.length())); }
    private record ResultContext(InspectionResult result, InspectionTask task) { }
}
