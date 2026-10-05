package io.github.opensabre.iqc.selector;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.selector.dao.*;
import io.github.opensabre.iqc.selector.model.*;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.shared.IqcPage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** Independent selection definitions; conversation selection and task execution remain existing capabilities. */
@Service @RequiredArgsConstructor
public class SelectorAssetService {
    public record Reference(String selectorId,int versionNo) { }
    public record Prepared(String selectorId,int versionNo,int sourceDraftRevision,SelectorDefinition definition,
                           String contentHash,String ownerGroupId) { }
    private final SelectorAssetMapper assets;
    private final SelectorAssetVersionMapper versions;
    private final ConversationSelectionService selections;
    private final ObjectMapper json;
    private final IqcDataScope scope;

    public SelectorDefinition requestDefinition(SelectorDefinition definition,String definitionJson) {
        if(definitionJson==null){selections.validate(definition);return definition;}
        if(definition!=null)throw IqcException.invalidArgument("定义对象与精确JSON不能同时提交");
        var parsed=read(definitionJson);selections.validate(parsed);return parsed;
    }
    public IqcPage<SelectorAsset> page(long current,long size) {
        if(current<1 || size<1 || size>100)throw IqcException.invalidArgument("无效Selector分页");
        var query=Wrappers.<SelectorAsset>lambdaQuery();
        if(!scope.canViewAll()) {String group=scope.groupId();query.and(q->{
            q.eq(SelectorAsset::getCreatedBy,scope.owner());if(group!=null)q.or().eq(SelectorAsset::getOwnerGroupId,group);
        });}
        return IqcPage.from(assets.selectPage(new Page<>(current,size),query.orderByDesc(SelectorAsset::getCreatedTime).orderByDesc(SelectorAsset::getId)));
    }
    public SelectorAsset get(String id){return require(id,false);}
    @Transactional public SelectorAsset create(String code,String name,SelectorDefinition definition) {
        requireActor();if(code==null || !code.matches("[A-Z][A-Z0-9_]{1,63}"))throw IqcException.invalidArgument("无效Selector编码");
        validateName(name);selections.validate(definition);
        var row=new SelectorAsset();row.setCode(code);row.setName(name.trim());row.setDraftRevision(1);
        row.setDraftConfigJson(write(definition));row.setStatus("ACTIVE");row.setOwnerGroupId(scope.groupId());
        if(assets.insert(row)!=1)throw IqcException.invalidState("Selector保存失败");return row;
    }
    @Transactional public SelectorAsset update(String id,int revision,String name,SelectorDefinition definition) {
        requireActor();var row=require(id,true);checkRevision(row,revision);validateName(name);selections.validate(definition);
        if(revision==Integer.MAX_VALUE)throw IqcException.invalidState("Selector修订已达上限");
        row.setName(name.trim());row.setDraftConfigJson(write(definition));row.setDraftRevision(revision+1);
        update(row);return row;
    }
    @Transactional public SelectorAsset availability(String id,boolean enabled) {
        requireActor();var row=require(id,true);row.setStatus(enabled?"ACTIVE":"DISABLED");update(row);return row;
    }
    public List<SelectorAssetVersion> history(String id) {
        require(id,false);var rows=versions.selectList(Wrappers.<SelectorAssetVersion>lambdaQuery()
                .eq(SelectorAssetVersion::getSelectorId,id).orderByDesc(SelectorAssetVersion::getVersionNo).last("LIMIT 100"));
        rows.forEach(this::validateVersion);return rows;
    }
    @Transactional public SelectorAssetVersion publish(String id,int revision) {
        requireActor();var row=require(id,true);checkRevision(row,revision);active(row);
        var prior=versions.selectOne(Wrappers.<SelectorAssetVersion>lambdaQuery().eq(SelectorAssetVersion::getSelectorId,id)
                .eq(SelectorAssetVersion::getSourceDraftRevision,revision));
        if(prior!=null){validateVersion(prior);return prior;}
        var definition=read(row.getDraftConfigJson());selections.validate(definition);
        if(row.getActivePublishedVersion()!=null && row.getActivePublishedVersion()==Integer.MAX_VALUE)
            throw IqcException.invalidState("Selector版本已达上限");
        var version=new SelectorAssetVersion();version.setSelectorId(id);version.setSourceDraftRevision(revision);
        version.setVersionNo(row.getActivePublishedVersion()==null?1:row.getActivePublishedVersion()+1);
        version.setSnapshotJson(write(definition));version.setContentHash(hash(version.getSnapshotJson()));
        if(versions.insert(version)!=1)throw IqcException.invalidState("Selector版本保存失败");
        row.setActivePublishedVersion(version.getVersionNo());update(row);return version;
    }
    public Prepared published(String id,int versionNo) {return published(id,versionNo,false);}
    /** Called inside task creation transaction to serialize new use with disable/update. */
    public Prepared publishedForTask(String id,int versionNo) {return published(id,versionNo,true);}
    private Prepared published(String id,int versionNo,boolean lock) {
        if(versionNo<1)throw IqcException.invalidArgument("Selector版本必须为正数");
        var asset=require(id,lock);active(asset);
        var version=versions.selectOne(Wrappers.<SelectorAssetVersion>lambdaQuery().eq(SelectorAssetVersion::getSelectorId,id)
                .eq(SelectorAssetVersion::getVersionNo,versionNo));
        if(version==null)throw IqcException.notFound("Selector发布版本不存在");
        validateVersion(version);
        return new Prepared(id,versionNo,version.getSourceDraftRevision(),read(version.getSnapshotJson()),version.getContentHash(),asset.getOwnerGroupId());
    }
    public ConversationSelectionService.Preview preview(String id,int versionNo,int size) {
        var source=published(id,versionNo);var preview=selections.preview(source.definition(),size);
        return new ConversationSelectionService.Preview(preview.schemaVersion(),preview.anchor(),preview.resolvedWindow(),preview.candidateCount(),
                preview.matchedCount(),preview.sampledCount(),preview.selectedCount(),preview.limited(),preview.matchedDistribution(),
                preview.selectedDistribution(),preview.preview(),source.contentHash());
    }
    private SelectorAsset require(String id,boolean lock) {
        var query=Wrappers.<SelectorAsset>lambdaQuery().eq(SelectorAsset::getId,id);if(lock)query.last("FOR UPDATE");
        var row=assets.selectOne(query);if(row==null)throw IqcException.notFound("Selector资产不存在");
        if(!scope.canView(row.getCreatedBy(),row.getOwnerGroupId()))throw IqcException.accessDenied("无权访问Selector资产");return row;
    }
    private void validateVersion(SelectorAssetVersion version) {
        if(version.getSnapshotJson()==null || !hash(version.getSnapshotJson()).equals(version.getContentHash()))
            throw IqcException.invalidState("Selector版本摘要不一致");
        selections.validate(read(version.getSnapshotJson()));
    }
    private SelectorDefinition read(String value) {
        if(value==null || value.length()>128*1024)throw IqcException.invalidState("Selector配置大小无效");
        try{return exact(json).readValue(value,SelectorDefinition.class);}catch(Exception invalid){throw IqcException.invalidState("Selector配置无效");}
    }
    private String write(SelectorDefinition definition) {
        try{return json.writeValueAsString(definition);}catch(Exception invalid){throw IqcException.invalidArgument("Selector配置无法序列化");}
    }
    private static ObjectMapper exact(ObjectMapper mapper) {
        return mapper.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
    }
    /** Preserve JSON decimal values; 0.1 and 0.10000000000000000001 can select different counts. */
    public static String hash(String value) {
        try {
            var node=exact(new ObjectMapper()).readTree(value);
            var canonical=canonical(node);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch(Exception invalid){throw IqcException.invalidState("Selector摘要输入无效");}
    }
    private static String canonical(com.fasterxml.jackson.databind.JsonNode node) {
        if(node.isObject()) {
            var names=new java.util.TreeSet<String>();node.fieldNames().forEachRemaining(names::add);
            return names.stream().map(name->new com.fasterxml.jackson.databind.node.TextNode(name)+":"+canonical(node.get(name)))
                    .collect(java.util.stream.Collectors.joining(",","{","}"));
        }
        if(node.isArray()) {var values=new java.util.ArrayList<String>();node.forEach(n->values.add(canonical(n)));
            return String.join(",",values).transform(v->"["+v+"]");}
        if(node.isNumber())return node.decimalValue().stripTrailingZeros().toString();
        return node.toString();
    }
    private void requireActor(){if("system".equals(scope.owner()))throw IqcException.accessDenied("Selector维护需要已认证操作者");}
    private void validateName(String name){if(name==null || name.isBlank() || name.length()>100)throw IqcException.invalidArgument("Selector名称必须为1–100字");}
    private void active(SelectorAsset row){if(!"ACTIVE".equals(row.getStatus()))throw IqcException.invalidState("Selector资产已停用");}
    private void checkRevision(SelectorAsset row,int revision){if(row.getDraftRevision()==null || row.getDraftRevision()!=revision)throw IqcException.invalidState("Selector草稿修订已变化，请重新加载");}
    private void update(SelectorAsset row){if(assets.updateById(row)!=1)throw IqcException.invalidState("Selector更新失败");}
}
