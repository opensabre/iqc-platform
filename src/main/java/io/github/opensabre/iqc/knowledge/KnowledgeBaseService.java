package io.github.opensabre.iqc.knowledge;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.knowledge.dao.*;
import io.github.opensabre.iqc.knowledge.model.*;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.shared.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Corpus ownership and immutable releases only; retrieval and model execution remain separate workflows. */
@Service @lombok.RequiredArgsConstructor
public class KnowledgeBaseService {
    public record Reference(String knowledgeBaseId,int versionNo){
        public Reference {if(knowledgeBaseId==null||!knowledgeBaseId.matches("[A-Za-z0-9:_-]{1,64}")||versionNo<1)
            throw new IllegalArgumentException("knowledge reference requires exact positive version");}
    }
    public record Published(String schemaVersion,String knowledgeBaseId,int versionNo,int sourceDraftRevision,String name,
                            KnowledgeCorpus corpus,String owner,String ownerGroupId,String publishedBy){ }
    public record Prepared(String versionId,Published snapshot,String contentHash,String ownerGroupId){ }
    /** Safe for shared capability configurations: no corpus, owner, title or source group. */
    public record Binding(String knowledgeBaseId,int versionNo,String versionId,String contentHash){ }
    private final KnowledgeBaseMapper bases;
    private final KnowledgeBaseVersionMapper versions;
    private final IqcDataScope scope;
    private final ObjectMapper json;
    private static final int MAX_BYTES=2*1024*1024;
    private static ObjectMapper exact(ObjectMapper json){return json.copy()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);}
    private static <T>T read(ObjectMapper json,String body,Class<T> type) throws java.io.IOException{
        var mapper=exact(json);
        try(var parser=mapper.createParser(body)){
            T value=mapper.readValue(parser,type);if(parser.nextToken()!=null)throw new IllegalArgumentException("trailing knowledge JSON");return value;
        }
    }
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception invalid){throw IqcException.invalidArgument("知识库正文无法编码");}}
    public KnowledgeCorpus requestCorpus(String corpusJson){
        if(corpusJson==null||corpusJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_BYTES)
            throw IqcException.invalidArgument("知识库语料不能为空或超过2MiB");
        try{var value=read(json,corpusJson,KnowledgeCorpus.class);if(value==null)throw new IllegalArgumentException();return value;}
        catch(Exception invalid){throw IqcException.invalidArgument("知识库语料协议无效");}
    }
    private void actor(){if("system".equals(scope.owner()))throw IqcException.accessDenied("知识库需要已认证负责人");}
    private static void name(String name){if(name==null||name.isBlank()||name.length()>100)throw IqcException.invalidArgument("知识库名称须为1–100字");}
    private KnowledgeBase require(String id,boolean lock){
        actor();if(id==null||!id.matches("[A-Za-z0-9:_-]{1,64}"))throw IqcException.invalidArgument("知识库ID无效");
        var query=Wrappers.<KnowledgeBase>lambdaQuery().eq(KnowledgeBase::getId,id);if(lock)query.last("FOR UPDATE");
        var row=bases.selectOne(query);if(row==null)throw IqcException.notFound("知识库不存在");
        if(row.getCreatedBy()==null||row.getCreatedBy().isBlank())throw IqcException.invalidState("知识库原归属来源缺失");
        if(!scope.canView(row.getCreatedBy(),row.getOwnerGroupId()))throw IqcException.accessDenied("无权读取知识库");return row;
    }
    private static void revision(KnowledgeBase row,int expected){if(expected<1||!Objects.equals(row.getDraftRevision(),expected))throw IqcException.invalidState("知识库草稿已变化，请重新加载");}
    private void update(KnowledgeBase row){if(bases.updateById(row)!=1)throw IqcException.invalidState("知识库保存失败");}
    private void narrow(KnowledgeBase row){if(!Objects.equals(row.getOwnerGroupId(),scope.groupId()))row.setOwnerGroupId(null);}
    private String corpus(KnowledgeCorpus value){if(value==null)throw IqcException.invalidArgument("缺少知识库语料");String body=write(value);
        if(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_BYTES)throw IqcException.invalidArgument("知识库语料超过2MiB");return body;}
    public IqcPage<KnowledgeBase> page(long current,long size){
        actor();if(current<1||size<1||size>100)throw IqcException.invalidArgument("知识库分页无效");
        var query=Wrappers.<KnowledgeBase>lambdaQuery();
        if(!scope.canViewAll()){String group=scope.groupId();query.and(q->{q.eq(KnowledgeBase::getCreatedBy,scope.owner());if(group!=null)q.or().eq(KnowledgeBase::getOwnerGroupId,group);});}
        query.select(KnowledgeBase.class,field->!"draftConfigJson".equals(field.getProperty()));
        return IqcPage.from(bases.selectPage(new Page<>(current,size),query.orderByDesc(KnowledgeBase::getCreatedTime).orderByDesc(KnowledgeBase::getId)));
    }
    public KnowledgeBase get(String id){return require(id,false);}
    @Transactional public KnowledgeBase create(String code,String name,KnowledgeCorpus corpus){
        actor();name(name);if(code==null||!code.matches("[A-Z][A-Z0-9_]{1,63}"))throw IqcException.invalidArgument("知识库编码无效");
        var row=new KnowledgeBase();row.setCode(code);row.setName(name.trim());row.setDraftConfigJson(corpus(corpus));row.setDraftRevision(1);
        row.setStatus("DISABLED");row.setOwnerGroupId(scope.groupId());if(bases.insert(row)!=1)throw IqcException.invalidState("知识库创建失败");return row;
    }
    @Transactional public KnowledgeBase update(String id,int expected,String name,KnowledgeCorpus corpus){
        var row=require(id,true);revision(row,expected);name(name);
        if(expected==Integer.MAX_VALUE)throw IqcException.invalidState("知识库修订已达上限");
        row.setName(name.trim());row.setDraftConfigJson(corpus(corpus));row.setDraftRevision(expected+1);narrow(row);update(row);return row;
    }
    public static Published readPublishedEvidence(KnowledgeBaseVersion version,ObjectMapper json){
        try{
            if(version==null||version.getId()==null||!version.getId().matches("[A-Za-z0-9:_-]{1,64}")||version.getKnowledgeBaseId()==null||!version.getKnowledgeBaseId().matches("[A-Za-z0-9:_-]{1,64}")||version.getVersionNo()==null||version.getVersionNo()<1
                    ||version.getSourceDraftRevision()==null||version.getSourceDraftRevision()<1||version.getSnapshotJson()==null
                    ||version.getSnapshotJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_BYTES
                    ||!Objects.equals(version.getContentHash(),SelectorAssetService.hash(version.getSnapshotJson())))throw new IllegalArgumentException();
            var value=read(json,version.getSnapshotJson(),Published.class);
            if(value==null||!"iqc-knowledge-release-v1".equals(value.schemaVersion())||value.corpus()==null||value.name()==null||value.name().isBlank()||value.name().length()>100
                    ||!Objects.equals(value.knowledgeBaseId(),version.getKnowledgeBaseId())||value.versionNo()!=version.getVersionNo()
                    ||value.sourceDraftRevision()!=version.getSourceDraftRevision()||value.owner()==null||value.owner().isBlank()
                    ||value.publishedBy()==null||value.publishedBy().isBlank()||!Objects.equals(value.publishedBy(),version.getCreatedBy()))throw new IllegalArgumentException();
            return value;
        }catch(Exception invalid){throw IqcException.invalidState("知识库发布版本来源或摘要无效");}
    }
    private KnowledgeBaseVersion version(KnowledgeBase row,int number){
        if(number<1)throw IqcException.invalidArgument("知识库版本无效");
        var value=versions.selectOne(Wrappers.<KnowledgeBaseVersion>lambdaQuery().eq(KnowledgeBaseVersion::getKnowledgeBaseId,row.getId())
                .eq(KnowledgeBaseVersion::getVersionNo,number));
        var published=readPublishedEvidence(value,json);
        if(!row.getId().equals(published.knowledgeBaseId())||published.versionNo()!=number||!Objects.equals(row.getCreatedBy(),published.owner()))throw IqcException.invalidState("知识库发布版本不属于原负责人");
        return value;
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<KnowledgeBaseVersion> history(String id){
        var row=require(id,false);var values=versions.selectList(Wrappers.<KnowledgeBaseVersion>lambdaQuery().eq(KnowledgeBaseVersion::getKnowledgeBaseId,id)
                .orderByDesc(KnowledgeBaseVersion::getVersionNo).last("LIMIT 100"));
        for(var value:values){var source=readPublishedEvidence(value,json);if(!id.equals(source.knowledgeBaseId())||!Objects.equals(row.getCreatedBy(),source.owner()))throw IqcException.invalidState("知识库历史来源不一致");}
        return values;
    }
    /** Bulk catalog reads declared version identities only; exact reads verify the original body before revealing it. */
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<KnowledgeBaseVersion> historyMetadata(String id){
        require(id,false);
        var query=Wrappers.<KnowledgeBaseVersion>lambdaQuery().eq(KnowledgeBaseVersion::getKnowledgeBaseId,id)
                .orderByDesc(KnowledgeBaseVersion::getVersionNo).last("LIMIT 100");
        query.select(KnowledgeBaseVersion.class,field->!"snapshotJson".equals(field.getProperty()));
        var values=versions.selectList(query);
        for(var value:values)if(!id.equals(value.getKnowledgeBaseId())||value.getId()==null||value.getVersionNo()==null||value.getVersionNo()<1
                ||value.getSourceDraftRevision()==null||value.getSourceDraftRevision()<1||value.getContentHash()==null
                ||!value.getContentHash().matches("[a-f0-9]{64}"))throw IqcException.invalidState("知识库历史元数据无效");
        return values;
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Prepared viewVersion(String id,int number){return prepared(require(id,false),number);}
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ,
            propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Binding referenceBinding(String id,int number){
        var row=require(id,false);if(!"ACTIVE".equals(row.getStatus()))throw IqcException.invalidState("知识库已停用");
        var value=prepared(row,number);return new Binding(id,value.snapshot().versionNo(),value.versionId(),value.contentHash());
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Prepared publishedForTask(String id,int number){var row=require(id,true);
        if(!"ACTIVE".equals(row.getStatus()))throw IqcException.invalidState("知识库已停用");return prepared(row,number);}
    /** Resolve the complete task reference set in a stable parent-lock order in the caller transaction. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public List<Prepared> publishedForTask(List<Binding> references){
        if(references==null||references.size()>400)throw IqcException.invalidArgument("任务知识库引用数量无效");
        var ordered=new TreeMap<String,TreeMap<Integer,Binding>>();
        for(var reference:references){
            if(reference==null||reference.knowledgeBaseId()==null||!reference.knowledgeBaseId().matches("[A-Za-z0-9:_-]{1,64}")
                    ||reference.versionNo()<1||reference.versionId()==null||!reference.versionId().matches("[A-Za-z0-9:_-]{1,64}")
                    ||reference.contentHash()==null||!reference.contentHash().matches("[a-f0-9]{64}"))
                throw IqcException.invalidArgument("任务知识库引用未精确绑定");
            var previous=ordered.computeIfAbsent(reference.knowledgeBaseId(),ignored->new TreeMap<>())
                    .putIfAbsent(reference.versionNo(),reference);
            if(previous!=null&&!previous.equals(reference))throw IqcException.invalidArgument("任务知识库版本引用冲突");
        }
        var result=new ArrayList<Prepared>();long frozenBytes=0;
        for(var entry:ordered.entrySet()){
            var row=require(entry.getKey(),true);
            if(!"ACTIVE".equals(row.getStatus()))throw IqcException.invalidState("知识库已停用");
            for(var reference:entry.getValue().values()){
                var value=prepared(row,reference.versionNo());
                if(!reference.versionId().equals(value.versionId())||!reference.contentHash().equals(value.contentHash()))
                    throw IqcException.invalidState("任务知识库版本来源已不匹配");
                frozenBytes+=write(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if(frozenBytes>8L*1024*1024)throw IqcException.invalidArgument("任务知识库正文冻结超过8MiB，请缩小引用语料");
                result.add(value);
            }
        }
        return List.copyOf(result);
    }
    private Prepared prepared(KnowledgeBase row,int number){
        var version=version(row,number);var snapshot=readPublishedEvidence(version,json);
        String group=Objects.equals(row.getOwnerGroupId(),snapshot.ownerGroupId())?row.getOwnerGroupId():null;
        return new Prepared(version.getId(),snapshot,version.getContentHash(),group);
    }
    @Transactional public KnowledgeBaseVersion publish(String id,int expected){
        var row=require(id,true);revision(row,expected);
        var previous=versions.selectOne(Wrappers.<KnowledgeBaseVersion>lambdaQuery().eq(KnowledgeBaseVersion::getKnowledgeBaseId,id)
                .eq(KnowledgeBaseVersion::getSourceDraftRevision,expected));
        if(previous!=null){var source=readPublishedEvidence(previous,json);if(!id.equals(source.knowledgeBaseId())||source.sourceDraftRevision()!=expected||!Objects.equals(row.getCreatedBy(),source.owner()))throw IqcException.invalidState("知识库发布来源无效");return previous;}
        int last=row.getActivePublishedVersion()==null?0:row.getActivePublishedVersion();
        if(last==Integer.MAX_VALUE)throw IqcException.invalidState("知识库发布版本已达上限");
        narrow(row);var value=new KnowledgeBaseVersion();value.setKnowledgeBaseId(id);value.setVersionNo(last+1);value.setSourceDraftRevision(expected);
        value.setSnapshotJson(write(new Published("iqc-knowledge-release-v1",id,last+1,expected,row.getName(),requestCorpus(row.getDraftConfigJson()),row.getCreatedBy(),row.getOwnerGroupId(),scope.owner())));
        if(value.getSnapshotJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_BYTES)throw IqcException.invalidArgument("知识库发布正文超过2MiB");
        value.setContentHash(SelectorAssetService.hash(value.getSnapshotJson()));if(versions.insert(value)!=1)throw IqcException.invalidState("知识库版本写入失败");
        row.setActivePublishedVersion(last+1);update(row);return value;
    }
    @Transactional public KnowledgeBase availability(String id,boolean enabled){
        var row=require(id,true);if(enabled){if(row.getActivePublishedVersion()==null)throw IqcException.invalidState("知识库须先发布版本");version(row,row.getActivePublishedVersion());}
        row.setStatus(enabled?"ACTIVE":"DISABLED");narrow(row);update(row);return row;
    }
    /** Restore into a new draft; active release and every immutable historical row remain untouched. */
    @Transactional public KnowledgeBase restore(String id,int expected,int number){
        var row=require(id,true);revision(row,expected);var source=readPublishedEvidence(version(row,number),json);
        return update(id,expected,source.name(),source.corpus());
    }
}
