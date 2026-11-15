package io.github.opensabre.iqc.label;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.label.dao.*;
import io.github.opensabre.iqc.label.model.*;
import io.github.opensabre.iqc.shared.IqcDataScope;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Configures reusable actions; deliberately never dispatches matching, labeling or deduction. */
@Service @RequiredArgsConstructor
public class LabelActionService {
    private final LabelActionDefinitionMapper definitions;
    private final LabelActionBindingMapper bindings;
    private final InsightLabelMapper labels;
    private final LabelTaxonomyService taxonomy;
    private final IqcDataScope scope;

    /** Existing label permissions apply to the action catalog; at most 500 per page. */
    public List<LabelActionDefinition> list(int page, int size) {
        if (page < 1 || page > 100000 || size < 1 || size > 500) throw IqcException.invalidArgument("动作分页参数无效");
        var query = Wrappers.<LabelActionDefinition>lambdaQuery();
        if (!scope.canViewAll()) query.and(q -> q.eq(LabelActionDefinition::getCreatedBy, scope.owner())
                .or(scope.groupId() != null, nested -> nested.eq(LabelActionDefinition::getOwnerGroupId, scope.groupId())));
        return definitions.selectList(query.orderByAsc(LabelActionDefinition::getId).last("LIMIT " + size + " OFFSET " + (page - 1) * size));
    }
    /** Metadata revision increments the action version; already frozen execution snapshots remain unchanged. */
    @Transactional
    public LabelActionDefinition save(Long id, Request request) {
        if (request == null || !Set.of("RECORD_TAG", "DEDUCT_SCORE").contains(request.type() == null ? "" : request.type())
                || request.name() == null || request.name().isBlank() || request.name().trim().length() > 100
                || request.description() != null && request.description().length() > 500)
            throw IqcException.invalidArgument("动作类型、名称或描述无效");
        var value = id == null ? new LabelActionDefinition() : require(id, true);
        if (id != null && !value.getType().equals(request.type())) throw IqcException.invalidArgument("已创建动作不能变更类型，请新建动作");
        value.setType(request.type()); value.setName(request.name().trim()); value.setDescription(request.description());
        value.setVersionNo(value.getVersionNo() == null ? 1 : value.getVersionNo() + 1);
        if (id == null) { value.setStatus("ENABLED"); value.setCreatedBy(scope.owner()); value.setOwnerGroupId(scope.groupId()); definitions.insert(value); }
        else definitions.updateById(value);
        return value;
    }
    /** Referenced definitions cannot be disabled or deleted; unlink explicitly first. */
    @Transactional
    public void disableOrDelete(Long id, boolean delete) {
        var value = require(id, true);
        if (bindings.selectCount(Wrappers.<LabelActionBinding>lambdaQuery().eq(LabelActionBinding::getActionId, id)) > 0)
            throw IqcException.invalidState("动作仍被标签引用，请先解除绑定");
        if (delete) definitions.deleteById(id);
        else { value.setStatus("DISABLED"); definitions.updateById(value); }
    }
    /** Returns immutable configuration snapshots, including action version and enabled status. */
    @Transactional(readOnly = true)
    public List<Snapshot> snapshots(String labelId) {
        requireLabel(labelId);
        return bindings.selectList(Wrappers.<LabelActionBinding>lambdaQuery().eq(LabelActionBinding::getLabelId, labelId)
                .orderByAsc(LabelActionBinding::getDisplayOrder)).stream().map(binding -> {
            var definition = require(binding.getActionId(), false);
            if (!"ENABLED".equals(definition.getStatus())) throw IqcException.invalidState("标签引用了停用动作");
            return new Snapshot(String.valueOf(binding.getId()), String.valueOf(definition.getId()), definition.getVersionNo(),
                    definition.getType(), definition.getName(), definition.getDescription(), definition.getStatus());
        }).toList();
    }
    /** Whole-set replacement shares the label revision lifecycle and rejects duplicate action references. */
    @Transactional
    public List<Snapshot> replace(String labelId, List<Long> actionIds) {
        requireLabel(labelId);
        if (actionIds == null || actionIds.size() > 20 || actionIds.stream().anyMatch(java.util.Objects::isNull)
                || actionIds.stream().distinct().count() != actionIds.size()) throw IqcException.invalidArgument("请选择最多 20 个不重复动作");
        // Lock all definitions in stable order before writing so disable/delete cannot race a new binding.
        actionIds.stream().sorted().forEach(id -> {
            if (!"ENABLED".equals(require(id, true).getStatus())) throw IqcException.invalidState("不能绑定停用动作");
        });
        taxonomy.reviseBindings(labelId);
        bindings.delete(Wrappers.<LabelActionBinding>lambdaQuery().eq(LabelActionBinding::getLabelId, labelId));
        int order = 0;
        for (Long id : actionIds) {
            var binding = new LabelActionBinding(); binding.setLabelId(labelId); binding.setActionId(id); binding.setDisplayOrder(++order); binding.setCreatedBy(scope.owner());
            bindings.insert(binding);
        }
        return snapshots(labelId);
    }
    private InsightLabel requireLabel(String id) {
        var label = labels.selectById(id);
        if (label == null) throw IqcException.notFound("标签不存在");
        if (!scope.canView(label.getCreatedBy(), label.getOwnerGroupId())) throw IqcException.accessDenied("标签超出数据权限");
        return label;
    }
    private LabelActionDefinition require(Long id, boolean lock) {
        if (id == null) throw IqcException.invalidArgument("动作 ID 不能为空");
        var query = Wrappers.<LabelActionDefinition>lambdaQuery().eq(LabelActionDefinition::getId, id);
        if (lock) query.last("FOR UPDATE");
        var value = definitions.selectOne(query);
        if (value == null) throw IqcException.notFound("动作不存在");
        if (!scope.canView(value.getCreatedBy(), value.getOwnerGroupId())) throw IqcException.accessDenied("动作超出数据权限");
        return value;
    }
    public record Request(String type, String name, String description) { }
    public record Snapshot(String bindingId, String actionId, Integer versionNo, String type, String name, String description, String status) { }
}
