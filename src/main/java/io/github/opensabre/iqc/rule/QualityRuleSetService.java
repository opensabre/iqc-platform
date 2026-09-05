package io.github.opensabre.iqc.rule;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.rule.dao.QualityRuleMapper;
import io.github.opensabre.iqc.rule.dao.QualityRuleSetMapper;
import io.github.opensabre.iqc.rule.dao.QualityRuleSetVersionMapper;
import io.github.opensabre.iqc.rule.model.QualityRule;
import io.github.opensabre.iqc.rule.model.QualityRuleSet;
import io.github.opensabre.iqc.rule.model.QualityRuleSetVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Manages ordered, versioned rule sets used as stable task and Agent inputs. */
@Service
@RequiredArgsConstructor
public class QualityRuleSetService {
    private final QualityRuleSetMapper mapper;
    private final QualityRuleSetVersionMapper versionMapper;
    private final QualityRuleMapper ruleMapper;
    private final ObjectMapper objectMapper;

    public List<QualityRuleSet> list() {
        return mapper.selectList(Wrappers.<QualityRuleSet>lambdaQuery().orderByDesc(QualityRuleSet::getCreatedTime));
    }

    public List<QualityRuleSetVersion> versions(String ruleSetId) {
        return versionMapper.selectList(Wrappers.<QualityRuleSetVersion>lambdaQuery()
                .eq(QualityRuleSetVersion::getRuleSetId, ruleSetId).orderByDesc(QualityRuleSetVersion::getVersionNo));
    }

    @Transactional
    public QualityRuleSet create(String name, String code, String description, List<String> ruleIds, String aggregationMode) {
        validateIdentity(name, code); List<String> normalized = validateRules(ruleIds, false);
        QualityRuleSet set = new QualityRuleSet(); set.setName(name.trim()); set.setCode(code.trim());
        set.setDescription(description); set.setRuleIdsJson(write(normalized)); set.setAggregationMode(normalizeMode(aggregationMode));
        set.setVersionNo(1); set.setStatus("DRAFT"); mapper.insert(set); versionMapper.insert(toVersion(set, 1)); return set;
    }

    @Transactional
    public QualityRuleSetVersion createVersion(String id, String name, String description, List<String> ruleIds, String aggregationMode) {
        QualityRuleSet set = require(id); List<String> normalized = validateRules(ruleIds, false);
        int next = versions(id).stream().map(QualityRuleSetVersion::getVersionNo).max(Integer::compareTo).orElse(0) + 1;
        QualityRuleSetVersion version = new QualityRuleSetVersion(); version.setRuleSetId(id); version.setVersionNo(next);
        version.setName(name == null || name.isBlank() ? set.getName() : name.trim()); version.setCode(set.getCode());
        version.setDescription(description); version.setRuleIdsJson(write(normalized)); version.setAggregationMode(normalizeMode(aggregationMode));
        version.setStatus("DRAFT"); versionMapper.insert(version); set.setStatus("DRAFT"); mapper.updateById(set); return version;
    }

    @Transactional
    public QualityRuleSet submit(String id) {
        QualityRuleSet set = require(id); QualityRuleSetVersion version = latest(id);
        if (version == null || !"DRAFT".equals(version.getStatus()) && !"REJECTED".equals(version.getStatus()))
            throw IqcException.invalidState("只有草稿或已驳回版本可以提交审批");
        validateRules(readIds(version.getRuleIdsJson()), true);
        version.setStatus("PENDING_APPROVAL"); versionMapper.updateById(version); set.setStatus("PENDING_APPROVAL"); mapper.updateById(set); return set;
    }

    @Transactional
    public QualityRuleSet publish(String id) {
        QualityRuleSet set = require(id); QualityRuleSetVersion version = latest(id);
        if (version == null || !"PENDING_APPROVAL".equals(version.getStatus())) throw IqcException.invalidState("规则集必须先提交审批");
        validateRules(readIds(version.getRuleIdsJson()), true); version.setStatus("PUBLISHED"); versionMapper.updateById(version);
        set.setName(version.getName()); set.setDescription(version.getDescription()); set.setRuleIdsJson(version.getRuleIdsJson());
        set.setAggregationMode(version.getAggregationMode()); set.setVersionNo(version.getVersionNo()); set.setStatus("PUBLISHED"); mapper.updateById(set); return set;
    }

    @Transactional
    public QualityRuleSet reject(String id) {
        QualityRuleSet set = require(id); QualityRuleSetVersion version = latest(id);
        if (version == null || !"PENDING_APPROVAL".equals(version.getStatus())) throw IqcException.invalidState("规则集当前没有待审批版本");
        version.setStatus("REJECTED"); versionMapper.updateById(version);
        set.setStatus(versions(id).stream().anyMatch(item -> "PUBLISHED".equals(item.getStatus())) ? "PUBLISHED" : "DRAFT"); mapper.updateById(set); return set;
    }

    public List<String> publishedRuleIds(String id) {
        QualityRuleSet set = require(id);
        if (!"PUBLISHED".equals(set.getStatus())) throw IqcException.invalidArgument("只能选择已发布规则集");
        return validateRules(readIds(set.getRuleIdsJson()), true);
    }

    public PublishedRuleSet published(String id) {
        QualityRuleSet set = require(id);
        if (!"PUBLISHED".equals(set.getStatus())) throw IqcException.invalidArgument("只能选择已发布规则集");
        return new PublishedRuleSet(set.getId(), set.getName(), set.getCode(), set.getVersionNo(), set.getAggregationMode(),
                validateRules(readIds(set.getRuleIdsJson()), true));
    }

    private List<String> validateRules(List<String> ids, boolean requirePublished) {
        List<String> normalized = ids == null ? List.of() : ids.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
        if (normalized.isEmpty()) throw IqcException.invalidArgument("规则集至少包含一条规则");
        if (normalized.size() > 100) throw IqcException.invalidArgument("规则集最多包含 100 条规则");
        for (String id : normalized) { QualityRule rule = ruleMapper.selectById(id); if (rule == null) throw IqcException.invalidArgument("规则不存在: " + id); if (requirePublished && !"PUBLISHED".equals(rule.getStatus())) throw IqcException.invalidArgument("规则集只能引用已发布规则: " + id); }
        return normalized;
    }

    private QualityRuleSet require(String id) { QualityRuleSet set = mapper.selectById(id); if (set == null) throw IqcException.notFound("规则集不存在: " + id); return set; }
    private QualityRuleSetVersion latest(String id) { return versions(id).stream().findFirst().orElse(null); }
    private void validateIdentity(String name, String code) { if (name == null || name.isBlank() || code == null || code.isBlank()) throw IqcException.invalidArgument("规则集名称和编码不能为空"); }
    private String normalizeMode(String mode) { String value = mode == null ? "ALL" : mode.trim().toUpperCase(); if (!List.of("ALL", "ANY").contains(value)) throw IqcException.invalidArgument("规则集聚合模式仅支持 ALL 或 ANY"); return value; }
    private String write(Object value) { try { return objectMapper.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException("规则集快照生成失败", e); } }
    private List<String> readIds(String json) { try { return objectMapper.readValue(json, new TypeReference<>() { }); } catch (JsonProcessingException e) { throw IqcException.invalidArgument("规则集成员数据损坏", e); } }
    private QualityRuleSetVersion toVersion(QualityRuleSet set, int number) { QualityRuleSetVersion version = new QualityRuleSetVersion(); version.setRuleSetId(set.getId()); version.setVersionNo(number); version.setName(set.getName()); version.setCode(set.getCode()); version.setDescription(set.getDescription()); version.setRuleIdsJson(set.getRuleIdsJson()); version.setAggregationMode(set.getAggregationMode()); version.setStatus("DRAFT"); return version; }
    public record PublishedRuleSet(String id, String name, String code, Integer versionNo,
                                   String aggregationMode, List<String> ruleIds) { }
}
