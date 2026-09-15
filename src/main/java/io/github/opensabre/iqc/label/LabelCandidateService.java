package io.github.opensabre.iqc.label;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.label.dao.InsightLabelMapper;
import io.github.opensabre.iqc.label.dao.LabelCandidateMapper;
import io.github.opensabre.iqc.label.model.InsightLabel;
import io.github.opensabre.iqc.label.model.LabelCandidate;
import io.github.opensabre.iqc.shared.IqcDataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/** Keeps AI proposals in a review-only pool and never publishes them implicitly. */
@Service
@RequiredArgsConstructor
public class LabelCandidateService {
    private final LabelCandidateMapper mapper;
    private final InsightLabelMapper labelMapper;
    private final LabelTaxonomyService taxonomyService;
    private final IqcDataScope dataScope;

    public List<LabelCandidate> list(String status) {
        var query = Wrappers.<LabelCandidate>lambdaQuery().orderByDesc(LabelCandidate::getCreatedTime);
        if (status != null && !status.isBlank()) query.eq(LabelCandidate::getStatus, status);
        if (!dataScope.canViewAll()) query.and(q -> q.eq(LabelCandidate::getCreatedBy, dataScope.owner())
                .or(dataScope.groupId() != null, n -> n.eq(LabelCandidate::getOwnerGroupId, dataScope.groupId())));
        return mapper.selectList(query);
    }

    public List<Similarity> similarities(String id) {
        LabelCandidate candidate = mapper.selectById(id);
        if (candidate == null) throw IqcException.notFound("候选标签不存在: " + id);
        if (!dataScope.canView(candidate.getCreatedBy(), candidate.getOwnerGroupId())) throw IqcException.accessDenied("无权查看该候选标签");
        String source = normalizeText(candidate.getSuggestedName() + " " + candidate.getSuggestedCode());
        return labelMapper.selectList(Wrappers.<InsightLabel>lambdaQuery().ne(InsightLabel::getStatus, "DISABLED"))
                .stream().filter(label -> dataScope.canView(label.getCreatedBy(), label.getOwnerGroupId()))
                .map(label -> new Similarity(label.getId(), label.getName(), label.getCode(), similarity(source, normalizeText(label.getName() + " " + label.getCode()))))
                .filter(value -> value.score().compareTo(BigDecimal.ZERO) > 0)
                .sorted(java.util.Comparator.comparing(Similarity::score).reversed()).limit(5).toList();
    }

    @Transactional
    public LabelCandidate propose(Proposal request) {
        if (request == null || blank(request.taskId()) || blank(request.conversationId()) || blank(request.suggestedName()) || blank(request.suggestedCode()))
            throw IqcException.invalidArgument("候选标签必须包含任务、会话、名称和编码");
        if (request.confidence() == null || request.confidence().compareTo(BigDecimal.ZERO) < 0
                || request.confidence().compareTo(BigDecimal.ONE) > 0) throw IqcException.invalidArgument("候选置信度必须在 0 到 1 之间");
        LabelCandidate duplicate = mapper.selectOne(Wrappers.<LabelCandidate>lambdaQuery()
                .eq(LabelCandidate::getTaskId, request.taskId()).eq(LabelCandidate::getConversationId, request.conversationId())
                .eq(LabelCandidate::getSuggestedCode, request.suggestedCode()).eq(LabelCandidate::getStatus, "PENDING").last("LIMIT 1"));
        if (duplicate != null) return duplicate;
        LabelCandidate value = new LabelCandidate();
        value.setTaskId(request.taskId()); value.setConversationId(request.conversationId());
        value.setCategoryId(request.categoryId()); value.setGroupId(request.groupId());
        value.setSuggestedName(request.suggestedName()); value.setSuggestedCode(request.suggestedCode());
        value.setDescription(request.description()); value.setValueJson(request.valueJson());
        value.setEvidenceJson(request.evidenceJson()); value.setConfidence(request.confidence());
        value.setModelSnapshotJson(request.modelSnapshotJson()); value.setStatus("PENDING");
        value.setOwnerGroupId(dataScope.groupId()); mapper.insert(value); return value;
    }

    @Transactional
    public LabelCandidate reject(String id, String comment) {
        LabelCandidate value = pending(id); value.setStatus("REJECTED"); value.setReviewComment(comment);
        mapper.updateById(value); return value;
    }

    @Transactional
    public LabelCandidate merge(String id, String labelId, String comment) {
        LabelCandidate value = pending(id);
        InsightLabel label = labelMapper.selectById(labelId);
        if (label == null) throw IqcException.notFound("合并目标标签不存在");
        if (!dataScope.canView(label.getCreatedBy(), label.getOwnerGroupId())) throw IqcException.accessDenied("无权合并到该标签");
        if (!"PUBLISHED".equals(label.getStatus())) throw IqcException.invalidState("候选只能合并到已发布标签");
        value.setStatus("MERGED"); value.setMergedLabelId(labelId); value.setReviewComment(comment);
        mapper.updateById(value); return value;
    }

    @Transactional
    public LabelCandidate approveAsDraft(String id, String groupId, String targetRole, BigDecimal weight, String comment) {
        LabelCandidate candidate = pending(id);
        InsightLabel label = taxonomyService.createLabel(new LabelTaxonomyService.LabelRequest(groupId,
                candidate.getSuggestedName(), candidate.getSuggestedCode(), candidate.getDescription(), targetRole, weight));
        label.setSourceType("AI_APPROVED"); labelMapper.updateById(label);
        candidate.setStatus("APPROVED"); candidate.setCreatedLabelId(label.getId()); candidate.setReviewComment(comment);
        mapper.updateById(candidate); return candidate;
    }

    private LabelCandidate pending(String id) {
        LabelCandidate value = mapper.selectById(id);
        if (value == null) throw IqcException.notFound("候选标签不存在: " + id);
        if (!"PENDING".equals(value.getStatus())) throw IqcException.invalidState("候选标签已完成审核");
        if (!dataScope.canView(value.getCreatedBy(), value.getOwnerGroupId())) throw IqcException.accessDenied("无权审核该候选标签");
        return value;
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String normalizeText(String value) { return value == null ? "" : value.toLowerCase().replaceAll("[^\\p{L}\\p{N}]", ""); }
    private BigDecimal similarity(String left, String right) {
        if (left.isEmpty() || right.isEmpty()) return BigDecimal.ZERO;
        int[] previous = new int[right.length() + 1]; for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) { int[] current = new int[right.length() + 1]; current[0] = i;
            for (int j = 1; j <= right.length(); j++) current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1)); previous = current; }
        return BigDecimal.ONE.subtract(BigDecimal.valueOf(previous[right.length()]).divide(BigDecimal.valueOf(Math.max(left.length(), right.length())), 4, java.math.RoundingMode.HALF_UP));
    }

    public record Proposal(String taskId, String conversationId, String categoryId, String groupId,
                           String suggestedName, String suggestedCode, String description, String valueJson,
                           String evidenceJson, BigDecimal confidence, String modelSnapshotJson) { }
    public record Similarity(String labelId, String name, String code, BigDecimal score) { }
}
