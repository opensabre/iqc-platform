package io.github.opensabre.iqc.quality.evaluation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import io.github.opensabre.iqc.quality.dao.EvaluationDatasetMapper;
import io.github.opensabre.iqc.quality.dao.EvaluationDatasetVersionMapper;
import io.github.opensabre.iqc.quality.model.EvaluationDataset;
import io.github.opensabre.iqc.quality.model.EvaluationDatasetVersion;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.shared.IqcPage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

@Service @RequiredArgsConstructor
public class EvaluationDatasetService {
    private final EvaluationDatasetMapper datasets;
    private final EvaluationDatasetVersionMapper versions;
    private final GoldSampleService goldSamples;
    private final IqcDataScope scope;
    private final ObjectMapper json;
    private static final int MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024;

    public IqcPage<EvaluationDataset> page(long current, long size) {
        pagination(current, size);
        var query = Wrappers.<EvaluationDataset>lambdaQuery();
        if (!scope.canViewAll()) {
            String group = scope.groupId();
            query.and(q -> { q.eq(EvaluationDataset::getCreatedBy, scope.owner());
                if (group != null) q.or().eq(EvaluationDataset::getOwnerGroupId, group); });
        }
        query.orderByDesc(EvaluationDataset::getCreatedTime).orderByAsc(EvaluationDataset::getId);
        return IqcPage.from(datasets.selectPage(new Page<>(current, size), query));
    }
    public EvaluationDataset get(String id) { return require(id, false); }

    @Transactional
    public EvaluationDataset create(String name, DatasetDraft draft) {
        validateName(name); validateMembers(draft);
        var dataset = new EvaluationDataset(); dataset.setName(name); dataset.setDatasetType(draft.type().name());
        dataset.setDraftConfigJson(write(draft)); dataset.setDraftRevision(1); dataset.setStatus("ACTIVE");
        dataset.setOwnerGroupId(scope.groupId()); datasets.insert(dataset); return dataset;
    }

    @Transactional
    public EvaluationDataset update(String id, int expectedRevision, String name, DatasetDraft draft) {
        var dataset = require(id, true); revision(dataset, expectedRevision); validateName(name); validateMembers(draft);
        dataset.setName(name); dataset.setDatasetType(draft.type().name()); dataset.setDraftConfigJson(write(draft));
        dataset.setDraftRevision(expectedRevision + 1); datasets.updateById(dataset); return dataset;
    }

    /** Atomic, idempotent freeze of exact reviewed member versions. */
    @Transactional
    public EvaluationDatasetVersion freeze(String id, int expectedRevision) {
        var dataset = require(id, true); revision(dataset, expectedRevision);
        if (!"ACTIVE".equals(dataset.getStatus())) throw IqcException.invalidState("停用数据集不能冻结新版本");
        var existing = versions.selectOne(Wrappers.<EvaluationDatasetVersion>lambdaQuery()
                .eq(EvaluationDatasetVersion::getDatasetId, id).eq(EvaluationDatasetVersion::getSourceDraftRevision, expectedRevision));
        if (existing != null) { validateVersion(existing); return existing; }
        DatasetDraft draft = read(dataset.getDraftConfigJson(), DatasetDraft.class);
        var approved = new LinkedHashMap<String, DatasetSnapshot.Member>();
        long memberBytes = 0;
        // Consistent sample lock order prevents competing datasets from reversing lock acquisition.
        for (var member : draft.members().stream().sorted(Comparator.comparing(DatasetDraft.Member::sampleId)).toList()) {
            var version = goldSamples.approvedVersionForFreeze(member.sampleId(), member.versionNo());
            memberBytes += version.getSnapshotJson().getBytes(StandardCharsets.UTF_8).length;
            if (memberBytes > MAX_SNAPSHOT_BYTES) throw IqcException.invalidArgument("数据集成员快照总量超过64MiB");
            var gold = goldSamples.read(version.getSnapshotJson(), GoldConversation.class);
            approved.put(member.sampleId(), new DatasetSnapshot.Member(member.sampleId(), member.versionNo(),
                    version.getSourceDraftRevision(), version.getContentHash(), gold));
        }
        DatasetSnapshot snapshot;
        try {
            snapshot = new DatasetSnapshot(DatasetSnapshot.SCHEMA, id, dataset.getName(), draft.type(), draft.description(),
                    draft.members().stream().map(member -> approved.get(member.sampleId())).toList());
        } catch (IllegalArgumentException exception) { throw IqcException.invalidArgument("数据集冻结内容无效：" + exception.getMessage(), exception); }
        var version = new EvaluationDatasetVersion(); version.setDatasetId(id);
        version.setVersionNo(dataset.getActiveVersion() == null ? 1 : dataset.getActiveVersion() + 1);
        version.setSourceDraftRevision(expectedRevision); version.setSampleCount(snapshot.members().size());
        version.setSnapshotJson(write(snapshot)); version.setContentHash(PipelineAssetService.hash(version.getSnapshotJson()));
        versions.insert(version); dataset.setActiveVersion(version.getVersionNo()); datasets.updateById(dataset); return version;
    }

    @Transactional
    public EvaluationDataset availability(String id, boolean enabled) {
        var dataset = require(id, true); dataset.setStatus(enabled ? "ACTIVE" : "DISABLED"); datasets.updateById(dataset); return dataset;
    }

    /** Metadata only: listing versions must not return many complete 64MiB snapshots. */
    public IqcPage<EvaluationDatasetVersion> history(String id, long current, long size) {
        require(id, false); pagination(current, size);
        var query = Wrappers.<EvaluationDatasetVersion>lambdaQuery().eq(EvaluationDatasetVersion::getDatasetId, id)
                .select(EvaluationDatasetVersion.class, field -> !"snapshotJson".equals(field.getProperty()))
                .orderByDesc(EvaluationDatasetVersion::getVersionNo);
        return IqcPage.from(versions.selectPage(new Page<>(current, size), query));
    }

    /** Historical read checks integrity without consulting mutable sample or conversation records. */
    public EvaluationDatasetVersion version(String id, int versionNo) {
        require(id, false);
        if (versionNo < 1) throw IqcException.invalidArgument("数据集版本必须为正整数");
        var version = versions.selectOne(Wrappers.<EvaluationDatasetVersion>lambdaQuery()
                .eq(EvaluationDatasetVersion::getDatasetId, id).eq(EvaluationDatasetVersion::getVersionNo, versionNo));
        if (version == null) throw IqcException.notFound("数据集冻结版本不存在");
        validateVersion(version); return version;
    }
    /** Trusted task worker boundary; API authorization must come from the owning task before calling this. */
    public EvaluationDatasetVersion versionForExecution(String id, int versionNo, String expectedHash, boolean requireActive) {
        if (requireActive) {
            var dataset = datasets.selectById(id);
            if (dataset == null || !"ACTIVE".equals(dataset.getStatus())) throw IqcException.invalidState("评测数据集不存在或已停用");
        }
        var version = versions.selectOne(Wrappers.<EvaluationDatasetVersion>lambdaQuery()
                .eq(EvaluationDatasetVersion::getDatasetId, id).eq(EvaluationDatasetVersion::getVersionNo, versionNo));
        if (version == null || !java.util.Objects.equals(expectedHash, version.getContentHash()))
            throw IqcException.invalidState("评测冻结数据集版本不存在或摘要不一致");
        validateVersion(version); return version;
    }
    private void validateVersion(EvaluationDatasetVersion version) {
        DatasetSnapshot snapshot = read(version.getSnapshotJson(), DatasetSnapshot.class);
        if (!PipelineAssetService.hash(version.getSnapshotJson()).equals(version.getContentHash()))
            throw IqcException.invalidState("数据集版本摘要不一致");
        if (!version.getDatasetId().equals(snapshot.datasetId()) || version.getSampleCount() != snapshot.members().size())
            throw IqcException.invalidState("数据集版本身份或样本数不一致");
        for (var member : snapshot.members())
            if (!PipelineAssetService.hash(write(member.gold())).equals(member.contentHash()))
                throw IqcException.invalidState("数据集成员金标摘要不一致");
    }

    private void validateMembers(DatasetDraft draft) {
        if (draft == null) throw IqcException.invalidArgument("数据集草稿不能为空");
        // Every reference is scoped immediately; freeze revalidates exact approvals and embeds the snapshots.
        for (var member : draft.members()) goldSamples.approvedVersion(member.sampleId(), member.versionNo());
    }
    private EvaluationDataset require(String id, boolean lock) {
        if (id == null || id.isBlank()) throw IqcException.invalidArgument("数据集ID不能为空");
        var query = Wrappers.<EvaluationDataset>lambdaQuery().eq(EvaluationDataset::getId, id);
        if (lock) query.last("FOR UPDATE");
        var dataset = datasets.selectOne(query);
        if (dataset == null) throw IqcException.notFound("数据集不存在");
        if (!scope.canView(dataset.getCreatedBy(), dataset.getOwnerGroupId())) throw IqcException.accessDenied("无权访问数据集");
        return dataset;
    }
    private void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 128) throw IqcException.invalidArgument("数据集名称必须为1–128字");
    }
    private void revision(EvaluationDataset dataset, int expected) {
        if (expected < 1 || dataset.getDraftRevision() != expected) throw IqcException.invalidState("数据集草稿修订已变化");
    }
    private void pagination(long current, long size) {
        if (current < 1 || size < 1 || size > 100) throw IqcException.invalidArgument("无效数据集分页");
    }
    private String write(Object value) {
        try {
            String body = json.writeValueAsString(value);
            if (body.getBytes(StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES) throw IqcException.invalidArgument("数据集快照超过64MiB");
            return body;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw IqcException.invalidArgument("无法冻结数据集", exception); }
    }
    public <T> T read(String body, Class<T> type) {
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES)
            throw IqcException.invalidState("数据集快照为空或超过限制");
        try {
            T value = json.readValue(body, type);
            if (value == null) throw IqcException.invalidState("数据集快照不能为空值");
            return value;
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw IqcException.invalidState("数据集快照格式无效"); }
    }
}
