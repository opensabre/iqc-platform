package io.github.opensabre.iqc.quality.evaluation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import io.github.opensabre.iqc.quality.dao.QualitySampleMapper;
import io.github.opensabre.iqc.quality.dao.QualitySampleVersionMapper;
import io.github.opensabre.iqc.quality.model.QualitySample;
import io.github.opensabre.iqc.quality.model.QualitySampleVersion;
import io.github.opensabre.iqc.shared.IqcDataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Extends the governed candidate pool rather than creating a second sample registry. */
@Service @RequiredArgsConstructor
public class GoldSampleService {
    private final QualitySampleMapper samples;
    private final QualitySampleVersionMapper versions;
    private final ConversationMapper conversations;
    private final ConversationMessageMapper messages;
    private final IqcDataScope scope;
    private final ObjectMapper json;
    public static final int MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024;

    public record Annotation(Integer expectedRevision, List<GoldFinding> findings, JsonNode businessContext,
                             List<String> tags, GoldConversation.Difficulty difficulty, GoldDecision decision) { }

    public QualitySample get(String sampleId) { return require(sampleId, false); }

    @Transactional
    public QualitySample annotate(String sampleId, Annotation request) {
        if (request == null || request.expectedRevision() == null || request.expectedRevision() < 0)
            throw IqcException.invalidArgument("必须指定金标草稿修订");
        QualitySample sample = require(sampleId, true);
        checkRevision(sample, request.expectedRevision());
        if (!available(sample)) throw IqcException.invalidState("停用候选样本不能标注");
        Input input;
        try { input = freezeInput(sample); }
        catch (IllegalArgumentException | NullPointerException exception) {
            throw IqcException.invalidState("来源输入损坏，不能生成金标");
        }
        var context = input.context().deepCopy();
        if (request.businessContext() != null && !request.businessContext().isNull()) {
            if (!request.businessContext().isObject()) throw IqcException.invalidArgument("标注业务上下文必须是对象");
            context.set("annotation", request.businessContext().deepCopy());
        }
        GoldDraft draft;
        try {
            draft = new GoldDraft(GoldDraft.SCHEMA, sampleId, sample.getConversationId(), input.messages(),
                    context, request.findings(), actor(), "RESULT:" + sample.getSourceResultId(),
                    request.tags(), request.difficulty(), request.decision());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw IqcException.invalidArgument("金标内容无效：" + exception.getMessage(), exception);
        }
        sample.setGoldDraftJson(write(draft)); sample.setGoldDraftRevision(request.expectedRevision() + 1);
        sample.setGoldStatus("PENDING_REVIEW"); samples.updateById(sample); return sample;
    }

    private record Input(List<GoldConversation.Message> messages, com.fasterxml.jackson.databind.node.ObjectNode context) { }
    private Input freezeInput(QualitySample sample) {
        if (sample.getMessageId() == null) return reviewedCandidate(sample);
        var conversation = conversations.selectById(sample.getConversationId());
        if (conversation == null) throw IqcException.notFound("样本来源会话不存在");
        if (!scope.canView(conversation.getCreatedBy(), conversation.getOwnerGroupId()))
            throw IqcException.accessDenied("无权冻结样本来源会话");
        var frozen = messages.selectList(Wrappers.<ConversationMessage>lambdaQuery()
                .eq(ConversationMessage::getConversationId, sample.getConversationId())
                .orderByAsc(ConversationMessage::getSequenceNo).orderByAsc(ConversationMessage::getId).last("LIMIT 10001"));
        if (frozen.isEmpty() || frozen.size() > 10000
                || conversation.getMessageCount() != null && conversation.getMessageCount() != frozen.size())
            throw IqcException.invalidState("金标来源会话不完整或超过10000条消息");
        var source = frozen.stream().filter(message -> message.getId().equals(sample.getMessageId())).findFirst()
                .orElseThrow(() -> IqcException.invalidState("候选消息不在来源会话中"));
        if (!Objects.equals(sample.getContentSnapshot(), source.getContent()))
            throw IqcException.invalidState("来源消息已改变，请从新输入修订创建候选样本");
        var context = json.createObjectNode();
        var metadata = context.putObject("conversation");
        metadata.put("channel", conversation.getChannel()); metadata.put("businessType", conversation.getBusinessType());
        metadata.put("businessNo", conversation.getBusinessNo()); metadata.put("employeeId", conversation.getEmployeeId());
        metadata.put("customerExternalId", conversation.getCustomerExternalId());
        return new Input(frozen.stream().map(message -> {
                if (message.getSequenceNo() == null) throw new IllegalArgumentException("missing message sequence");
                return new GoldConversation.Message(message.getId(), message.getSpeakerRole(), message.getContent(),
                        message.getSequenceNo(), message.getRelativeTime(),message.getStartMs(),message.getEndMs(),message.getConfidence());
            }).toList(), context);
    }

    private static Long coordinate(JsonNode segment,String field) {
        var value=segment.get(field);
        if(value==null || value.isNull())return null;
        if(!value.isIntegralNumber() || !value.canConvertToLong())throw IqcException.invalidState("候选时间坐标必须是整数");
        return value.longValue();
    }

    /** B's reviewed business candidate has a frozen transcript; never recapture its live conversation. */
    private Input reviewedCandidate(QualitySample sample) {
        JsonNode input = read(sample.getContentSnapshot(), JsonNode.class);
        JsonNode lineage = read(sample.getExpectedJson(), JsonNode.class);
        boolean aligned = "iqc-conversation-candidate-input-v2".equals(input.path("schemaVersion").asText());
        if ((!aligned && !"iqc-conversation-candidate-input-v1".equals(input.path("schemaVersion").asText()))
                || !"iqc-reviewed-business-candidate-v1".equals(lineage.path("schemaVersion").asText())
                || !sample.getConversationId().equals(input.path("conversationId").asText())
                || !Objects.equals(sample.getSourceResultId(), lineage.path("sourceResultId").asText())
                || !PipelineAssetService.hash(input.toString()).equals(lineage.path("inputHash").asText())
                || !input.path("segments").isArray() || input.path("segments").isEmpty() || input.path("segments").size() > 10000)
            throw IqcException.invalidState("会话候选冻结输入或来源摘要无效");
        var frozen = new java.util.ArrayList<GoldConversation.Message>();
        for (JsonNode segment : input.path("segments")) {
            if (!segment.path("id").isTextual() || !segment.path("speakerRole").isTextual()
                    || !segment.path("content").isTextual() || !segment.path("sequenceNo").isIntegralNumber()
                    || !segment.path("sequenceNo").canConvertToInt()) throw IqcException.invalidState("候选消息字段无效");
            java.time.LocalTime relative = null;
            if (!segment.path("relativeTime").isMissingNode() && !segment.path("relativeTime").isNull()) {
                try { relative = java.time.LocalTime.parse(segment.path("relativeTime").asText()); }
                catch (java.time.DateTimeException exception) { throw IqcException.invalidState("候选消息相对时间无效"); }
            }
            if (!aligned && (segment.has("startMs") || segment.has("endMs") || segment.has("confidence")))
                throw IqcException.invalidState("旧候选协议不能携带音频对齐");
            Long start = coordinate(segment,"startMs"), end = coordinate(segment,"endMs");
            var confidenceNode=segment.get("confidence");
            if(confidenceNode!=null && !confidenceNode.isNull() && !confidenceNode.isNumber())
                throw IqcException.invalidState("候选置信度必须是数字");
            frozen.add(new GoldConversation.Message(segment.path("id").asText(), segment.path("speakerRole").asText(),
                    segment.path("content").asText(), segment.path("sequenceNo").intValue(), relative,start,end,
                    confidenceNode==null || confidenceNode.isNull()?null:confidenceNode.decimalValue()));
        }
        var context = json.createObjectNode();
        var metadata = ((com.fasterxml.jackson.databind.node.ObjectNode) input).deepCopy(); metadata.remove("segments");
        context.set("conversation", metadata);
        var origin = context.putObject("candidateLineage");
        for (String field : List.of("sourceResultId", "taskId", "executionId", "reviewId", "reviewRevision", "reviewerId",
                "reviewedTime", "sourceHash", "inputHash", "executionInputHash", "policySource"))
            if (lineage.has(field)) origin.set(field, lineage.get(field).deepCopy());
        return new Input(List.copyOf(frozen), context);
    }

    /** One immutable decision per revision; retries cannot turn a rejection into approval. */
    @Transactional
    public QualitySampleVersion review(String sampleId, int expectedRevision, boolean approve, String reason) {
        QualitySample sample = require(sampleId, true); checkRevision(sample, expectedRevision);
        if (reason == null || reason.isBlank() || reason.length() > 1000)
            throw IqcException.invalidArgument("审核必须填写不超过1000字的原因");
        String reviewer = actor(), decision = approve ? "APPROVED" : "REJECTED";
        var existing = versions.selectOne(Wrappers.<QualitySampleVersion>lambdaQuery()
                .eq(QualitySampleVersion::getSampleId, sampleId).eq(QualitySampleVersion::getSourceDraftRevision, expectedRevision));
        if (existing != null) {
            if (!decision.equals(existing.getDecision()) || !reviewer.equals(existing.getReviewerId()) || !reason.equals(existing.getReason()))
                throw IqcException.invalidState("该修订已审核，请重新标注生成新修订");
            return existing;
        }
        if (!available(sample) || !"PENDING_REVIEW".equals(sample.getGoldStatus()))
            throw IqcException.invalidState("当前样本没有待审核金标");
        GoldDraft draft = read(sample.getGoldDraftJson(), GoldDraft.class);
        if (!sampleId.equals(draft.sourceSampleId()) || !sample.getConversationId().equals(draft.conversationId()))
            throw IqcException.invalidState("金标草稿来源与候选样本不一致");
        if (reviewer.equals(draft.annotatorId())) throw IqcException.accessDenied("金标标注人与审核人必须不同");
        var version = new QualitySampleVersion(); version.setSampleId(sampleId);
        version.setVersionNo(number(sample.getGoldReviewVersion()) + 1); version.setSourceDraftRevision(expectedRevision);
        version.setDecision(decision); version.setReviewerId(reviewer); version.setReason(reason);
        version.setSnapshotJson(write(approve ? draft.approve(reviewer) : draft));
        version.setContentHash(PipelineAssetService.hash(version.getSnapshotJson())); versions.insert(version);
        sample.setGoldStatus(decision); sample.setGoldReviewVersion(version.getVersionNo()); samples.updateById(sample);
        return version;
    }

    public io.github.opensabre.iqc.shared.IqcPage<QualitySampleVersion> history(String sampleId, long current, long size) {
        require(sampleId, false);
        if (current < 1 || size < 1 || size > 100) throw IqcException.invalidArgument("无效金标历史分页");
        var query = Wrappers.<QualitySampleVersion>lambdaQuery().eq(QualitySampleVersion::getSampleId, sampleId)
                .select(QualitySampleVersion.class, field -> !"snapshotJson".equals(field.getProperty()))
                .orderByDesc(QualitySampleVersion::getVersionNo);
        return io.github.opensabre.iqc.shared.IqcPage.from(versions.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current, size), query));
    }
    public QualitySampleVersion version(String sampleId, int versionNo) {
        QualitySample sample = require(sampleId, false);
        if (versionNo < 1) throw IqcException.invalidArgument("金标版本必须为正整数");
        var version = versions.selectOne(Wrappers.<QualitySampleVersion>lambdaQuery()
                .eq(QualitySampleVersion::getSampleId, sampleId).eq(QualitySampleVersion::getVersionNo, versionNo));
        if (version == null) throw IqcException.notFound("金标审核版本不存在");
        String sourceSample, sourceConversation, reviewer;
        if ("APPROVED".equals(version.getDecision())) {
            var gold = read(version.getSnapshotJson(), GoldConversation.class);
            sourceSample = gold.sourceSampleId(); sourceConversation = gold.conversationId(); reviewer = gold.reviewerId();
        } else if ("REJECTED".equals(version.getDecision())) {
            var gold = read(version.getSnapshotJson(), GoldDraft.class);
            sourceSample = gold.sourceSampleId(); sourceConversation = gold.conversationId(); reviewer = version.getReviewerId();
            if (gold.annotatorId().equals(reviewer)) throw IqcException.invalidState("金标退回记录不是独立审核");
        } else throw IqcException.invalidState("金标审核决定无效");
        if (!sampleId.equals(sourceSample) || !sample.getConversationId().equals(sourceConversation)
                || !Objects.equals(version.getReviewerId(), reviewer)
                || !PipelineAssetService.hash(version.getSnapshotJson()).equals(version.getContentHash()))
            throw IqcException.invalidState("金标版本来源或摘要不一致");
        return version;
    }

    /** Dataset freeze must name an exact approved version, not a mutable active pointer. */
    public QualitySampleVersion approvedVersion(String sampleId, int versionNo) {
        return approvedVersion(sampleId, versionNo, false);
    }
    public QualitySampleVersion approvedVersionForFreeze(String sampleId, int versionNo) {
        return approvedVersion(sampleId, versionNo, true);
    }
    private QualitySampleVersion approvedVersion(String sampleId, int versionNo, boolean lock) {
        QualitySample sample = require(sampleId, lock);
        if (!available(sample)) throw IqcException.invalidState("停用样本不能加入新的数据集版本");
        var version = versions.selectOne(Wrappers.<QualitySampleVersion>lambdaQuery()
                .eq(QualitySampleVersion::getSampleId, sampleId).eq(QualitySampleVersion::getVersionNo, versionNo));
        if (version == null) throw IqcException.notFound("金标样本版本不存在");
        if (!"APPROVED".equals(version.getDecision())) throw IqcException.invalidState("数据集只能冻结已审核金标");
        GoldConversation gold = read(version.getSnapshotJson(), GoldConversation.class);
        if (!sampleId.equals(gold.sourceSampleId()) || !sample.getConversationId().equals(gold.conversationId())
                || !Objects.equals(version.getReviewerId(), gold.reviewerId())
                || !PipelineAssetService.hash(version.getSnapshotJson()).equals(version.getContentHash()))
            throw IqcException.invalidState("金标版本来源或摘要不一致");
        return version;
    }

    private QualitySample require(String id, boolean lock) {
        if (id == null || id.isBlank()) throw IqcException.invalidArgument("样本ID不能为空");
        var query = Wrappers.<QualitySample>lambdaQuery().eq(QualitySample::getId, id);
        if (lock) query.last("FOR UPDATE");
        var sample = samples.selectOne(query);
        if (sample == null) throw IqcException.notFound("候选样本不存在");
        if (!scope.canView(sample.getCreatedBy(), sample.getOwnerGroupId())) throw IqcException.accessDenied("无权访问候选样本");
        return sample;
    }
    private void checkRevision(QualitySample sample, int expected) {
        if (expected < 0 || number(sample.getGoldDraftRevision()) != expected) throw IqcException.invalidState("金标草稿修订已变化");
    }
    private int number(Integer value) { return value == null ? 0 : value; }
    private boolean available(QualitySample sample) { return "ENABLED".equals(sample.getStatus()) || "CANDIDATE".equals(sample.getStatus()); }
    private String actor() {
        String actor = scope.owner();
        if (actor == null || actor.isBlank() || "system".equals(actor)) throw IqcException.accessDenied("金标操作需要可识别的登录用户");
        return actor;
    }
    private String write(Object value) {
        try {
            String body = json.writeValueAsString(value);
            if (body.getBytes(StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES)
                throw IqcException.invalidArgument("金标快照超过8MiB");
            return body;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw IqcException.invalidArgument("金标序列化失败", exception); }
    }
    public <T> T read(String body, Class<T> type) {
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES)
            throw IqcException.invalidState("金标快照为空或超过限制");
        try {
            T value = json.readValue(body, type);
            if (value == null) throw IqcException.invalidState("金标快照不能为空值");
            return value;
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw IqcException.invalidState("金标快照格式无效"); }
    }
}
