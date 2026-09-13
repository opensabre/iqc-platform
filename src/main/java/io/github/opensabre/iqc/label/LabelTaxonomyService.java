package io.github.opensabre.iqc.label;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.label.dao.*;
import io.github.opensabre.iqc.label.model.*;
import io.github.opensabre.iqc.rule.dao.QualityRuleMapper;
import io.github.opensabre.iqc.rule.model.QualityRule;
import io.github.opensabre.iqc.shared.IqcDataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Owns the three-level business taxonomy while delegating all detection to published IQC rules. */
@Service @RequiredArgsConstructor
public class LabelTaxonomyService {
    private static final Set<String> VALUE_TYPES = Set.of("FIXED", "BOOLEAN", "PERCENTAGE", "DURATION_MONTHS", "MONTH", "DATE");
    private static final Set<String> TARGET_ROLES = Set.of("agent", "user", "both", "all");
    private final LabelCategoryMapper categoryMapper; private final LabelGroupMapper groupMapper;
    private final InsightLabelMapper labelMapper; private final LabelValueDefinitionMapper valueMapper;
    private final LabelRuleBindingMapper bindingMapper; private final QualityRuleMapper ruleMapper;
    private final IqcDataScope dataScope;
    private final ObjectMapper objectMapper;

    public TaxonomyTree tree(String keyword) {
        String term = blank(keyword) ? null : keyword.trim();
        List<LabelCategory> categories = categoryMapper.selectList(Wrappers.<LabelCategory>lambdaQuery().orderByAsc(LabelCategory::getName));
        List<LabelGroup> groups = groupMapper.selectList(Wrappers.<LabelGroup>lambdaQuery().orderByAsc(LabelGroup::getName));
        List<InsightLabel> labels = labelMapper.selectList(Wrappers.<InsightLabel>lambdaQuery().orderByAsc(InsightLabel::getName));
        categories = categories.stream().filter(v -> dataScope.canView(v.getCreatedBy(), v.getOwnerGroupId())).toList();
        Set<String> visibleCategoryIds = categories.stream().map(LabelCategory::getId).collect(java.util.stream.Collectors.toSet());
        groups = groups.stream().filter(v -> visibleCategoryIds.contains(v.getCategoryId()) && dataScope.canView(v.getCreatedBy(), v.getOwnerGroupId())).toList();
        Set<String> visibleGroupIds = groups.stream().map(LabelGroup::getId).collect(java.util.stream.Collectors.toSet());
        labels = labels.stream().filter(v -> visibleGroupIds.contains(v.getGroupId()) && dataScope.canView(v.getCreatedBy(), v.getOwnerGroupId())).toList();
        if (term != null) {
            Set<String> matchedCategories = categories.stream().filter(v -> contains(v.getName(), term) || contains(v.getCode(), term)).map(LabelCategory::getId).collect(java.util.stream.Collectors.toSet());
            Set<String> matchedGroups = groups.stream().filter(v -> contains(v.getName(), term) || contains(v.getCode(), term)).map(LabelGroup::getId).collect(java.util.stream.Collectors.toSet());
            Set<String> matchedLabels = labels.stream().filter(v -> contains(v.getName(), term) || contains(v.getCode(), term)).map(InsightLabel::getId).collect(java.util.stream.Collectors.toSet());
            matchedGroups.addAll(labels.stream().filter(v -> matchedLabels.contains(v.getId())).map(InsightLabel::getGroupId).toList());
            matchedCategories.addAll(groups.stream().filter(v -> matchedGroups.contains(v.getId())).map(LabelGroup::getCategoryId).toList());
            Set<String> categoryMatches = Set.copyOf(matchedCategories);
            groups = groups.stream().filter(v -> matchedGroups.contains(v.getId()) || categoryMatches.contains(v.getCategoryId())).toList();
            Set<String> visibleGroups = groups.stream().map(LabelGroup::getId).collect(java.util.stream.Collectors.toSet());
            labels = labels.stream().filter(v -> visibleGroups.contains(v.getGroupId())).toList();
            categories = categories.stream().filter(v -> categoryMatches.contains(v.getId())).toList();
        }
        return new TaxonomyTree(categories, groups, labels);
    }

    @Transactional public LabelCategory createCategory(CategoryRequest request) {
        requireNameCode(request.name(), request.code()); requireUniqueCategory(request.code());
        if (categoryMapper.selectCount(Wrappers.<LabelCategory>lambdaQuery().eq(LabelCategory::getName, request.name().trim())) > 0) throw IqcException.invalidArgument("一级标签名称已存在");
        LabelCategory value = new LabelCategory(); value.setName(request.name().trim()); value.setCode(request.code().trim());
        value.setPrompt(trim(request.prompt(), 500)); value.setMaxChildCount(nonNegative(request.maxChildCount()));
        value.setAllowAutoExpand(Boolean.TRUE.equals(request.allowAutoExpand())); value.setStatus("DRAFT"); value.setVersionNo(1); value.setOwnerGroupId(dataScope.groupId()); categoryMapper.insert(value); return value;
    }

    @Transactional public LabelGroup createGroup(GroupRequest request) {
        LabelCategory parent = categoryMapper.selectById(request.categoryId()); if (parent == null) throw IqcException.notFound("一级标签不存在");
        if (!dataScope.canView(parent.getCreatedBy(), parent.getOwnerGroupId())) throw IqcException.accessDenied("无权在该一级标签下创建群组");
        requireNameCode(request.name(), request.code()); requireUniqueGroup(request.code()); long count = groupMapper.selectCount(Wrappers.<LabelGroup>lambdaQuery().eq(LabelGroup::getCategoryId, parent.getId()));
        if (groupMapper.selectCount(Wrappers.<LabelGroup>lambdaQuery().eq(LabelGroup::getCategoryId, parent.getId()).eq(LabelGroup::getName, request.name().trim())) > 0) throw IqcException.invalidArgument("同一一级标签下群组名称已存在");
        if (parent.getMaxChildCount() != null && parent.getMaxChildCount() > 0 && count >= parent.getMaxChildCount()) throw IqcException.invalidState("一级标签已达到最大标签群组数量");
        LabelGroup value = new LabelGroup(); value.setCategoryId(parent.getId()); value.setName(request.name().trim()); value.setCode(request.code().trim());
        value.setDescription(trim(request.description(), 200)); value.setMaxChildCount(nonNegative(request.maxChildCount())); value.setAllowAutoExpand(Boolean.TRUE.equals(request.allowAutoExpand()));
        value.setStatus("DRAFT"); value.setVersionNo(1); value.setOwnerGroupId(dataScope.groupId()); groupMapper.insert(value); return value;
    }

    @Transactional public InsightLabel createLabel(LabelRequest request) {
        LabelGroup parent = groupMapper.selectById(request.groupId()); if (parent == null) throw IqcException.notFound("标签群组不存在");
        if (!dataScope.canView(parent.getCreatedBy(), parent.getOwnerGroupId())) throw IqcException.accessDenied("无权在该标签群组下创建标签");
        requireNameCode(request.name(), request.code()); requireUniqueLabel(request.code()); long count = labelMapper.selectCount(Wrappers.<InsightLabel>lambdaQuery().eq(InsightLabel::getGroupId, parent.getId()));
        if (labelMapper.selectCount(Wrappers.<InsightLabel>lambdaQuery().eq(InsightLabel::getGroupId, parent.getId()).eq(InsightLabel::getName, request.name().trim())) > 0) throw IqcException.invalidArgument("同一群组下标签名称已存在");
        if (parent.getMaxChildCount() != null && parent.getMaxChildCount() > 0 && count >= parent.getMaxChildCount()) throw IqcException.invalidState("标签群组已达到最大标签数量");
        String role = blank(request.targetRole()) ? "all" : request.targetRole().trim().toLowerCase(); if (!TARGET_ROLES.contains(role)) throw IqcException.invalidArgument("标签生效角色无效");
        InsightLabel value = new InsightLabel(); value.setGroupId(parent.getId()); value.setName(request.name().trim()); value.setCode(request.code().trim()); value.setDescription(trim(request.description(), 500));
        value.setTargetRole(role); value.setWeight(request.weight() == null ? BigDecimal.ONE : request.weight()); value.setStatus("DRAFT"); value.setVersionNo(1); value.setSourceType("MANUAL"); value.setOwnerGroupId(dataScope.groupId()); labelMapper.insert(value); return value;
    }

    @Transactional public List<LabelValueDefinition> replaceValues(String labelId, List<ValueRequest> requests) {
        prepareRevision(labelId); List<ValueRequest> values = requests == null ? List.of() : requests; if (values.size() > 10) throw IqcException.invalidArgument("每个标签最多配置 10 个值");
        if (values.stream().map(ValueRequest::valueCode).anyMatch(this::blank) || values.stream().map(ValueRequest::valueCode).distinct().count() != values.size()) throw IqcException.invalidArgument("标签值编码不能为空或重复");
        valueMapper.delete(Wrappers.<LabelValueDefinition>lambdaQuery().eq(LabelValueDefinition::getLabelId, labelId)); int order = 0;
        for (ValueRequest request : values) { String type = request.valueType() == null ? "" : request.valueType().toUpperCase(); if (!VALUE_TYPES.contains(type)) throw IqcException.invalidArgument("不支持的标签值类型: " + type);
            validateValueConfig(type, request.configJson());
            LabelValueDefinition value = new LabelValueDefinition(); value.setLabelId(labelId); value.setValueCode(request.valueCode().trim()); value.setValueType(type); value.setDescription(trim(request.description(), 500)); value.setDisplayOrder(++order); value.setConfigJson(request.configJson()); valueMapper.insert(value); }
        return values(labelId);
    }

    @Transactional public List<LabelRuleBinding> replaceBindings(String labelId, List<BindingRequest> requests) {
        prepareRevision(labelId); List<BindingRequest> values = requests == null ? List.of() : requests; if (values.isEmpty()) throw IqcException.invalidArgument("标签至少绑定一条规则");
        bindingMapper.delete(Wrappers.<LabelRuleBinding>lambdaQuery().eq(LabelRuleBinding::getLabelId, labelId)); int order = 0;
        for (BindingRequest request : values) { QualityRule rule = ruleMapper.selectById(request.ruleId()); if (rule == null || !"PUBLISHED".equals(rule.getStatus())) throw IqcException.invalidArgument("标签只能绑定已发布规则");
            LabelRuleBinding binding = new LabelRuleBinding(); binding.setLabelId(labelId); binding.setRuleId(rule.getId()); binding.setRuleVersionNo(rule.getVersionNo()); binding.setBindingRole("PRIMARY"); binding.setDisplayOrder(++order); bindingMapper.insert(binding); }
        return bindings(labelId);
    }

    @Transactional public InsightLabel publish(String labelId) { InsightLabel label = requireLabel(labelId); requireAccess(label.getCreatedBy(), label.getOwnerGroupId()); if (bindings(labelId).isEmpty()) throw IqcException.invalidState("标签绑定已发布规则后才能发布"); label.setStatus("PUBLISHED"); labelMapper.updateById(label); return label; }
    @Transactional public InsightLabel revise(String labelId, LabelRequest request) {
        InsightLabel label = prepareRevision(labelId); requireNameCode(request.name(), label.getCode());
        if (!label.getName().equals(request.name().trim()) && labelMapper.selectCount(Wrappers.<InsightLabel>lambdaQuery().eq(InsightLabel::getGroupId, label.getGroupId()).eq(InsightLabel::getName, request.name().trim()).ne(InsightLabel::getId, labelId)) > 0)
            throw IqcException.invalidArgument("同一群组下标签名称已存在");
        String role = blank(request.targetRole()) ? label.getTargetRole() : request.targetRole().trim().toLowerCase();
        if (!TARGET_ROLES.contains(role)) throw IqcException.invalidArgument("标签生效角色无效");
        label.setName(request.name().trim()); label.setDescription(trim(request.description(), 500)); label.setTargetRole(role);
        label.setWeight(request.weight() == null ? label.getWeight() : request.weight()); labelMapper.updateById(label); return label;
    }
    @Transactional public LabelCategory reviseCategory(String id, CategoryRequest request) {
        LabelCategory value = requireCategory(id); requireNameCode(request.name(), value.getCode());
        if (!value.getName().equals(request.name().trim()) && categoryMapper.selectCount(Wrappers.<LabelCategory>lambdaQuery().eq(LabelCategory::getName, request.name().trim()).ne(LabelCategory::getId, id)) > 0)
            throw IqcException.invalidArgument("一级标签名称已存在");
        value.setName(request.name().trim()); value.setPrompt(trim(request.prompt(), 500)); value.setMaxChildCount(nonNegative(request.maxChildCount()));
        value.setAllowAutoExpand(Boolean.TRUE.equals(request.allowAutoExpand())); reviseStatus(value); categoryMapper.updateById(value); return value;
    }
    @Transactional public LabelGroup reviseGroup(String id, GroupRequest request) {
        LabelGroup value = requireGroup(id); requireNameCode(request.name(), value.getCode());
        if (!value.getName().equals(request.name().trim()) && groupMapper.selectCount(Wrappers.<LabelGroup>lambdaQuery().eq(LabelGroup::getCategoryId, value.getCategoryId()).eq(LabelGroup::getName, request.name().trim()).ne(LabelGroup::getId, id)) > 0)
            throw IqcException.invalidArgument("同一一级标签下群组名称已存在");
        value.setName(request.name().trim()); value.setDescription(trim(request.description(), 200)); value.setMaxChildCount(nonNegative(request.maxChildCount()));
        value.setAllowAutoExpand(Boolean.TRUE.equals(request.allowAutoExpand())); reviseStatus(value); groupMapper.updateById(value); return value;
    }
    @Transactional public InsightLabel disableLabel(String id) { InsightLabel value = requireLabel(id); requireAccess(value.getCreatedBy(), value.getOwnerGroupId()); value.setStatus("DISABLED"); labelMapper.updateById(value); return value; }
    @Transactional public LabelGroup disableGroup(String id) { LabelGroup value = requireGroup(id); if (labelMapper.selectCount(Wrappers.<InsightLabel>lambdaQuery().eq(InsightLabel::getGroupId, id).ne(InsightLabel::getStatus, "DISABLED")) > 0) throw IqcException.invalidState("请先停用群组下的标签"); value.setStatus("DISABLED"); groupMapper.updateById(value); return value; }
    @Transactional public LabelCategory disableCategory(String id) { LabelCategory value = requireCategory(id); if (groupMapper.selectCount(Wrappers.<LabelGroup>lambdaQuery().eq(LabelGroup::getCategoryId, id).ne(LabelGroup::getStatus, "DISABLED")) > 0) throw IqcException.invalidState("请先停用一级标签下的群组"); value.setStatus("DISABLED"); categoryMapper.updateById(value); return value; }
    public LabelDetail detail(String labelId) { InsightLabel label = requireLabel(labelId); requireAccess(label.getCreatedBy(), label.getOwnerGroupId()); return new LabelDetail(label, values(labelId), bindings(labelId)); }
    public List<LabelValueDefinition> values(String labelId) { return valueMapper.selectList(Wrappers.<LabelValueDefinition>lambdaQuery().eq(LabelValueDefinition::getLabelId, labelId).orderByAsc(LabelValueDefinition::getDisplayOrder)); }
    public List<LabelRuleBinding> bindings(String labelId) { return bindingMapper.selectList(Wrappers.<LabelRuleBinding>lambdaQuery().eq(LabelRuleBinding::getLabelId, labelId).orderByAsc(LabelRuleBinding::getDisplayOrder)); }
    private InsightLabel requireLabel(String id) { InsightLabel value = labelMapper.selectById(id); if (value == null) throw IqcException.notFound("标签不存在: " + id); return value; }
    private LabelCategory requireCategory(String id) { LabelCategory value = categoryMapper.selectById(id); if (value == null) throw IqcException.notFound("一级标签不存在: " + id); requireAccess(value.getCreatedBy(), value.getOwnerGroupId()); return value; }
    private LabelGroup requireGroup(String id) { LabelGroup value = groupMapper.selectById(id); if (value == null) throw IqcException.notFound("标签群组不存在: " + id); requireAccess(value.getCreatedBy(), value.getOwnerGroupId()); return value; }
    private void requireAccess(String owner, String group) { if (!dataScope.canView(owner, group)) throw IqcException.accessDenied("无权修改该标签资产"); }
    private void reviseStatus(LabelCategory value) { if ("PUBLISHED".equals(value.getStatus())) { value.setStatus("DRAFT"); value.setVersionNo((value.getVersionNo() == null ? 1 : value.getVersionNo()) + 1); } }
    private void reviseStatus(LabelGroup value) { if ("PUBLISHED".equals(value.getStatus())) { value.setStatus("DRAFT"); value.setVersionNo((value.getVersionNo() == null ? 1 : value.getVersionNo()) + 1); } }
    private InsightLabel prepareRevision(String id) { InsightLabel value = requireLabel(id); if (!dataScope.canView(value.getCreatedBy(), value.getOwnerGroupId())) throw IqcException.accessDenied("无权修改该标签"); if ("PUBLISHED".equals(value.getStatus())) { value.setStatus("DRAFT"); value.setVersionNo((value.getVersionNo() == null ? 1 : value.getVersionNo()) + 1); labelMapper.updateById(value); } return value; }
    private void requireUniqueCategory(String code) { if (categoryMapper.selectCount(Wrappers.<LabelCategory>lambdaQuery().eq(LabelCategory::getCode, code.trim())) > 0) throw IqcException.invalidArgument("一级标签编码已存在"); }
    private void requireUniqueGroup(String code) { if (groupMapper.selectCount(Wrappers.<LabelGroup>lambdaQuery().eq(LabelGroup::getCode, code.trim())) > 0) throw IqcException.invalidArgument("标签群组编码已存在"); }
    private void requireUniqueLabel(String code) { if (labelMapper.selectCount(Wrappers.<InsightLabel>lambdaQuery().eq(InsightLabel::getCode, code.trim())) > 0) throw IqcException.invalidArgument("标签编码已存在"); }
    private void validateValueConfig(String type, String configJson) {
        if (blank(configJson)) return;
        try {
            var config = objectMapper.readTree(configJson);
            if (!config.isObject()) throw new IllegalArgumentException();
            var value = config.get("defaultValue");
            if (value == null || value.isNull()) return;
            switch (type) {
                case "PERCENTAGE" -> { if (!value.isNumber() || value.decimalValue().compareTo(BigDecimal.ZERO) < 0 || value.decimalValue().compareTo(BigDecimal.valueOf(100)) > 0) throw new IllegalArgumentException(); }
                case "MONTH" -> { if (!value.canConvertToInt() || value.asInt() < 1 || value.asInt() > 12) throw new IllegalArgumentException(); }
                case "DATE" -> java.time.LocalDate.parse(value.asText());
                case "BOOLEAN" -> { if (!value.isBoolean()) throw new IllegalArgumentException(); }
                case "DURATION_MONTHS" -> { if (!value.canConvertToInt() || value.asInt() < 0) throw new IllegalArgumentException(); }
                default -> { }
            }
        } catch (Exception exception) { throw IqcException.invalidArgument("标签值配置与值类型不匹配: " + type); }
    }
    private void requireNameCode(String name, String code) { if (blank(name) || name.trim().length() < 2 || name.trim().length() > 50 || blank(code)) throw IqcException.invalidArgument("名称长度必须为 2-50 且编码不能为空"); }
    private int nonNegative(Integer value) { if (value == null) return 0; if (value < 0) throw IqcException.invalidArgument("最大子项数不能为负数"); return value; }
    private String trim(String value, int max) { if (value == null) return null; String v = value.trim(); if (v.length() > max) throw IqcException.invalidArgument("文本长度不能超过 " + max); return v; }
    private boolean blank(String value) { return value == null || value.isBlank(); } private boolean contains(String value, String term) { return value != null && value.toLowerCase().contains(term.toLowerCase()); }
    public record TaxonomyTree(List<LabelCategory> categories, List<LabelGroup> groups, List<InsightLabel> labels) { }
    public record CategoryRequest(String name, String code, String prompt, Integer maxChildCount, Boolean allowAutoExpand) { }
    public record GroupRequest(String categoryId, String name, String code, String description, Integer maxChildCount, Boolean allowAutoExpand) { }
    public record LabelRequest(String groupId, String name, String code, String description, String targetRole, BigDecimal weight) { }
    public record ValueRequest(String valueCode, String valueType, String description, String configJson) { }
    public record BindingRequest(String ruleId) { }
    public record LabelDetail(InsightLabel label, List<LabelValueDefinition> values, List<LabelRuleBinding> bindings) { }
}
