package io.github.opensabre.iqc.scheme;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.scheme.dao.InspectionSchemeMapper;
import io.github.opensabre.iqc.scheme.dao.InspectionSchemeVersionMapper;
import io.github.opensabre.iqc.scheme.model.InspectionScheme;
import io.github.opensabre.iqc.scheme.model.InspectionSchemeVersion;
import io.github.opensabre.iqc.shared.IqcDataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Owns scheme drafts and immutable releases without duplicating rule or Agent persistence. */
@Service
@RequiredArgsConstructor
public class InspectionSchemeService {
    private final InspectionSchemeMapper schemes;
    private final InspectionSchemeVersionMapper versions;
    private final SchemeDependencyResolver dependencies;
    private final IqcDataScope scope;
    private final ObjectMapper mapper;

    /** Creates one editable standard. Publication and use are separate permission-controlled actions. */
    @Transactional
    public InspectionScheme create(DraftRequest request) {
        if (request != null && (request.sourceSchemeId() != null || request.sourceVersionNo() != null))
            return createDerived(request);
        validate(request);
        return insertDraft(request, null);
    }

    private InspectionScheme createDerived(DraftRequest request) {
        validateIdentity(request);
        if (request.sourceSchemeId() == null || request.sourceSchemeId().isBlank()
                || request.sourceSchemeId().length() > 64 || request.sourceVersionNo() == null || request.sourceVersionNo() < 1
                || request.businessScene() != null || request.definition() != null)
            throw IqcException.invalidArgument("派生草稿必须指定来源方案与正版本号，不能提交可变配置");
        InspectionScheme source = get(request.sourceSchemeId());
        if (!"ACTIVE".equals(source.getStatus())) throw IqcException.invalidState("已停用方案不能作为新派生来源");
        InspectionSchemeVersion released = versions.selectOne(Wrappers.<InspectionSchemeVersion>lambdaQuery()
                .eq(InspectionSchemeVersion::getSchemeId, request.sourceSchemeId())
                .eq(InspectionSchemeVersion::getVersionNo, request.sourceVersionNo()));
        if (released == null) throw IqcException.notFound("来源业务方案发布版本不存在");
        if (Boolean.TRUE.equals(released.getArchived())) throw IqcException.invalidState("已归档发布版本不能作为新派生来源");
        if (!request.sourceSchemeId().equals(released.getSchemeId()) || released.getVersionNo() == null
                || released.getVersionNo() != request.sourceVersionNo() || released.getSnapshotJson() == null
                || !contentHash(released.getSnapshotJson()).equals(released.getContentHash()))
            throw IqcException.invalidState("来源发布版本快照校验失败");
        ReleaseSnapshot snapshot = read(released.getSnapshotJson(), ReleaseSnapshot.class);
        if (snapshot == null || snapshot.definition() == null || snapshot.dependencies() == null
                || snapshot.name() == null || snapshot.name().isBlank() || snapshot.code() == null || snapshot.code().isBlank()
                || snapshot.businessScene() == null || snapshot.businessScene().isBlank())
            throw IqcException.invalidState("来源发布版本快照无效");
        DraftRequest copied = new DraftRequest(request.name(), request.code(),
                request.description() == null ? snapshot.description() : request.description(),
                snapshot.businessScene(), snapshot.definition());
        validate(copied);
        var lineage = new Lineage(source.getId(), snapshot.name(), snapshot.code(), released.getVersionNo(), released.getContentHash());
        return insertDraft(copied, lineage);
    }

    private InspectionScheme insertDraft(DraftRequest request, Lineage lineage) {
        InspectionScheme scheme = new InspectionScheme();
        apply(scheme, request); scheme.setDraftRevision(1); scheme.setActivePublishedVersion(null);
        scheme.setStatus("ACTIVE"); scheme.setCreatedBy(scope.owner()); scheme.setOwnerGroupId(scope.groupId());
        if (lineage != null) {
            scheme.setSourceSchemeId(lineage.schemeId()); scheme.setSourceSchemeName(lineage.name());
            scheme.setSourceSchemeCode(lineage.code()); scheme.setSourceVersionNo(lineage.versionNo());
            scheme.setSourceContentHash(lineage.contentHash());
        }
        schemes.insert(scheme);
        return scheme;
    }

    /** Serializes edits and publishing on the same row; stale browser revisions cannot overwrite changes. */
    @Transactional
    public InspectionScheme revise(String id, int expectedRevision, DraftRequest request) {
        if (request != null && (request.sourceSchemeId() != null || request.sourceVersionNo() != null))
            throw IqcException.invalidArgument("方案来源只能在创建派生草稿时指定");
        validate(request);
        InspectionScheme scheme = locked(id); requireRevision(scheme, expectedRevision);
        if (!scheme.getCode().equals(request.code())) throw IqcException.invalidArgument("方案编码不能修改");
        apply(scheme, request); scheme.setDraftRevision(scheme.getDraftRevision() + 1);
        schemes.updateById(scheme);
        return scheme;
    }

    /** Validates dependencies and returns a publication preview without changing the active version. */
    public ReleaseSnapshot preview(String id, int expectedRevision) {
        return preview(id, expectedRevision, null);
    }

    /** Resolve only approved alternatives; previews validate every variant's published dependencies. */
    public ReleaseSnapshot preview(String id, int expectedRevision, String variantCode) {
        InspectionScheme scheme = get(id); requireRevision(scheme, expectedRevision);
        if (!"ACTIVE".equals(scheme.getStatus())) throw IqcException.invalidState("业务方案已停用，请恢复后检查或试跑");
        return resolve(scheme, variantCode);
    }

    /** Changes availability under the publication lock, preserving every release and task snapshot. */
    @Transactional
    public InspectionScheme changeAvailability(String id, int expectedRevision, boolean enabled) {
        if (expectedRevision < 1) throw IqcException.invalidArgument("必须指定有效的方案修订号");
        var scheme = locked(id);
        String target = enabled ? "ACTIVE" : "DISABLED";
        // A retry of this exact transition is harmless; a later edit or toggle must not be overwritten.
        if (scheme.getDraftRevision() != null && (long) scheme.getDraftRevision() == (long) expectedRevision + 1
                && target.equals(scheme.getStatus())) return scheme;
        requireRevision(scheme, expectedRevision);
        if (!"ACTIVE".equals(scheme.getStatus()) && !"DISABLED".equals(scheme.getStatus()))
            throw IqcException.invalidState("方案状态无效，不能切换可用性");
        if (target.equals(scheme.getStatus())) return scheme;
        if (expectedRevision == Integer.MAX_VALUE) throw IqcException.invalidState("方案修订号已达上限");
        scheme.setStatus(target);
        // Conservatively invalidate pre-transition trial acknowledgements without modifying their content.
        scheme.setDraftRevision(expectedRevision + 1);
        schemes.updateById(scheme);
        return scheme;
    }

    /** Archives only superseded releases; immutable snapshots and the active version pointer remain untouched. */
    @Transactional
    public InspectionSchemeVersion changeVersionArchive(String id, int expectedRevision, int versionNo, boolean archived) {
        if (expectedRevision < 1 || versionNo < 1) throw IqcException.invalidArgument("必须指定有效的方案修订号和发布版本号");
        InspectionScheme scheme = locked(id);
        requireRevision(scheme, expectedRevision);
        Integer current = scheme.getActivePublishedVersion();
        if (current == null || versionNo >= current)
            throw IqcException.invalidState("只能归档或恢复已被新版本取代的发布版本");
        InspectionSchemeVersion version = versions.selectOne(Wrappers.<InspectionSchemeVersion>lambdaQuery()
                .eq(InspectionSchemeVersion::getSchemeId, id).eq(InspectionSchemeVersion::getVersionNo, versionNo));
        if (version == null) throw IqcException.notFound("业务方案发布版本不存在");
        verifiedSnapshot(id, version);
        if (Boolean.TRUE.equals(version.getArchived()) == archived) return version;
        version.setArchived(archived);
        versions.updateById(version);
        return version;
    }

    /** Checks trial content again while holding the draft lock; a concurrent revision cannot reuse stale verification. */
    @Transactional
    public InspectionSchemeVersion publishValidated(String id, int expectedRevision, String trialTaskId, String verifiedHash) {
        if (trialTaskId == null || trialTaskId.isBlank() || verifiedHash == null || verifiedHash.isBlank())
            throw IqcException.invalidState("发布必须提供已验证的试跑记录");
        InspectionScheme scheme = locked(id); requireRevision(scheme, expectedRevision);
        if (!"ACTIVE".equals(scheme.getStatus())) throw IqcException.invalidState("停用方案不能发布");
        read(scheme.getDraftConfigJson(), SchemeDefinition.class).requireExecutable();
        var existing = versions.selectOne(Wrappers.<InspectionSchemeVersion>lambdaQuery()
                .eq(InspectionSchemeVersion::getSchemeId, id)
                .eq(InspectionSchemeVersion::getSourceDraftRevision, expectedRevision));
        if (existing != null) return existing;
        String json = write(resolve(scheme).forTaskSnapshot());
        String hash = contentHash(json);
        if (!verifiedHash.equals(hash)) throw IqcException.invalidState("方案或依赖已变化，请重新试跑");
        var version = new InspectionSchemeVersion();
        version.setSchemeId(id); version.setVersionNo(scheme.getActivePublishedVersion() == null ? 1 : scheme.getActivePublishedVersion() + 1);
        version.setSourceDraftRevision(expectedRevision); version.setSnapshotJson(json); version.setContentHash(hash);
        version.setSourceTrialTaskId(trialTaskId);
        versions.insert(version);
        scheme.setActivePublishedVersion(version.getVersionNo());
        schemes.updateById(scheme);
        return version;
    }

    /** Reads a specific immutable version, never substituting a newer draft or release. */
    public InspectionSchemeVersion published(String id, int versionNo) {
        InspectionScheme scheme = get(id);
        if (!"ACTIVE".equals(scheme.getStatus())) throw IqcException.invalidState("业务方案已停用");
        var version = versions.selectOne(Wrappers.<InspectionSchemeVersion>lambdaQuery()
                .eq(InspectionSchemeVersion::getSchemeId, id).eq(InspectionSchemeVersion::getVersionNo, versionNo));
        if (version == null) throw IqcException.notFound("业务方案发布版本不存在");
        if (Boolean.TRUE.equals(version.getArchived())) throw IqcException.notFound("业务方案发布版本已归档，不可用于新任务");
        return version;
    }

    /** Reads frozen standards even after disablement; never resolves current detector/Agent drafts. */
    public VersionHistory history(String id, Integer beforeVersion) {
        get(id); // Authorization precedes any version query, including empty histories.
        if (beforeVersion != null && beforeVersion < 1) throw IqcException.invalidArgument("发布历史游标必须为正版本号");
        var query = Wrappers.<InspectionSchemeVersion>lambdaQuery()
                .eq(InspectionSchemeVersion::getSchemeId, id)
                .lt(beforeVersion != null, InspectionSchemeVersion::getVersionNo, beforeVersion)
                .orderByDesc(InspectionSchemeVersion::getVersionNo).last("LIMIT 21");
        var found = versions.selectList(query);
        var page = found.stream().limit(20).map(version -> {
            if (!id.equals(version.getSchemeId()) || version.getVersionNo() == null || version.getVersionNo() < 1
                    || version.getSnapshotJson() == null
                    || !contentHash(version.getSnapshotJson()).equals(version.getContentHash()))
                throw IqcException.invalidState("发布历史快照校验失败");
            var snapshot = read(version.getSnapshotJson(), ReleaseSnapshot.class);
            if (snapshot == null || snapshot.definition() == null || snapshot.dependencies() == null
                    || snapshot.name() == null || snapshot.name().isBlank())
                throw IqcException.invalidState("发布历史快照无效");
            return new ReleasedVersion(version.getVersionNo(), version.getSourceDraftRevision(),
                    version.getSourceTrialTaskId(), version.getContentHash(), snapshot, Boolean.TRUE.equals(version.getArchived()));
        }).toList();
        return new VersionHistory(page, found.size() > 20 ? page.getLast().versionNo() : null);
    }

    /** Read-only history for authorized template users; disabled schemes cannot seed new tasks. */
    public PublishedVersionHistory publishedHistory(String id, Integer beforeVersion) {
        InspectionScheme scheme = get(id);
        if (!"ACTIVE".equals(scheme.getStatus())) throw IqcException.notFound("可用业务模板不存在");
        if (beforeVersion != null && beforeVersion < 1) throw IqcException.invalidArgument("发布历史游标必须为正版本号");
        var query = Wrappers.<InspectionSchemeVersion>lambdaQuery()
                .eq(InspectionSchemeVersion::getSchemeId, id)
                .eq(InspectionSchemeVersion::getArchived, false)
                .lt(beforeVersion != null, InspectionSchemeVersion::getVersionNo, beforeVersion)
                .orderByDesc(InspectionSchemeVersion::getVersionNo).last("LIMIT 21");
        var found = versions.selectList(query);
        var page = found.stream().limit(20).map(version -> {
            ReleaseSnapshot snapshot = verifiedSnapshot(id, version);
            return new PublishedTemplate(id, version.getVersionNo(), version.getContentHash(), snapshot);
        }).toList();
        return new PublishedVersionHistory(page, found.size() > 20 ? page.getLast().versionNo() : null);
    }

    private ReleaseSnapshot verifiedSnapshot(String schemeId, InspectionSchemeVersion version) {
        if (!schemeId.equals(version.getSchemeId()) || version.getVersionNo() == null || version.getVersionNo() < 1
                || version.getSnapshotJson() == null
                || !contentHash(version.getSnapshotJson()).equals(version.getContentHash()))
            throw IqcException.invalidState("发布历史快照校验失败");
        ReleaseSnapshot snapshot = read(version.getSnapshotJson(), ReleaseSnapshot.class);
        if (snapshot == null || snapshot.definition() == null || snapshot.dependencies() == null
                || snapshot.name() == null || snapshot.name().isBlank())
            throw IqcException.invalidState("发布历史快照无效");
        return snapshot;
    }

    /** Lists visible drafts for experts; ordinary callers should use the released template list. */
    public List<InspectionScheme> list() {
        var query = Wrappers.<InspectionScheme>lambdaQuery().orderByDesc(InspectionScheme::getCreatedTime);
        if (!scope.canViewAll()) {
            String owner = scope.owner();
            String groupId = scope.groupId();
            query.and(q -> q.eq(InspectionScheme::getCreatedBy, owner)
                    .or(groupId != null, group -> group.eq(InspectionScheme::getOwnerGroupId, groupId)));
        }
        return schemes.selectList(query);
    }

    /** Uses released names and content rather than exposing draft edits to ordinary template users. */
    public List<PublishedTemplate> templates() {
        return list().stream().filter(s -> "ACTIVE".equals(s.getStatus()) && s.getActivePublishedVersion() != null)
                .map(s -> {
                    var version = published(s.getId(), s.getActivePublishedVersion());
                    var snapshot = read(version.getSnapshotJson(), ReleaseSnapshot.class);
                    return new PublishedTemplate(s.getId(), version.getVersionNo(), version.getContentHash(), snapshot);
                }).toList();
    }

    /** Enforces the same owner/team data scope as existing IQC business records. */
    public InspectionScheme get(String id) {
        InspectionScheme scheme = schemes.selectById(id); authorize(scheme); return scheme;
    }

    private InspectionScheme locked(String id) {
        InspectionScheme scheme = schemes.selectOne(Wrappers.<InspectionScheme>lambdaQuery()
                .eq(InspectionScheme::getId, id).last("FOR UPDATE"));
        authorize(scheme); return scheme;
    }

    private void authorize(InspectionScheme scheme) {
        if (scheme == null) throw IqcException.notFound("业务方案不存在");
        if (!scope.canView(scheme.getCreatedBy(), scheme.getOwnerGroupId())) throw IqcException.accessDenied("无权访问该业务方案");
    }

    private void requireRevision(InspectionScheme scheme, int expected) {
        if (scheme.getDraftRevision() == null || scheme.getDraftRevision() != expected)
            throw IqcException.invalidState("方案已被修改，请刷新后重试");
    }

    private ReleaseSnapshot resolve(InspectionScheme scheme) {
        return resolve(scheme, null);
    }

    private ReleaseSnapshot resolve(InspectionScheme scheme, String variantCode) {
        SchemeDefinition definition = read(scheme.getDraftConfigJson(), SchemeDefinition.class);
        var variants = definition.executionVariants();
        if (variants != null) {
            variants.select(variantCode); // Reject unknown selections before resolving any dependencies.
            var selected = variants.select(null);
            SchemeDependencyResolver.Dependencies selectedDependencies = null;
            SchemeDefinition selectedDefinition = null;
            var variantDependencies = new java.util.LinkedHashMap<String, SchemeDependencyResolver.Dependencies>();
            // A valid recommendation must not conceal a broken allowed alternative.
            for (var variant : variants.variants()) {
                var expanded = variants.expand(definition, variant.code());
                var approved = new SchemeDefinition(expanded.schemaVersion(), expanded.items(), expanded.agent(),
                        expanded.scoring(), expanded.runLimits(), expanded.labels(), variants);
                var checked = dependencies.previewRoutes(approved);
                variantDependencies.put(variant.code(), checked);
                if (variant.code().equals(selected.code())) {
                    selectedDefinition = approved;
                    selectedDependencies = checked;
                }
            }
            return new ReleaseSnapshot(scheme.getName(), scheme.getCode(), scheme.getDescription(), scheme.getBusinessScene(),
                    selectedDefinition, selectedDependencies, selected.code(), variantDependencies).selectVariant(variantCode);
        }
        if (variantCode != null) throw IqcException.invalidArgument("模板未配置允许策略变体");
        return new ReleaseSnapshot(scheme.getName(), scheme.getCode(), scheme.getDescription(), scheme.getBusinessScene(),
                definition, definition.items().stream().anyMatch(item -> item.execution() != null)
                        ? dependencies.previewRoutes(definition) : dependencies.resolve(definition));
    }

    private void validate(DraftRequest request) {
        validateIdentity(request);
        if (request.definition() == null) throw IqcException.invalidArgument("方案名称、编码和配置无效");
        if (request.businessScene() == null || request.businessScene().isBlank() || request.businessScene().length() > 100)
            throw IqcException.invalidArgument("业务场景必须为 1 到 100 个字符");
    }

    private void validateIdentity(DraftRequest request) {
        if (request == null || request.name() == null || request.name().isBlank() || request.name().length() > 100
                || request.code() == null || !request.code().matches("[A-Za-z0-9_-]{1,64}"))
            throw IqcException.invalidArgument("方案名称和编码无效");
        if (request.description() != null && request.description().length() > 1000)
            throw IqcException.invalidArgument("方案说明不能超过 1000 字符");
    }

    private void apply(InspectionScheme scheme, DraftRequest request) {
        scheme.setName(request.name().trim()); scheme.setCode(request.code()); scheme.setDescription(request.description());
        scheme.setBusinessScene(request.businessScene().trim()); scheme.setDraftConfigJson(write(request.definition()));
    }

    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw IqcException.invalidArgument("业务方案配置无法序列化", exception); }
    }

    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw IqcException.invalidState("业务方案快照无效"); }
    }

    /** Stable fingerprint shared by trial binding, publication validation, and task snapshot verification. */
    public static String contentHash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public record DraftRequest(String name, String code, String description, String businessScene, SchemeDefinition definition,
                               String sourceSchemeId, Integer sourceVersionNo) {
        public DraftRequest(String name, String code, String description, String businessScene, SchemeDefinition definition) {
            this(name, code, description, businessScene, definition, null, null);
        }
    }
    private record Lineage(String schemeId, String name, String code, int versionNo, String contentHash) { }
    public record ReleaseSnapshot(String name, String code, String description, String businessScene,
                                  SchemeDefinition definition, SchemeDependencyResolver.Dependencies dependencies,
                                  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                                  String selectedVariantCode,
                                  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                                  java.util.Map<String, SchemeDependencyResolver.Dependencies> variantDependencies) {
        /** Preserve JSON order and reject incomplete dependency sets; absent maps retain historical hashes. */
        public ReleaseSnapshot {
            if (variantDependencies != null) {
                if (definition == null || definition.executionVariants() == null || selectedVariantCode == null)
                    throw IqcException.invalidArgument("变体依赖快照缺少允许变体或选中编码");
                var codes = definition.executionVariants().variants().stream()
                        .map(SchemeExecutionVariants.Variant::code).collect(java.util.stream.Collectors.toSet());
                if (!codes.equals(variantDependencies.keySet()) || variantDependencies.values().stream().anyMatch(java.util.Objects::isNull)
                        || !java.util.Objects.equals(dependencies, variantDependencies.get(selectedVariantCode)))
                    throw IqcException.invalidArgument("变体依赖快照不完整或与选中变体不一致");
                definition.executionVariants().select(selectedVariantCode);
                variantDependencies = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(variantDependencies));
            }
        }
        public ReleaseSnapshot(String name, String code, String description, String businessScene,
                               SchemeDefinition definition, SchemeDependencyResolver.Dependencies dependencies,
                               String selectedVariantCode) {
            this(name, code, description, businessScene, definition, dependencies, selectedVariantCode, null);
        }
        public ReleaseSnapshot(String name, String code, String description, String businessScene,
                               SchemeDefinition definition, SchemeDependencyResolver.Dependencies dependencies) {
            this(name, code, description, businessScene, definition, dependencies, null);
        }

        /** Select only an approved frozen alternative; historical snapshots cannot invent missing dependencies. */
        public ReleaseSnapshot selectVariant(String code) {
            var variants = definition == null ? null : definition.executionVariants();
            if (variants == null) {
                if (code != null) throw IqcException.invalidArgument("模板未配置允许策略变体");
                return this;
            }
            String selected = variants.select(code).code();
            if (selected.equals(selectedVariantCode)) return this;
            if (variantDependencies == null)
                throw IqcException.invalidState("历史模板未冻结全部变体依赖，不能切换策略");
            var expanded = variants.expand(definition, selected);
            var selectedDefinition = new SchemeDefinition(expanded.schemaVersion(), expanded.items(), expanded.agent(),
                    expanded.scoring(), expanded.runLimits(), expanded.labels(), variants);
            return new ReleaseSnapshot(name, this.code, description, businessScene, selectedDefinition,
                    variantDependencies.get(selected), selected, variantDependencies);
        }

        /** Normalize the execution marker while preserving every frozen release field. */
        public ReleaseSnapshot forTaskSnapshot() {
            var normalized = definition.forTaskSnapshot();
            return normalized == definition ? this : new ReleaseSnapshot(name, code, description, businessScene,
                    normalized, dependencies, selectedVariantCode, variantDependencies);
        }
    }
    public record PublishedTemplate(String schemeId, int versionNo, String contentHash, ReleaseSnapshot snapshot) { }
    public record PublishedVersionHistory(List<PublishedTemplate> versions, Integer nextBeforeVersion) { }
    public record ReleasedVersion(int versionNo, Integer sourceDraftRevision, String sourceTrialTaskId,
                                  String contentHash, ReleaseSnapshot snapshot, boolean archived) {
        public ReleasedVersion(int versionNo, Integer sourceDraftRevision, String sourceTrialTaskId,
                               String contentHash, ReleaseSnapshot snapshot) {
            this(versionNo, sourceDraftRevision, sourceTrialTaskId, contentHash, snapshot, false);
        }
    }
    public record VersionHistory(List<ReleasedVersion> versions, Integer nextBeforeVersion) { }
}
