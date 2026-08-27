package io.github.opensabre.iqc.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.opensabre.iqc.agent.dao.QualityAgentMapper;
import io.github.opensabre.iqc.agent.model.QualityAgent;
import io.github.opensabre.iqc.agent.dao.QualityAgentVersionMapper;
import io.github.opensabre.iqc.agent.model.QualityAgentVersion;
import io.github.opensabre.iqc.governance.IqcException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class QualityAgentService {
    private final QualityAgentMapper mapper;
    private final QualityAgentVersionMapper versionMapper;
    private final ObjectMapper objectMapper;
    private final AgentAssetReferenceValidator assetReferenceValidator;

    public List<QualityAgent> list() { return mapper.selectList(Wrappers.<QualityAgent>lambdaQuery().orderByDesc(QualityAgent::getCreatedTime)); }

    @Transactional
    public QualityAgent create(String name, String code, String description, String configJson) {
        if (name == null || name.isBlank() || code == null || code.isBlank()) throw IqcException.invalidArgument("Agent 名称和编码不能为空");
        QualityAgent agent = new QualityAgent();
        agent.setName(name.trim()); agent.setCode(code.trim()); agent.setDescription(description); agent.setConfigJson(normalizeConfiguration(configJson)); agent.setStatus("DRAFT");
        mapper.insert(agent);
        agent.setVersionNo(1); mapper.updateById(agent); versionMapper.insert(toVersion(agent, 1, "DRAFT"));
        return agent;
    }

    @Transactional
    public QualityAgent submit(String id) {
        QualityAgent agent = mapper.selectById(id);
        if (agent == null) throw IqcException.notFound("Agent 不存在: " + id);
        QualityAgentVersion version = latestVersion(id);
        if (version == null) { version = toVersion(agent, agent.getVersionNo() == null ? 1 : agent.getVersionNo(), "DRAFT"); versionMapper.insert(version); }
        if ("PUBLISHED".equals(version.getStatus())) throw IqcException.invalidState("已发布版本不能重复提交审批");
        version.setConfigJson(snapshotConfiguration(version.getConfigJson()));
        version.setStatus("PENDING_APPROVAL"); versionMapper.updateById(version);
        agent.setStatus("PENDING_APPROVAL"); mapper.updateById(agent);
        return agent;
    }

    @Transactional
    public QualityAgent publish(String id) {
        QualityAgent agent = mapper.selectById(id);
        if (agent == null) throw IqcException.notFound("Agent 不存在: " + id);
        QualityAgentVersion version = latestVersion(id);
        if (version == null || !"PENDING_APPROVAL".equals(version.getStatus())) throw IqcException.invalidState("Agent 必须先提交审批");
        version.setStatus("PUBLISHED"); versionMapper.updateById(version); copyVersion(agent, version); agent.setStatus("PUBLISHED"); mapper.updateById(agent); return agent;
    }

    @Transactional
    public QualityAgent reject(String id) {
        QualityAgent agent = mapper.selectById(id);
        if (agent == null) throw IqcException.notFound("Agent 不存在: " + id);
        QualityAgentVersion version = latestVersion(id);
        if (version == null || !"PENDING_APPROVAL".equals(version.getStatus())) throw IqcException.invalidState("Agent 当前没有待审批版本");
        version.setStatus("REJECTED"); versionMapper.updateById(version);
        agent.setStatus(versions(id).stream().anyMatch(item -> "PUBLISHED".equals(item.getStatus())) ? "PUBLISHED" : "DRAFT");
        mapper.updateById(agent); return agent;
    }

    /**
     * Stops an Agent from being selected by new inspection tasks without changing historical versions.
     */
    @Transactional
    public QualityAgent disable(String id) {
        QualityAgent agent = requireAgent(id);
        if (!"PUBLISHED".equals(agent.getStatus())) {
            throw IqcException.invalidState("只有已发布 Agent 可以停用");
        }
        agent.setStatus("DISABLED");
        mapper.updateById(agent);
        return agent;
    }

    public List<QualityAgentVersion> versions(String agentId) { return versionMapper.selectList(Wrappers.<QualityAgentVersion>lambdaQuery().eq(QualityAgentVersion::getAgentId, agentId).orderByDesc(QualityAgentVersion::getVersionNo)); }

    @Transactional
    public QualityAgentVersion createVersion(String agentId, String name, String code, String description, String configJson) {
        QualityAgent agent = requireAgent(agentId);
        ensureNoOpenVersion(agentId);
        int next = versions(agentId).stream().map(QualityAgentVersion::getVersionNo).filter(java.util.Objects::nonNull).max(Integer::compareTo).orElse(0) + 1;
        QualityAgentVersion version = new QualityAgentVersion(); version.setAgentId(agentId); version.setVersionNo(next); version.setName(normalize(name, agent.getName())); version.setCode(normalize(code, agent.getCode())); version.setDescription(description == null ? agent.getDescription() : description); version.setConfigJson(configJson == null ? agent.getConfigJson() : normalizeConfiguration(configJson)); version.setStatus("DRAFT"); versionMapper.insert(version);
        agent.setStatus("DRAFT"); mapper.updateById(agent);
        return version;
    }

    /**
     * Rolls a historical version forward as a new draft so immutable history remains intact.
     */
    @Transactional
    public QualityAgentVersion rollback(String agentId, int versionNo) {
        QualityAgent agent = requireAgent(agentId);
        ensureNoOpenVersion(agentId);
        QualityAgentVersion source = requireVersion(agentId, versionNo);
        int next = versions(agentId).stream().map(QualityAgentVersion::getVersionNo).filter(java.util.Objects::nonNull).max(Integer::compareTo).orElse(0) + 1;
        QualityAgentVersion rollback = new QualityAgentVersion();
        rollback.setAgentId(agentId); rollback.setVersionNo(next); rollback.setName(source.getName()); rollback.setCode(source.getCode());
        rollback.setDescription(source.getDescription()); rollback.setConfigJson(source.getConfigJson()); rollback.setStatus("DRAFT");
        versionMapper.insert(rollback);
        agent.setStatus("DRAFT"); mapper.updateById(agent);
        return rollback;
    }

    /**
     * Compares two immutable Agent versions, including changed JSON configuration paths.
     */
    public AgentVersionComparison compare(String agentId, int fromVersion, int toVersion) {
        requireAgent(agentId);
        QualityAgentVersion from = requireVersion(agentId, fromVersion);
        QualityAgentVersion to = requireVersion(agentId, toVersion);
        Set<String> changedFields = new LinkedHashSet<>();
        if (!java.util.Objects.equals(from.getName(), to.getName())) changedFields.add("name");
        if (!java.util.Objects.equals(from.getCode(), to.getCode())) changedFields.add("code");
        if (!java.util.Objects.equals(from.getDescription(), to.getDescription())) changedFields.add("description");
        List<String> configPaths = new ArrayList<>();
        compareJson("$", parseJson(from.getConfigJson()), parseJson(to.getConfigJson()), configPaths);
        if (!configPaths.isEmpty()) changedFields.add("configJson");
        return new AgentVersionComparison(fromVersion, toVersion, List.copyOf(changedFields), List.copyOf(configPaths));
    }

    private QualityAgent requireAgent(String id) {
        QualityAgent agent = mapper.selectById(id);
        if (agent == null) throw IqcException.notFound("Agent 不存在: " + id);
        return agent;
    }

    private QualityAgentVersion requireVersion(String agentId, int versionNo) {
        QualityAgentVersion version = versionMapper.selectOne(Wrappers.<QualityAgentVersion>lambdaQuery()
                .eq(QualityAgentVersion::getAgentId, agentId).eq(QualityAgentVersion::getVersionNo, versionNo));
        if (version == null) throw IqcException.notFound("Agent 版本不存在: " + versionNo);
        return version;
    }

    private void ensureNoOpenVersion(String agentId) {
        boolean open = versions(agentId).stream().anyMatch(version -> "DRAFT".equals(version.getStatus()) || "PENDING_APPROVAL".equals(version.getStatus()));
        if (open) throw IqcException.invalidState("请先完成当前草稿或待审批版本");
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private JsonNode parseJson(String value) {
        if (value == null || value.isBlank()) return objectMapper.nullNode();
        try { return objectMapper.readTree(value); }
        catch (Exception exception) { throw IqcException.invalidArgument("Agent 配置不是有效 JSON"); }
    }

    /** Canonicalizes the public Agent schema so comparisons and immutable snapshots stay deterministic. */
    private String normalizeConfiguration(String value) {
        try {
            AgentConfiguration configuration = value == null || value.isBlank()
                    ? AgentConfiguration.defaults() : objectMapper.readValue(value, AgentConfiguration.class);
            configuration = configuration.validated();
            assetReferenceValidator.validate(configuration);
            return objectMapper.writeValueAsString(configuration);
        } catch (IqcException exception) {
            throw exception;
        } catch (Exception exception) {
            throw IqcException.invalidArgument("Agent 配置不是有效的结构化配置");
        }
    }

    /** Captures exact asset versions once when a draft enters approval. */
    private String snapshotConfiguration(String value) {
        try {
            AgentConfiguration configuration = objectMapper.readValue(value, AgentConfiguration.class).validated();
            if (!AgentConfiguration.CURRENT_SCHEMA.equals(configuration.schemaVersion())) return value;
            return objectMapper.writeValueAsString(configuration.withSnapshots(assetReferenceValidator.snapshot(configuration)));
        } catch (IqcException exception) { throw exception; }
        catch (Exception exception) { throw IqcException.invalidArgument("Agent 资产快照生成失败"); }
    }

    private void compareJson(String path, JsonNode from, JsonNode to, List<String> changes) {
        if (java.util.Objects.equals(from, to)) return;
        if (from != null && to != null && from.isObject() && to.isObject()) {
            Set<String> fields = new java.util.TreeSet<>();
            from.fieldNames().forEachRemaining(fields::add); to.fieldNames().forEachRemaining(fields::add);
            fields.forEach(field -> compareJson(path + "." + field, from.get(field), to.get(field), changes));
            return;
        }
        if (from != null && to != null && from.isArray() && to.isArray()) {
            int size = Math.max(from.size(), to.size());
            for (int index = 0; index < size; index++) compareJson(path + "[" + index + "]", from.get(index), to.get(index), changes);
            return;
        }
        changes.add(path);
    }

    private QualityAgentVersion latestVersion(String agentId) { return versions(agentId).stream().findFirst().orElse(null); }
    private QualityAgentVersion toVersion(QualityAgent agent, int versionNo, String status) { QualityAgentVersion version = new QualityAgentVersion(); version.setAgentId(agent.getId()); version.setVersionNo(versionNo); version.setName(agent.getName()); version.setCode(agent.getCode()); version.setDescription(agent.getDescription()); version.setConfigJson(agent.getConfigJson()); version.setStatus(status); return version; }
    private void copyVersion(QualityAgent agent, QualityAgentVersion version) { agent.setName(version.getName()); agent.setCode(version.getCode()); agent.setDescription(version.getDescription()); agent.setConfigJson(version.getConfigJson()); agent.setVersionNo(version.getVersionNo()); }

    public record AgentVersionComparison(int fromVersion, int toVersion, List<String> changedFields,
                                         List<String> changedConfigPaths) { }
}
