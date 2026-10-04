package io.github.opensabre.iqc.label;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.label.dao.*;
import io.github.opensabre.iqc.label.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import io.github.opensabre.iqc.shared.IqcDataScope;

import java.util.*;

/** Resolves business selections to immutable label and executable-rule snapshots at task creation time. */
@Service @RequiredArgsConstructor
public class LabelResolutionService {
    private final LabelCategoryMapper categoryMapper; private final LabelGroupMapper groupMapper;
    private final InsightLabelMapper labelMapper; private final LabelRuleBindingMapper bindingMapper;
    private final LabelValueDefinitionMapper valueMapper; private final LabelCollectionMapper collectionMapper;
    private final LabelCollectionMemberMapper memberMapper;
    private final IqcDataScope dataScope;

    /** Freezes exact currently published label versions; old versions must come from an existing release snapshot. */
    public ResolvedSelection resolveVersions(List<LabelReference> references) {
        if (references == null || references.isEmpty() || references.size() > 200)
            throw IqcException.invalidArgument("方案标签引用必须为 1 到 200 项");
        var expected = new LinkedHashMap<String, Integer>();
        for (var reference : references) {
            if (reference == null || reference.id() == null || reference.id().isBlank() || reference.versionNo() < 1
                    || expected.putIfAbsent(reference.id(), reference.versionNo()) != null)
                throw IqcException.invalidArgument("方案标签引用无效或重复");
        }
        var resolved = resolve(new LabelSelection(null, null, List.copyOf(expected.keySet()), null));
        for (var label : resolved.labels()) {
            if (!Objects.equals(expected.get(label.id()), label.versionNo()))
                throw IqcException.invalidState("标签版本已变化，请重新选择并试跑: " + label.id());
            for (var binding : label.bindings()) {
                if (binding.getRuleId() == null || binding.getRuleId().isBlank()
                        || binding.getRuleVersionNo() == null || binding.getRuleVersionNo() < 1)
                    throw IqcException.invalidState("标签绑定缺少明确规则版本: " + label.id());
            }
        }
        // A distinct protocol prevents coverage-aware outputs from being interpreted as legacy hit-only labels.
        return new ResolvedSelection("2.0", resolved.labels(), resolved.ruleIds());
    }

    public ResolvedSelection resolve(LabelSelection selection) {
        if (selection == null) throw IqcException.invalidArgument("标签选择不能为空");
        LinkedHashSet<String> categoryIds = clean(selection.categoryIds());
        LinkedHashSet<String> groupIds = clean(selection.groupIds());
        LinkedHashSet<String> labelIds = clean(selection.labelIds());
        for (String collectionId : clean(selection.collectionIds())) expandCollection(collectionId, categoryIds, groupIds, labelIds);
        if (!categoryIds.isEmpty()) groupMapper.selectList(Wrappers.<LabelGroup>lambdaQuery().in(LabelGroup::getCategoryId, categoryIds)).forEach(v -> groupIds.add(v.getId()));
        if (!groupIds.isEmpty()) labelMapper.selectList(Wrappers.<InsightLabel>lambdaQuery().in(InsightLabel::getGroupId, groupIds)).forEach(v -> labelIds.add(v.getId()));
        if (labelIds.isEmpty()) throw IqcException.invalidArgument("标签选择没有解析出可执行标签");
        List<InsightLabel> labels = labelMapper.selectBatchIds(labelIds);
        if (labels.size() != labelIds.size() || labels.stream().anyMatch(v -> !"PUBLISHED".equals(v.getStatus()))) throw IqcException.invalidArgument("任务只能选择已发布标签");
        if (labels.stream().anyMatch(v -> !dataScope.canView(v.getCreatedBy(), v.getOwnerGroupId()))) throw IqcException.accessDenied("标签选择超出当前数据权限");
        Map<String, LabelGroup> groupsById = groupMapper.selectBatchIds(labels.stream().map(InsightLabel::getGroupId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(LabelGroup::getId, java.util.function.Function.identity()));
        Map<String, LabelCategory> categoriesById = categoryMapper.selectBatchIds(groupsById.values().stream().map(LabelGroup::getCategoryId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(LabelCategory::getId, java.util.function.Function.identity()));
        List<LabelSnapshot> snapshots = new ArrayList<>(); LinkedHashSet<String> ruleIds = new LinkedHashSet<>();
        for (InsightLabel label : labels.stream().sorted(Comparator.comparing(InsightLabel::getCode)).toList()) {
            List<LabelRuleBinding> bindings = bindingMapper.selectList(Wrappers.<LabelRuleBinding>lambdaQuery().eq(LabelRuleBinding::getLabelId, label.getId()).orderByAsc(LabelRuleBinding::getDisplayOrder));
            if (bindings.isEmpty()) throw IqcException.invalidState("已发布标签缺少规则绑定: " + label.getName());
            bindings.forEach(v -> ruleIds.add(v.getRuleId()));
            List<LabelValueDefinition> values = valueMapper.selectList(Wrappers.<LabelValueDefinition>lambdaQuery().eq(LabelValueDefinition::getLabelId, label.getId()).orderByAsc(LabelValueDefinition::getDisplayOrder));
            LabelGroup group = groupsById.get(label.getGroupId());
            snapshots.add(new LabelSnapshot(label.getId(), label.getVersionNo(), label.getName(), label.getCode(), label.getGroupId(),
                    group == null ? null : group.getName(), group == null ? null : group.getCode(), group == null ? null : group.getCategoryId(),
                    group == null || categoriesById.get(group.getCategoryId()) == null ? null : categoriesById.get(group.getCategoryId()).getName(),
                    group != null && Boolean.TRUE.equals(group.getAllowAutoExpand()), label.getTargetRole(), label.getWeight(), List.copyOf(bindings), List.copyOf(values)));
        }
        return new ResolvedSelection("1.0", List.copyOf(snapshots), List.copyOf(ruleIds));
    }

    private void expandCollection(String id, Set<String> categories, Set<String> groups, Set<String> labels) {
        LabelCollection collection = collectionMapper.selectById(id); if (collection == null || !"PUBLISHED".equals(collection.getStatus())) throw IqcException.invalidArgument("任务只能选择已发布标签集合");
        if (!dataScope.canView(collection.getCreatedBy(), collection.getOwnerGroupId())) throw IqcException.accessDenied("标签集合超出当前数据权限");
        for (LabelCollectionMember member : memberMapper.selectList(Wrappers.<LabelCollectionMember>lambdaQuery().eq(LabelCollectionMember::getCollectionId, id))) {
            switch (member.getMemberType()) { case "CATEGORY" -> categories.add(member.getMemberId()); case "GROUP" -> groups.add(member.getMemberId()); case "LABEL" -> labels.add(member.getMemberId()); default -> throw IqcException.invalidArgument("标签集合包含未知成员类型"); }
        }
    }
    private LinkedHashSet<String> clean(List<String> values) { LinkedHashSet<String> result = new LinkedHashSet<>(); if (values != null) values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).forEach(result::add); return result; }
    public record LabelSelection(List<String> categoryIds, List<String> groupIds, List<String> labelIds, List<String> collectionIds) { }
    public record LabelReference(String id, int versionNo) { }
    public record ResolvedSelection(String schemaVersion, List<LabelSnapshot> labels, List<String> ruleIds) { }
    public record LabelSnapshot(String id, Integer versionNo, String name, String code, String groupId, String groupName,
                                String groupCode, String categoryId, String categoryName, boolean groupAllowAutoExpand, String targetRole,
                                java.math.BigDecimal weight, List<LabelRuleBinding> bindings, List<LabelValueDefinition> values) { }
}
