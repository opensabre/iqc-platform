package io.github.opensabre.iqc.quality;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import io.github.opensabre.iqc.quality.dao.QualitySampleMapper;
import io.github.opensabre.iqc.quality.dao.ResultReviewMapper;
import io.github.opensabre.iqc.quality.model.QualitySample;
import io.github.opensabre.iqc.scheme.SchemeResultEvaluator;
import io.github.opensabre.iqc.pipelineasset.PipelineRelease;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/** Captures a reviewed conversation in the existing sample store; candidate approval is a separate step. */
@Service
@RequiredArgsConstructor
public class BusinessReviewSampleService {
    /** Explicit candidate identity for exact-number pipeline sources; old candidate versions remain unchanged. */
    public static final String EXACT_INPUT_SCHEMA="iqc-conversation-candidate-input-v3";
    /** Replays the hash selected by the immutable candidate body protocol; unknown versions fail closed. */
    public static String candidateInputHash(JsonNode input) {
        return switch(input.path("schemaVersion").asText()) {
            case "iqc-conversation-candidate-input-v1", "iqc-conversation-candidate-input-v2" -> PipelineAssetService.hash(input.toString());
            case EXACT_INPUT_SCHEMA -> io.github.opensabre.iqc.selector.SelectorAssetService.hash(input.toString());
            default -> throw IqcException.invalidState("不支持的会话候选输入协议");
        };
    }

    private final QualitySampleMapper samples;
    private final ResultReviewMapper reviews;
    private final ConversationInspectionResultMapper results;
    private final InspectionTaskMapper tasks;
    private final ConversationMapper conversations;
    private final ConversationMessageMapper messages;
    private final BusinessItemReviewService businessReviews;
    private final IqcDataScope scope;
    private final ObjectMapper json;

    /** Reads the first captured candidate; later review/source edits never cause a recapture. */
    @Transactional(readOnly = true)
    public QualitySample lookupRequest(String resultId, int reviewRevision, String requestId,
                                       String name, String sampleType, String tagsJson) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{16,64}") || reviewRevision < 1)
            throw IqcException.invalidArgument("候选请求或复核修订无效");
        String owner = scope.owner();
        var source = results.selectById(resultId);
        var task = source == null ? null : tasks.selectById(source.getTaskId());
        authorize(task);
        if (owner == null || "system".equals(owner) || !owner.equals(task.getCreatedBy()))
            throw IqcException.accessDenied("无权核对候选来源");
        var existing = samples.selectById("qc-" + hash(json.valueToTree(List.of(owner, requestId))).substring(0, 60));
        if (existing == null) return null;
        var expected = parse(existing.getExpectedJson());
        String reviewId = expected.path("reviewId").asText();
        var review = reviews.selectById(reviewId);
        String label = name == null || name.isBlank() ? "会话候选-" + reviewId : name.trim();
        String fingerprint = hash(json.valueToTree(List.of(resultId, reviewId, label, sampleType, tags(tagsJson))));
        if (!"iqc-reviewed-business-candidate-v1".equals(expected.path("schemaVersion").asText())
                || !resultId.equals(existing.getSourceResultId()) || !owner.equals(existing.getCreatedBy())
                || !source.getConversationId().equals(existing.getConversationId())
                || expected.path("reviewRevision").asInt(-1) != reviewRevision
                || !fingerprint.equals(expected.path("requestFingerprint").asText())
                || review == null || !"BUSINESS".equals(review.getTargetType())
                || !resultId.equals(review.getBusinessResultId())
                || !java.util.Objects.equals(reviewRevision, review.getReviewRevision()))
            throw IqcException.invalidState("候选持久产物与原请求不一致");
        return existing;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public QualitySample create(String resultId, String reviewId, String requestId, String name, String sampleType, String tagsJson) {
        if (resultId == null || resultId.isBlank() || reviewId == null || reviewId.isBlank()
                || requestId == null || !requestId.matches("[A-Za-z0-9_-]{16,64}"))
            throw IqcException.invalidArgument("会话候选必须指定原始结果、复核轮次及幂等请求标识");
        if (!Set.of("POSITIVE", "NEGATIVE", "FALSE_POSITIVE", "FALSE_NEGATIVE").contains(sampleType == null ? "" : sampleType))
            throw IqcException.invalidArgument("样本类型无效");
        String actor = scope.owner();
        if (actor == null || actor.isBlank() || "system".equals(actor)) throw IqcException.accessDenied("候选样本必须由已登录用户创建");
        String label = name == null || name.isBlank() ? "会话候选-" + reviewId : name.trim();
        if (label.length() > 128) throw IqcException.invalidArgument("样本名称不得超过128字");
        JsonNode tags = tags(tagsJson);
        var source = results.selectById(resultId);
        if (source == null) throw IqcException.notFound("会话质检结果不存在");
        var task = tasks.selectById(source.getTaskId());
        authorize(task);
        if ("PIPELINE_EVALUATION".equals(task.getTaskType())) throw IqcException.invalidArgument("评测产物不属于生产业务候选来源");
        // Review decisions and task execution use this same lock; concurrent retries cannot insert twice.
        task = tasks.selectOne(Wrappers.<InspectionTask>lambdaQuery().eq(InspectionTask::getId, task.getId()).last("FOR UPDATE"));
        authorize(task);
        source = results.selectById(resultId);
        if (source == null || !task.getId().equals(source.getTaskId())) throw IqcException.invalidState("原始结果已变化");
        String fingerprint = hash(json.valueToTree(List.of(resultId, reviewId, label, sampleType, tags)));
        String id = "qc-" + hash(json.valueToTree(List.of(actor, requestId))).substring(0, 60);
        var existing = samples.selectById(id);
        if (existing != null) {
            var expected = parse(existing.getExpectedJson());
            if (!"iqc-reviewed-business-candidate-v1".equals(expected.path("schemaVersion").asText())
                    || !resultId.equals(existing.getSourceResultId())
                    || !fingerprint.equals(expected.path("requestFingerprint").asText()))
                throw IqcException.invalidArgument("同一候选请求标识不能用于不同内容");
            return existing; // Immutable first capture survives later source edits; never recapture on retry.
        }
        var review = reviews.selectById(reviewId);
        if (review == null || !"BUSINESS".equals(review.getTargetType()) || !resultId.equals(review.getBusinessResultId()))
            throw IqcException.invalidArgument("候选必须引用该结果的业务复核轮次");
        var projection = businessReviews.validatedProjection(review, source, task);
        if (projection.reviewed().items().stream().anyMatch(item -> item.status() != InspectionScoring.ItemStatus.PASS
                && item.status() != InspectionScoring.ItemStatus.FAIL && item.status() != InspectionScoring.ItemStatus.NOT_APPLICABLE))
            throw IqcException.invalidState("候选样本仍有未确认的质检项，请先完成复核");
        var conversation = conversations.selectById(source.getConversationId());
        if (conversation == null) throw IqcException.invalidState("候选原始会话不存在");
        var transcript = messages.selectList(Wrappers.<ConversationMessage>lambdaQuery()
                .eq(ConversationMessage::getConversationId, source.getConversationId())
                .orderByAsc(ConversationMessage::getSequenceNo, ConversationMessage::getId));
        var ids = new java.util.HashSet<String>(); var sequences = new java.util.HashSet<Integer>();
        if (transcript.isEmpty() || transcript.size() > 10_000
                || conversation.getMessageCount() != null && conversation.getMessageCount() != transcript.size())
            throw IqcException.invalidState("候选会话消息不完整或超过10000条");
        var verifiedInput = io.github.opensabre.iqc.result.ReviewSampleInputValidator.validateInput(
                source.getExecutionTraceJson(), transcript, task, source.getConversationId(), json);
        String executionInputHash = verifiedInput.executionInputHash();
        var snapshot = parse(task.getRuleSnapshotJson());
        boolean exact=PipelineRelease.EXACT_SCHEMA.equals(snapshot.path("pipelineSnapshot").path("release").path("schemaVersion").asText());
        boolean aligned = transcript.stream().anyMatch(message -> message.getStartMs()!=null || message.getEndMs()!=null || message.getConfidence()!=null);
        var input = json.createObjectNode().put("schemaVersion", exact ? EXACT_INPUT_SCHEMA : aligned ? "iqc-conversation-candidate-input-v2" : "iqc-conversation-candidate-input-v1")
                .put("conversationId", source.getConversationId()).put("channel", conversation.getChannel())
                .put("sourceType", conversation.getSourceType()).put("businessType", conversation.getBusinessType())
                .put("employeeId", conversation.getEmployeeId()).put("employeeGroupId", conversation.getEmployeeGroupId())
                .put("startedTime", conversation.getStartedTime() == null ? null : conversation.getStartedTime().toString())
                .put("endedTime", conversation.getEndedTime() == null ? null : conversation.getEndedTime().toString());
        if(verifiedInput.businessContext()!=null)input.set("businessContext",verifiedInput.businessContext());
        var segments = input.putArray("segments");
        for (var message : transcript) {
            if (!source.getConversationId().equals(message.getConversationId()) || message.getId() == null
                    || !ids.add(message.getId()) || message.getSequenceNo() == null || message.getSequenceNo() < 1
                    || !sequences.add(message.getSequenceNo()) || message.getContent() == null || message.getId().length() > 64
                    || message.getSpeakerRole() == null || message.getSpeakerRole().isBlank() || message.getSpeakerRole().length() > 128)
                throw IqcException.invalidState("候选会话消息或顺序损坏");
            io.github.opensabre.iqc.conversation.TranscriptAlignment.validate(message.getStartMs(),message.getEndMs(),message.getConfidence());
            var segment = segments.addObject().put("id", message.getId()).put("sequenceNo", message.getSequenceNo())
                    .put("speakerRole", message.getSpeakerRole()).put("relativeTime", message.getRelativeTime() == null ? null : message.getRelativeTime().toString())
                    .put("content", message.getContent());
            if(message.getStartMs()!=null)segment.put("startMs",message.getStartMs());
            if(message.getEndMs()!=null)segment.put("endMs",message.getEndMs());
            if(message.getConfidence()!=null)segment.put("confidence",message.getConfidence());
        }
        boolean pipeline = snapshot.has("pipelineSnapshot");
        var marker = snapshot.path(pipeline ? "pipelineSnapshot" : "schemeSnapshot");
        Object scoring;
        try {
            if (pipeline) {
                var release = json.treeToValue(marker.path("release"), PipelineRelease.class);
                if (release == null || snapshot.has("schemeSnapshot") || !marker.path("contentHash").asText().equals(hash(json.valueToTree(release))))
                    throw IqcException.invalidState("候选流水线冻结政策摘要无效");
                scoring = release.scoring();
            } else scoring = SchemeResultEvaluator.definition(snapshot, json).scoring();
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw IqcException.invalidState("候选冻结政策损坏"); }
        var expected = json.createObjectNode().put("schemaVersion", "iqc-reviewed-business-candidate-v1")
                .put("requestFingerprint", fingerprint).put("sourceResultId", resultId).put("taskId", task.getId())
                .put("executionId", source.getExecutionId()).put("reviewId", reviewId).put("reviewRevision", review.getReviewRevision())
                .put("reviewerId", review.getReviewerId()).put("reviewedTime", review.getReviewedTime().toString())
                .put("sourceHash", review.getSourceHash()).put("inputHash", candidateInputHash(input)).put("executionInputHash", executionInputHash);
        expected.set("expected", json.valueToTree(projection.reviewed()));
        expected.set("humanDecisions", json.valueToTree(projection.decisions()));
        if(projection.workflow()!=null)expected.set("reviewWorkflow",json.valueToTree(projection.workflow()));
        if(projection.manualScore()!=null)expected.set("manualScore",json.valueToTree(projection.manualScore()));
        if(projection.findingReview()!=null){
            expected.set("humanFindingDecisions",json.valueToTree(projection.findingReview().findings().decisions()));
            var reviewedFindings=json.createArrayNode();
            for(var finding:projection.findingReview().findings().reviewed()){
                var fact=json.createObjectNode().put("code",finding.code()).put("valueType",finding.valueType().name())
                        .put("state",finding.state().name()).put("severity",finding.severity().name());
                fact.set("value",finding.value());
                // Gold evidence keeps only frozen message identities, never external tool details or raw detector output.
                var evidenceIds=new java.util.LinkedHashSet<String>();
                for(var evidence:finding.evidence()){
                    if(ids.contains(evidence.reference()))evidenceIds.add(evidence.reference());
                    var detail=evidence.details();
                    if(detail!=null&&ids.contains(detail.path("messageId").asText()))evidenceIds.add(detail.path("messageId").asText());
                    if(detail!=null&&detail.path("messageIds").isArray())detail.path("messageIds").forEach(value->{if(value.isTextual()&&ids.contains(value.asText()))evidenceIds.add(value.asText());});
                }
                fact.set("evidenceMessageIds",json.valueToTree(evidenceIds));reviewedFindings.add(fact);
            }
            expected.set("humanReviewedFindings",reviewedFindings);
        }
        expected.set("scoringPolicy", json.valueToTree(scoring));
        // Keep only public version identity, not node settings, prompts, secrets or model call output.
        expected.set("policySource", json.createObjectNode().put("sourceType", pipeline ? "PIPELINE" : "SCHEME")
                .put("id", marker.path(pipeline ? "pipelineId" : "schemeId").asText())
                .put("versionNo", marker.path("versionNo").asText(""))
                .put("draftRevision", marker.path("draftRevision").asText(""))
                .put("contentHash", marker.path("contentHash").asText("")));
        var sample = new QualitySample(); sample.setId(id); sample.setName(label); sample.setSampleType(sampleType);
        sample.setSourceResultId(resultId); sample.setConversationId(source.getConversationId()); sample.setMessageId(null);
        sample.setContentSnapshot(bounded(input)); sample.setExpectedJson(bounded(expected)); sample.setTagsJson(tags.toString());
        sample.setStatus("CANDIDATE"); sample.setOwnerGroupId(task.getOwnerGroupId()); sample.setCreatedBy(actor);
        try { samples.insert(sample); }
        catch (org.springframework.dao.DuplicateKeyException conflict) {
            var winner = samples.selectById(id);
            if (winner == null || !resultId.equals(winner.getSourceResultId())
                    || !fingerprint.equals(parse(winner.getExpectedJson()).path("requestFingerprint").asText()))
                throw IqcException.invalidArgument("同一候选请求标识不能用于不同内容");
            return winner;
        }
        return sample;
    }
    private void authorize(InspectionTask task) {
        if (task == null || !scope.canView(task.getCreatedBy(), task.getOwnerGroupId())) throw IqcException.accessDenied("无权沉淀该会话候选");
    }
    private JsonNode tags(String value) {
        if (value != null && value.length() > 16384) throw IqcException.invalidArgument("候选标签过大");
        var tags = value == null || value.isBlank() ? json.createArrayNode() : parse(value);
        if (!tags.isArray() || tags.size() > 50) throw IqcException.invalidArgument("候选标签最多50个字符串");
        for (var tag : tags) if (!tag.isTextual() || tag.asText().isBlank() || tag.asText().length() > 64)
            throw IqcException.invalidArgument("候选标签必须为1–64字字符串");
        return tags;
    }
    private JsonNode parse(String value) {
        try { var node = PipelineAssetService.readFrozenTree(json,value); if (node == null) throw new IllegalArgumentException(); return node; }
        catch (Exception exception) { throw IqcException.invalidState("会话候选快照JSON无效"); }
    }
    private String hash(JsonNode value) { return PipelineAssetService.hash(value.toString()); }
    private String bounded(JsonNode value) {
        String body = value.toString();
        if (body.getBytes(StandardCharsets.UTF_8).length > 4 * 1024 * 1024)
            throw IqcException.invalidArgument("完整会话候选快照超过4MiB，不能截断保存");
        return body;
    }
}
