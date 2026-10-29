package io.github.opensabre.iqc.finding;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.finding.dao.FindingCatalogMapper;
import io.github.opensabre.iqc.finding.dao.FindingCatalogVersionMapper;
import io.github.opensabre.iqc.finding.model.FindingCatalog;
import io.github.opensabre.iqc.finding.model.FindingCatalogVersion;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.shared.IqcPage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Versioned cross-engine facts. Published bodies are immutable; editing never replaces an active version. */
@Service
@RequiredArgsConstructor
public class FindingCatalogService {
    private final FindingCatalogMapper catalogs;
    private final FindingCatalogVersionMapper versions;
    private final ObjectMapper json;
    private final IqcDataScope scope;

    public IqcPage<FindingCatalog> page(long current, long size) {
        if (current < 1 || size < 1 || size > 100) throw IqcException.invalidArgument("无效目录分页");
        var query = Wrappers.<FindingCatalog>lambdaQuery();
        if (!scope.canViewAll()) {
            String group = scope.groupId();
            query.and(q -> { q.eq(FindingCatalog::getCreatedBy, scope.owner()); if (group != null) q.or().eq(FindingCatalog::getOwnerGroupId, group); });
        }
        query.orderByDesc(FindingCatalog::getCreatedTime).orderByAsc(FindingCatalog::getId);
        return IqcPage.from(catalogs.selectPage(new Page<>(current, size), query));
    }

    public FindingCatalog get(String id) { return require(id, false); }

    @Transactional
    public FindingCatalog create(FindingDefinition definition) {
        if (definition == null) throw IqcException.invalidArgument("事实定义不能为空");
        var catalog = new FindingCatalog();
        catalog.setCode(definition.code()); catalog.setName(definition.name()); catalog.setCategory(definition.category());
        catalog.setDraftConfigJson(write(definition)); catalog.setDraftRevision(1); catalog.setStatus("ACTIVE");
        catalog.setOwnerGroupId(scope.groupId()); catalogs.insert(catalog); return catalog;
    }

    @Transactional
    public FindingCatalog update(String id, int expectedRevision, FindingDefinition definition) {
        var catalog = require(id, true);
        if (definition == null || !catalog.getCode().equals(definition.code())) throw IqcException.invalidArgument("不能修改稳定事实编码");
        if (catalog.getDraftRevision() != expectedRevision) throw IqcException.invalidState("事实草稿已被其他开发者更新");
        if (catalog.getActivePublishedVersion() != null) {
            var previous = version(catalog, catalog.getActivePublishedVersion());
            if (read(previous.getSnapshotJson()).valueType() != definition.valueType())
                throw IqcException.invalidArgument("已发布事实类型不可更改，请建立新的事实编码");
        }
        catalog.setName(definition.name()); catalog.setCategory(definition.category());
        catalog.setDraftConfigJson(write(definition)); catalog.setDraftRevision(expectedRevision + 1);
        catalogs.updateById(catalog); return catalog;
    }

    @Transactional
    public FindingCatalogVersion publish(String id, int expectedRevision) {
        var catalog = require(id, true);
        if (!"ACTIVE".equals(catalog.getStatus())) throw IqcException.invalidState("已停用事实定义不能发布");
        if (catalog.getDraftRevision() != expectedRevision) throw IqcException.invalidState("发布修订已过期");
        var existing = versions.selectOne(Wrappers.<FindingCatalogVersion>lambdaQuery()
                .eq(FindingCatalogVersion::getCatalogId, id).eq(FindingCatalogVersion::getSourceDraftRevision, expectedRevision));
        if (existing != null) return existing;
        read(catalog.getDraftConfigJson());
        var version = new FindingCatalogVersion(); version.setCatalogId(id);
        version.setVersionNo(catalog.getActivePublishedVersion() == null ? 1 : catalog.getActivePublishedVersion() + 1);
        version.setSourceDraftRevision(expectedRevision); version.setSnapshotJson(catalog.getDraftConfigJson());
        version.setContentHash(hash(version.getSnapshotJson())); versions.insert(version);
        catalog.setActivePublishedVersion(version.getVersionNo()); catalogs.updateById(catalog); return version;
    }

    @Transactional
    public FindingCatalog setEnabled(String id, boolean enabled) {
        var catalog = require(id, true); catalog.setStatus(enabled ? "ACTIVE" : "DISABLED"); catalogs.updateById(catalog); return catalog;
    }

    public List<FindingCatalogVersion> history(String id) {
        require(id, false);
        return versions.selectList(Wrappers.<FindingCatalogVersion>lambdaQuery()
                .eq(FindingCatalogVersion::getCatalogId, id).orderByDesc(FindingCatalogVersion::getVersionNo).last("LIMIT 100"));
    }
    public FindingDefinition publishedDefinition(String id, int versionNo) {
        var catalog = require(id, false);
        if (!"ACTIVE".equals(catalog.getStatus())) throw IqcException.invalidState("事实定义已停用");
        var version = version(catalog, versionNo);
        if (!hash(version.getSnapshotJson()).equals(version.getContentHash())) throw IqcException.invalidState("事实版本摘要不一致");
        return read(version.getSnapshotJson());
    }
    /** Trusted worker boundary: only kill switches/version integrity, never a replacement for API authorization. */
    public FindingDefinition publishedDefinitionForExecution(String id, int versionNo) {
        var catalog = catalogs.selectById(id);
        if (catalog == null || !"ACTIVE".equals(catalog.getStatus())) throw IqcException.invalidState("事实定义不存在或已停用");
        var version = version(catalog, versionNo);
        if (!hash(version.getSnapshotJson()).equals(version.getContentHash())) throw IqcException.invalidState("事实版本摘要不一致");
        return read(version.getSnapshotJson());
    }
    private FindingCatalogVersion version(FindingCatalog catalog, int number) {
        var version = versions.selectOne(Wrappers.<FindingCatalogVersion>lambdaQuery()
                .eq(FindingCatalogVersion::getCatalogId, catalog.getId()).eq(FindingCatalogVersion::getVersionNo, number));
        if (version == null) throw IqcException.notFound("事实发布版本不存在");
        return version;
    }
    private FindingCatalog require(String id, boolean lock) {
        var query = Wrappers.<FindingCatalog>lambdaQuery().eq(FindingCatalog::getId, id);
        if (lock) query.last("FOR UPDATE");
        var catalog = catalogs.selectOne(query);
        if (catalog == null) throw IqcException.notFound("事实定义不存在");
        if (!scope.canView(catalog.getCreatedBy(), catalog.getOwnerGroupId())) throw IqcException.accessDenied("无权访问事实定义");
        return catalog;
    }
    private String write(FindingDefinition value) {
        try { return json.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw IqcException.invalidArgument("无效事实定义", exception); }
    }
    private FindingDefinition read(String value) {
        try { return json.readValue(value, FindingDefinition.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw IqcException.invalidState("事实版本格式无效"); }
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
