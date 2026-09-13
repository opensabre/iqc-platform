package io.github.opensabre.iqc.label;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.label.dao.*;
import io.github.opensabre.iqc.label.model.*;
import io.github.opensabre.iqc.shared.IqcDataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/** Manages reusable business selections without duplicating rule-set execution semantics. */
@Service @RequiredArgsConstructor
public class LabelCollectionService {
    private static final Set<String> MEMBER_TYPES = Set.of("CATEGORY", "GROUP", "LABEL");
    private final LabelCollectionMapper mapper; private final LabelCollectionMemberMapper memberMapper;
    private final LabelCategoryMapper categoryMapper; private final LabelGroupMapper groupMapper;
    private final InsightLabelMapper labelMapper; private final IqcDataScope dataScope;

    public List<LabelCollection> list() { return mapper.selectList(Wrappers.<LabelCollection>lambdaQuery().orderByDesc(LabelCollection::getCreatedTime))
            .stream().filter(value -> dataScope.canView(value.getCreatedBy(), value.getOwnerGroupId())).toList(); }
    public Detail get(String id) { LabelCollection value = require(id); return new Detail(value, members(id)); }

    @Transactional public Detail create(CollectionRequest request) {
        if (request == null || blank(request.name()) || request.name().trim().length() < 2 || request.name().trim().length() > 50 || blank(request.code())) throw IqcException.invalidArgument("标签集合名称长度必须为 2-50 且编码不能为空");
        if (mapper.selectCount(Wrappers.<LabelCollection>lambdaQuery().eq(LabelCollection::getCode, request.code().trim())) > 0) throw IqcException.invalidArgument("标签集合编码已存在");
        LabelCollection value = new LabelCollection(); value.setName(request.name().trim()); value.setCode(request.code().trim()); value.setDescription(request.description()); value.setStatus("DRAFT"); value.setVersionNo(1); value.setOwnerGroupId(dataScope.groupId()); mapper.insert(value);
        replaceMembers(value.getId(), request.members()); return get(value.getId());
    }

    @Transactional public Detail replaceMembers(String id, List<MemberRequest> requested) {
        LabelCollection collection = require(id); if ("PUBLISHED".equals(collection.getStatus())) { collection.setStatus("DRAFT"); collection.setVersionNo(collection.getVersionNo() + 1); mapper.updateById(collection); }
        List<MemberRequest> values = requested == null ? List.of() : requested;
        if (values.isEmpty()) throw IqcException.invalidArgument("标签集合至少包含一个成员");
        if (values.size() > 100) throw IqcException.invalidArgument("标签集合最多包含 100 个成员");
        if (values.stream().map(v -> normalize(v.memberType()) + ":" + v.memberId()).distinct().count() != values.size()) throw IqcException.invalidArgument("标签集合成员不能重复");
        memberMapper.delete(Wrappers.<LabelCollectionMember>lambdaQuery().eq(LabelCollectionMember::getCollectionId, id)); int order = 0;
        for (MemberRequest request : values) { String type = normalize(request.memberType()); validateMember(type, request.memberId()); LabelCollectionMember member = new LabelCollectionMember(); member.setCollectionId(id); member.setMemberType(type); member.setMemberId(request.memberId()); member.setDisplayOrder(++order); memberMapper.insert(member); }
        return get(id);
    }

    @Transactional public LabelCollection publish(String id) { LabelCollection value = require(id); if (members(id).isEmpty()) throw IqcException.invalidState("空标签集合不能发布"); value.setStatus("PUBLISHED"); mapper.updateById(value); return value; }
    @Transactional public Detail revise(String id, CollectionRequest request) {
        LabelCollection value = require(id); validate(request);
        if (!value.getName().equals(request.name().trim()) && mapper.selectCount(Wrappers.<LabelCollection>lambdaQuery().eq(LabelCollection::getName, request.name().trim()).ne(LabelCollection::getId, id)) > 0)
            throw IqcException.invalidArgument("标签集合名称已存在");
        if ("PUBLISHED".equals(value.getStatus())) { value.setStatus("DRAFT"); value.setVersionNo((value.getVersionNo() == null ? 1 : value.getVersionNo()) + 1); }
        value.setName(request.name().trim()); value.setDescription(request.description()); mapper.updateById(value);
        if (request.members() != null) replaceMembers(id, request.members());
        return get(id);
    }
    @Transactional public LabelCollection disable(String id) { LabelCollection value = require(id); value.setStatus("DISABLED"); mapper.updateById(value); return value; }
    private List<LabelCollectionMember> members(String id) { return memberMapper.selectList(Wrappers.<LabelCollectionMember>lambdaQuery().eq(LabelCollectionMember::getCollectionId, id).orderByAsc(LabelCollectionMember::getDisplayOrder)); }
    private void validateMember(String type, String id) {
        if (blank(id)) throw IqcException.invalidArgument("标签集合成员不能为空");
        boolean exists = switch (type) {
            case "CATEGORY" -> { var value = categoryMapper.selectById(id); yield value != null && !"DISABLED".equals(value.getStatus()) && dataScope.canView(value.getCreatedBy(), value.getOwnerGroupId()); }
            case "GROUP" -> { var value = groupMapper.selectById(id); yield value != null && !"DISABLED".equals(value.getStatus()) && dataScope.canView(value.getCreatedBy(), value.getOwnerGroupId()); }
            case "LABEL" -> { InsightLabel value = labelMapper.selectById(id); yield value != null && "PUBLISHED".equals(value.getStatus()) && dataScope.canView(value.getCreatedBy(), value.getOwnerGroupId()); }
            default -> false;
        };
        if (!exists) throw IqcException.invalidArgument("标签集合成员不存在或不可用: " + type + ":" + id);
    }
    private String normalize(String value) { String result = value == null ? "" : value.trim().toUpperCase(); if (!MEMBER_TYPES.contains(result)) throw IqcException.invalidArgument("标签集合成员类型无效"); return result; }
    private LabelCollection require(String id) { LabelCollection value = mapper.selectById(id); if (value == null) throw IqcException.notFound("标签集合不存在: " + id); if (!dataScope.canView(value.getCreatedBy(), value.getOwnerGroupId())) throw IqcException.accessDenied("无权访问该标签集合"); return value; }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private void validate(CollectionRequest request) { if (request == null || blank(request.name()) || request.name().trim().length() < 2 || request.name().trim().length() > 50) throw IqcException.invalidArgument("标签集合名称长度必须为 2-50"); }
    public record CollectionRequest(String name, String code, String description, List<MemberRequest> members) { }
    public record MemberRequest(String memberType, String memberId) { }
    public record Detail(LabelCollection collection, List<LabelCollectionMember> members) { }
}
