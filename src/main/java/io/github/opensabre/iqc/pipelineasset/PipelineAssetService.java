package io.github.opensabre.iqc.pipelineasset;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.pipeline.PipelineDefinition;
import io.github.opensabre.iqc.pipeline.PipelineRuntime;
import io.github.opensabre.iqc.pipelineasset.dao.*;
import io.github.opensabre.iqc.pipelineasset.model.*;
import io.github.opensabre.iqc.finding.FindingCatalogService;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.finding.FindingDefinition;
import io.github.opensabre.iqc.rule.dao.*;
import io.github.opensabre.iqc.agent.dao.*;
import io.github.opensabre.iqc.agent.AgentAssetReferenceValidator;
import io.github.opensabre.iqc.agent.AgentConfiguration;
import io.github.opensabre.iqc.rule.RuleMatcher;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.shared.*;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
@RequiredArgsConstructor(onConstructor_=@org.springframework.beans.factory.annotation.Autowired)
public class PipelineAssetService {
    public static final String TASK_SCHEMA = "iqc-pipeline-task-v1";
    public record Prepared(String pipelineId, Integer versionNo, Integer draftRevision,
                           PipelineRelease release, String contentHash) { }
    private final PipelineAssetMapper assets;
    private final PipelineAssetVersionMapper versions;
    private final QualityRuleMapper rules;
    private final QualityRuleVersionMapper ruleVersions;
    private final QualityAgentMapper agents;
    private final QualityAgentVersionMapper agentVersions;
    private final AgentAssetReferenceValidator agentAssets;
    private final FindingCatalogService findings;
    private final ObjectMapper json;
    private final IqcDataScope scope;
    private final PipelineRuntime runtime;
    private final InspectionTaskMapper tasks;
    private final ConversationInspectionResultMapper results;
    private final org.springframework.beans.factory.ObjectProvider<PipelinePublicationGateService> publicationGates;

    private final org.springframework.beans.factory.ObjectProvider<io.github.opensabre.iqc.label.FindingLabelFreezeService> labelPolicies;
    private final org.springframework.beans.factory.ObjectProvider<PipelineRolloutExecutionGuard> rolloutGuards;
    public PipelineAssetService(PipelineAssetMapper assets,PipelineAssetVersionMapper versions,QualityRuleMapper rules,
            QualityRuleVersionMapper ruleVersions,QualityAgentMapper agents,QualityAgentVersionMapper agentVersions,
            AgentAssetReferenceValidator agentAssets,FindingCatalogService findings,ObjectMapper json,IqcDataScope scope,
            PipelineRuntime runtime,InspectionTaskMapper tasks,ConversationInspectionResultMapper results,
            org.springframework.beans.factory.ObjectProvider<PipelinePublicationGateService> publicationGates) {
        this.assets=assets;this.versions=versions;this.rules=rules;this.ruleVersions=ruleVersions;this.agents=agents;
        this.agentVersions=agentVersions;this.agentAssets=agentAssets;this.findings=findings;this.json=json;this.scope=scope;
        this.runtime=runtime;this.tasks=tasks;this.results=results;this.publicationGates=publicationGates;this.labelPolicies=null;this.rolloutGuards=null;
    }

    public PipelineAssetService(PipelineAssetMapper assets,PipelineAssetVersionMapper versions,QualityRuleMapper rules,
            QualityRuleVersionMapper ruleVersions,QualityAgentMapper agents,QualityAgentVersionMapper agentVersions,
            AgentAssetReferenceValidator agentAssets,FindingCatalogService findings,ObjectMapper json,IqcDataScope scope,
            PipelineRuntime runtime,InspectionTaskMapper tasks,ConversationInspectionResultMapper results,
            org.springframework.beans.factory.ObjectProvider<PipelinePublicationGateService> publicationGates,
            org.springframework.beans.factory.ObjectProvider<io.github.opensabre.iqc.label.FindingLabelFreezeService> labelPolicies){
        this(assets,versions,rules,ruleVersions,agents,agentVersions,agentAssets,findings,json,scope,runtime,tasks,results,publicationGates,labelPolicies,null);
    }

    public IqcPage<PipelineAsset> page(long current, long size) {
        if (current < 1 || size < 1 || size > 100) throw IqcException.invalidArgument("无效流水线分页");
        var query = Wrappers.<PipelineAsset>lambdaQuery();
        if (!scope.canViewAll()) { String group = scope.groupId(); query.and(q -> {
            q.eq(PipelineAsset::getCreatedBy, scope.owner()); if (group != null) q.or().eq(PipelineAsset::getOwnerGroupId, group);
        }); }
        return IqcPage.from(assets.selectPage(new Page<>(current,size), query.orderByDesc(PipelineAsset::getCreatedTime)));
    }
    public PipelineAsset get(String id) { return require(id,false); }
    @Transactional public PipelineAsset create(String code,String name,PipelineRelease release) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{1,63}")) throw IqcException.invalidArgument("无效流水线编码");
        validateName(name); freeze(release);
        var asset = new PipelineAsset(); asset.setCode(code); asset.setName(name.trim()); asset.setDraftRevision(1);
        asset.setDraftConfigJson(write(release)); asset.setStatus("ACTIVE"); asset.setOwnerGroupId(scope.groupId());
        assets.insert(asset); return asset;
    }
    @Transactional public PipelineAsset update(String id,int revision,String name,PipelineRelease release) {
        var asset=require(id,true); checkRevision(asset,revision); validateName(name); freeze(release);
        asset.setName(name.trim()); asset.setDraftConfigJson(write(release)); asset.setDraftRevision(revision+1);
        assets.updateById(asset); return asset;
    }
    @Transactional public PipelineAsset availability(String id,boolean enabled) {
        var asset=require(id,true); asset.setStatus(enabled?"ACTIVE":"DISABLED"); assets.updateById(asset); return asset;
    }
    public List<PipelineAssetVersion> history(String id) {
        require(id,false); var history=versions.selectList(Wrappers.<PipelineAssetVersion>lambdaQuery()
                .eq(PipelineAssetVersion::getPipelineId,id).orderByDesc(PipelineAssetVersion::getVersionNo).last("LIMIT 100"));
        history.forEach(this::validatePublicationEvidence); return history;
    }
    public Prepared frozenDraft(String id,int revision) {
        var asset=require(id,false); checkRevision(asset,revision); requireActive(asset);
        var release=freeze(read(asset.getDraftConfigJson()));
        return new Prepared(id,null,revision,release,hash(write(release)));
    }
    public Prepared publishedRelease(String id,int versionNo) {return publishedRelease(id,versionNo,false);}
    public Prepared publishedReleaseForTask(String id,int versionNo) {return publishedRelease(id,versionNo,true);}
    private Prepared publishedRelease(String id,int versionNo,boolean lock){return publishedRelease(id,versionNo,lock,true);}
    private Prepared publishedRelease(String id,int versionNo,boolean lock,boolean executionAvailable) {
        var asset=require(id,lock); requireActive(asset);
        var version=versions.selectOne(Wrappers.<PipelineAssetVersion>lambdaQuery().eq(PipelineAssetVersion::getPipelineId,id)
                .eq(PipelineAssetVersion::getVersionNo,versionNo).last(lock,"FOR UPDATE"));
        if(version==null) throw IqcException.notFound("流水线发布版本不存在");
        if(!hash(version.getSnapshotJson()).equals(version.getContentHash())) throw IqcException.invalidState("流水线版本摘要不一致");
        validatePublicationEvidence(version);
        var release=read(version.getSnapshotJson()); validateFrozen(release,executionAvailable);
        return new Prepared(id,versionNo,null,release,version.getContentHash());
    }
    /** Does not save a policy or create/route a task. Both versions remain original publication evidence. */
    public PipelineRolloutPolicy.Decision previewRollout(String id,PipelineRolloutPolicy policy,String requestKey){
        if(policy==null||!id.equals(policy.pipelineId()))throw IqcException.invalidArgument("灰度策略流水线不匹配");
        var actorId=scope.actorUserId();
        var sources=rolloutSources(id,policy);
        return policy.preview(actorId,requestKey,sources.baseline(),sources.candidate(),json);
    }
    record RolloutSources(Prepared baseline,Prepared candidate) { }
    RolloutSources rolloutSources(String id,PipelineRolloutPolicy policy){return rolloutSources(id,policy,false);}
    /** Owning append/materialization workflows already hold the asset lock in a local transaction. */
    RolloutSources rolloutSourcesForTask(String id,PipelineRolloutPolicy policy){return rolloutSources(id,policy,true);}
    private RolloutSources rolloutSources(String id,PipelineRolloutPolicy policy,boolean lock){
        if(policy==null||!id.equals(policy.pipelineId()))throw IqcException.invalidArgument("灰度策略流水线不匹配");
        return new RolloutSources(publishedRelease(id,policy.baselineVersionNo(),lock),
                publishedRelease(id,policy.candidateVersionNo(),lock,policy.state()!=PipelineRolloutPolicy.State.ROLLED_BACK));
    }
    /** Shares the original asset lock with publication and availability changes. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void lockForRollout(String id){requireActive(lockRolloutAsset(id));}
    /** A replay may read its original task while the asset is disabled; new release resolution remains active-only. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void lockForRolloutRequest(String id){lockRolloutAsset(id);}
    private PipelineAsset lockRolloutAsset(String id){
        var asset=require(id,true);
        if(!java.util.Objects.equals(id,asset.getId()))throw IqcException.invalidArgument("流水线标识必须与原资产一致");
        return asset;
    }
    @Transactional public PipelineAssetVersion publish(String id,int revision,String trialTaskId,boolean reviewed) {
        return publishMeasured(id,revision,trialTaskId,reviewed,null,null,false,null);
    }
    public PipelinePublicationGateService.Decision publicationPreview(String id,int revision,
            PipelinePublicationGateService.ExecutionEvidence quality,PipelinePublicationGateService.ExecutionEvidence regression) {
        var prepared=frozenDraft(id,revision);
        return publicationGates.getObject().evaluate(id,revision,prepared.contentHash(),prepared.release(),quality,regression);
    }
    @Transactional public PipelineAssetVersion publishMeasured(String id,int revision,String trialTaskId,boolean reviewed,
            PipelinePublicationGateService.ExecutionEvidence quality,PipelinePublicationGateService.ExecutionEvidence regression,
            boolean force,String forceReason) {
        if("system".equals(scope.owner())) throw IqcException.accessDenied("发布需要已认证操作者");
        if(force && !scope.isIqcAdministrator()) throw IqcException.accessDenied("强制发布仅限可信管理员角色");
        if(force && (forceReason==null || forceReason.trim().isEmpty() || forceReason.length()>1000))
            throw IqcException.invalidArgument("管理员强制发布必须填写1–1000字原因");
        if(!force && forceReason!=null && !forceReason.isBlank()) throw IqcException.invalidArgument("普通发布不能提交强制发布原因");
        var asset=require(id,true); checkRevision(asset,revision); requireActive(asset);
        var prior=versions.selectOne(Wrappers.<PipelineAssetVersion>lambdaQuery().eq(PipelineAssetVersion::getPipelineId,id)
                .eq(PipelineAssetVersion::getSourceDraftRevision,revision));
        if(prior!=null) { validatePublicationEvidence(prior); return prior; }
        var release=freeze(read(asset.getDraftConfigJson())); String body=write(release), digest=hash(body);
        var trial=trialTaskId==null?null:tasks.selectById(trialTaskId);
        if(!reviewed || trial==null || !scope.canView(trial.getCreatedBy(),trial.getOwnerGroupId()) || !"SUCCEEDED".equals(trial.getStatus()))
            throw IqcException.invalidState("发布需要已完成且人工确认的真实试跑");
        var marker=parse(trial.getRuleSnapshotJson()).path("pipelineSnapshot");
        if(!id.equals(marker.path("pipelineId").asText()) || marker.path("draftRevision").asInt(-1)!=revision
                || !digest.equals(marker.path("contentHash").asText()) || !marker.path("trial").asBoolean())
            throw IqcException.invalidState("试跑不属于当前流水线修订和冻结内容");
        var decisions=new ArrayList<ConversationInspectionResult>();
        var selected=parse(trial.getConversationIdsJson());
        for(var conversation:selected) {
            var result=results.selectLatestForTaskConversation(trial.getId(),conversation.asText());
            if(result!=null) decisions.add(result);
        }
        if(decisions.size()!=selected.size() || decisions.isEmpty() || decisions.stream().anyMatch(value ->
                !List.of("FINAL","NOT_APPLICABLE").contains(value.getScoreStatus())
                        || value.getExecutionTraceJson()==null || !"SUCCESS".equals(parse(value.getExecutionTraceJson()).path("run").path("status").asText())))
            throw IqcException.invalidState("试跑会话未全部形成完整业务结果");
        var gate=publicationGates.getObject().evaluate(id,revision,digest,release,quality,regression);
        if(!gate.passed() && !force) throw IqcException.invalidState("发布门禁未通过，请查看测量准入报告");
        var publication=json.createObjectNode().put("schemaVersion","iqc-pipeline-publication-v1")
                .put("operator",scope.owner()).put("recordedAt",java.time.Instant.now().toString()).put("forced",force);
        if(force) publication.put("forceReason",forceReason.trim());
        publication.set("gate",json.valueToTree(gate));
        String admission=publication.toString();
        if(admission.getBytes(StandardCharsets.UTF_8).length>8*1024*1024) throw IqcException.invalidState("发布证据超过8MiB");
        var version=new PipelineAssetVersion(); version.setPipelineId(id); version.setSourceDraftRevision(revision);
        version.setVersionNo(asset.getActivePublishedVersion()==null?1:asset.getActivePublishedVersion()+1);
        version.setSnapshotJson(body); version.setContentHash(digest);
        version.setPublicationDecisionJson(admission); version.setPublicationDecisionHash(hash(admission)); versions.insert(version);
        asset.setActivePublishedVersion(version.getVersionNo()); assets.updateById(asset); return version;
    }
    private void validatePublicationEvidence(PipelineAssetVersion version) {validatePublicationEvidence(version,json);}
    static void validatePublicationEvidence(PipelineAssetVersion version,ObjectMapper json) {
        String body=version.getPublicationDecisionJson(), digest=version.getPublicationDecisionHash();
        if(body==null && digest==null) return; // Versions published before this migration.
        if(body==null || digest==null || !hash(body).equals(digest))
            throw IqcException.invalidState("流水线发布证据摘要不一致");
        JsonNode admission;
        try{admission=json.readTree(body);}catch(Exception invalid){throw IqcException.invalidState("流水线JSON无效");}
        var gate=admission.path("gate");
        if(!"iqc-pipeline-publication-v1".equals(admission.path("schemaVersion").asText())
                || !"iqc-publication-gate-decision-v1".equals(gate.path("schemaVersion").asText())
                || !version.getPipelineId().equals(gate.path("pipelineId").asText())
                || gate.path("draftRevision").asInt(-1)!=version.getSourceDraftRevision()
                || !version.getContentHash().equals(gate.path("releaseHash").asText()))
            throw IqcException.invalidState("流水线发布证据协议或来源不一致");
    }
    public void validateTask(JsonNode snapshot) { validateTask(snapshot,true,null); }
    public void validateTask(JsonNode snapshot,String expectedTaskId) { validateTask(snapshot,true,expectedTaskId); }
    private void validateTask(JsonNode snapshot,boolean requireExecutionAvailability,String expectedTaskId) {
        validateRolloutEvidence(snapshot,expectedTaskId);
        if(!isPipeline(snapshot)) return;
        if(!TASK_SCHEMA.equals(snapshot.path("executionStrategy").path("schemaVersion").asText())
                || snapshot.has("schemeSnapshot")) throw IqcException.invalidState("流水线任务协议无效");
        var marker=snapshot.path("pipelineSnapshot");
        if(requireExecutionAvailability) {
            var asset=assets.selectById(marker.path("pipelineId").asText()); // worker checks availability, not current user scope
            if(asset==null) throw IqcException.invalidState("流水线资产不存在"); requireActive(asset);
        }
        JsonNode releaseNode=marker.path("release");
        if(!hash(releaseNode.toString()).equals(marker.path("contentHash").asText())) throw IqcException.invalidState("任务流水线摘要不一致");
        validateFrozen(convert(releaseNode),requireExecutionAvailability);
    }
    private void validateRolloutEvidence(JsonNode snapshot,String expectedTaskId){
        boolean trigger=expectedTaskId!=null&&expectedTaskId.matches("t[a-f0-9]{63}");
        if(!trigger&&!PipelineRolloutTaskEvidence.requiresEvidence(expectedTaskId)&&(snapshot==null||!snapshot.has("rolloutSnapshot")))return;
        var guard=rolloutGuards==null?null:rolloutGuards.getIfAvailable();
        if(guard==null)throw IqcException.invalidState("灰度任务原始证据校验组件不可用");
        guard.validate(snapshot,expectedTaskId);
    }
    public static boolean isPipeline(JsonNode snapshot) { return snapshot!=null && snapshot.has("pipelineSnapshot"); }
    public PipelineRelease taskRelease(JsonNode snapshot) { validateTask(snapshot); return convert(snapshot.path("pipelineSnapshot").path("release")); }

    /** Worker validation of an actual published production snapshot, without a thread user scope. */
    public PipelineRelease publishedTaskRelease(JsonNode snapshot) { return publishedTaskRelease(snapshot,true,null); }
    public PipelineRelease publishedTaskRelease(JsonNode snapshot,String expectedTaskId) { return publishedTaskRelease(snapshot,true,expectedTaskId); }
    /** Immutable production publication proof; current availability gates execution, not historical audit. */
    public PipelineRelease historicalPublishedTaskRelease(JsonNode snapshot) { return publishedTaskRelease(snapshot,false,null); }
    public PipelineRelease historicalPublishedTaskRelease(JsonNode snapshot,String expectedTaskId) { return publishedTaskRelease(snapshot,false,expectedTaskId); }
    private PipelineRelease publishedTaskRelease(JsonNode snapshot,boolean requireExecutionAvailability,String expectedTaskId) {
        if (!isPipeline(snapshot) || snapshot.has("evaluationSnapshot"))
            throw IqcException.invalidState("动作来源必须是已发布生产流水线");
        validateTask(snapshot,requireExecutionAvailability,expectedTaskId);
        var marker=snapshot.path("pipelineSnapshot");
        if (!"PIPELINE".equals(snapshot.path("executionStrategy").path("mode").asText())
                || !marker.path("trial").isBoolean() || marker.path("trial").booleanValue()
                || !marker.path("versionNo").isIntegralNumber() || !marker.path("versionNo").canConvertToInt()
                || marker.path("versionNo").intValue()<1
                || (marker.has("draftRevision") && !marker.path("draftRevision").isNull()))
            throw IqcException.invalidState("动作来源缺少生产发布版本");
        var version=versions.selectOne(Wrappers.<PipelineAssetVersion>lambdaQuery()
                .eq(PipelineAssetVersion::getPipelineId,marker.path("pipelineId").asText())
                .eq(PipelineAssetVersion::getVersionNo,marker.path("versionNo").intValue()));
        if (version==null || !hash(version.getSnapshotJson()).equals(version.getContentHash())
                || !Objects.equals(version.getContentHash(),marker.path("contentHash").asText())
                || !(snapshot.has("rolloutSnapshot")?parseExact(version.getSnapshotJson()):parse(version.getSnapshotJson())).equals(marker.path("release")))
            throw IqcException.invalidState("动作来源与发布流水线不一致");
        validatePublicationEvidence(version);
        return convert(marker.path("release"));
    }

    private PipelineRelease freeze(PipelineRelease release) {
        if(release==null) throw IqcException.invalidArgument("流水线配置不能为空");
        var nodes=new ArrayList<PipelineDefinition.Node>(); var codes=new HashSet<String>();
        for(var node:release.pipeline().nodes()) {
            if(node.config()!=null && !node.config().isObject()) throw IqcException.invalidArgument("节点配置必须为对象");
            var config=node.config()==null?json.createObjectNode():(ObjectNode)node.config();
            if("METRIC".equals(node.type())) {
                var reference=config.path("findingReference");validateReference(reference);
                var definition=findings.publishedDefinition(reference.path("id").asText(),reference.path("versionNo").intValue());
                config.set("findingDefinition",json.valueToTree(definition));
                try { io.github.opensabre.iqc.pipelinebridge.TranscriptMetricEngine.validateConfiguration(node.version(),config,definition); }
                catch(IllegalArgumentException invalid) {throw IqcException.invalidArgument("转写指标配置、版本或事实定义无效");}
                codes.add(definition.code());
                nodes.add(new PipelineDefinition.Node(node.nodeId(),node.type(),node.name(),
                        io.github.opensabre.iqc.pipelinebridge.TranscriptMetricEngine.VERSION,node.dependsOn(),node.inputMapping(),
                        config,node.condition(),node.timeoutMs(),node.retries(),node.onError(),node.fallbackNodeId(),node.outputSchema(),node.enabled()));
                continue;
            }
            if(Set.of("SKILL_API","SMALL_MODEL").contains(node.type())) {
                boolean inference="SMALL_MODEL".equals(node.type());
                freezeStructuredTool(config,node.version(),inference);
                codes.add(config.path("findingDefinition").path("code").asText());
                nodes.add(new PipelineDefinition.Node(node.nodeId(),node.type(),node.name(),
                        (inference?io.github.opensabre.iqc.pipelinebridge.SmallModelContract.VERSION:io.github.opensabre.iqc.pipelinebridge.SkillApiDetectorEngine.VERSION),node.dependsOn(),node.inputMapping(),
                        config,node.condition(),node.timeoutMs(),node.retries(),node.onError(),node.fallbackNodeId(),node.outputSchema(),node.enabled()));
                continue;
            }
            boolean session="SESSION".equals(node.type());
            var allowed=session?Set.of("ruleReference","findingReference","aggregation","countDefinition","ruleSnapshot","findingDefinition")
                    :Set.of("ruleReference","findingReference","agentReference","inputScope","phase","ruleSnapshot","findingDefinition","agentSnapshot");
            for(var field:config.properties()) if(!allowed.contains(field.getKey())) throw IqcException.invalidArgument("当前引擎不支持该节点配置字段");
            var ref=config.path("ruleReference"); validateReference(ref);
            String ruleId=ref.path("id").asText(); int number=ref.path("versionNo").asInt();
            var current=rules.selectById(ruleId);
            var version=ruleVersions.selectOne(Wrappers.<io.github.opensabre.iqc.rule.model.QualityRuleVersion>lambdaQuery()
                    .eq(io.github.opensabre.iqc.rule.model.QualityRuleVersion::getRuleId,ruleId)
                    .eq(io.github.opensabre.iqc.rule.model.QualityRuleVersion::getVersionNo,number)
                    .eq(io.github.opensabre.iqc.rule.model.QualityRuleVersion::getStatus,"PUBLISHED"));
            if(current==null || "DISABLED".equals(current.getStatus()) || version==null) throw IqcException.invalidArgument("节点必须引用可用的已发布规则版本");
            String type="LLM".equalsIgnoreCase(version.getRuleType())?"LLM":"DLS".equalsIgnoreCase(version.getRuleType())?"DLS":"RULE";
            if(session && "DLS".equals(type))type="SESSION";
            if(!type.equals(node.type())) throw IqcException.invalidArgument("节点引擎与规则类型不一致");
            if(!Set.of("DIRECT","REVIEW","VERIFY").contains(config.path("phase").asText("DIRECT"))
                    || !Set.of("MESSAGE","CONVERSATION").contains(config.path("inputScope").asText("RULE".equals(type)?"MESSAGE":"CONVERSATION")))
                throw IqcException.invalidArgument("节点阶段或输入范围无效");
            RuleMatcher.validate(version.getRuleType(),version.getExpression(),json);
            var frozen=(ObjectNode)json.valueToTree(version); frozen.put("id",ruleId).put("deduction",0).put("veto",false);
            config.set("ruleSnapshot",frozen);
            var findingRef=config.path("findingReference"); validateReference(findingRef);
            FindingDefinition definition=findings.publishedDefinition(findingRef.path("id").asText(),findingRef.path("versionNo").asInt());
            if(!session && definition.valueType()!=Finding.ValueType.BOOLEAN) throw IqcException.invalidArgument("当前规则适配器仅输出布尔事实，其他类型需对应引擎");
            config.set("findingDefinition",json.valueToTree(definition)); codes.add(definition.code());
            config.remove("agentSnapshot");
            if(session) {
                try {io.github.opensabre.iqc.pipelinebridge.SessionAggregationEngine.validateConfiguration(json,node.version(),config,definition);}
                catch(IllegalArgumentException invalid){throw IqcException.invalidArgument("会话聚合版本、配置或事实定义无效");}
            }
            if("LLM".equals(type)) {
                var agentRef=config.path("agentReference"); validateReference(agentRef); String agentId=agentRef.path("id").asText();
                var agent=agents.selectById(agentId);
                var av=agentVersions.selectOne(Wrappers.<io.github.opensabre.iqc.agent.model.QualityAgentVersion>lambdaQuery()
                        .eq(io.github.opensabre.iqc.agent.model.QualityAgentVersion::getAgentId,agentId)
                        .eq(io.github.opensabre.iqc.agent.model.QualityAgentVersion::getVersionNo,agentRef.path("versionNo").asInt())
                        .eq(io.github.opensabre.iqc.agent.model.QualityAgentVersion::getStatus,"PUBLISHED"));
                if(agent==null || "DISABLED".equals(agent.getStatus()) || av==null) throw IqcException.invalidArgument("LLM节点必须引用可用已发布智能体");
                var agentConfig=parse(av.getConfigJson());
                if(!"3.0".equals(agentConfig.path("schemaVersion").asText()) || agentConfig.path("assetSnapshots").path("mcpServers").size()>0)
                    throw IqcException.invalidArgument("检测流水线请引用无副作用MCP工具的新版LLM能力，业务动作另行执行");
                try { agentAssets.validate(json.treeToValue(agentConfig,AgentConfiguration.class)); }
                catch(com.fasterxml.jackson.core.JsonProcessingException ex) { throw IqcException.invalidArgument("智能体配置无效"); }
                var body=(ObjectNode)json.valueToTree(av); body.put("id",agentId); config.set("agentSnapshot",body);
            }
            nodes.add(new PipelineDefinition.Node(node.nodeId(),node.type(),node.name(),session?io.github.opensabre.iqc.pipelinebridge.SessionAggregationEngine.VERSION:String.valueOf(number),node.dependsOn(),
                    node.inputMapping(),config,node.condition(),node.timeoutMs(),node.retries(),node.onError(),node.fallbackNodeId(),node.outputSchema(),node.enabled()));
        }
        var findingTypes=labelFindingTypes(new PipelineDefinition(release.pipeline().schemaVersion(),nodes,release.pipeline().completionPolicy()));
        try {release.validateFindingTypes(findingTypes);}
        catch(IllegalArgumentException invalid){throw IqcException.invalidArgument("业务项目与适用条件的谓词必须匹配事实类型：布尔直接判断，数值需要明确阈值");}
        for(var item:release.items()) if(!codes.contains(item.findingCode())
                || item.appliesWhenFindingCode()!=null && !codes.contains(item.appliesWhenFindingCode()))
            throw IqcException.invalidArgument("业务项目或适用条件引用未声明的事实编码");
        if(release.riskPolicy()!=null)release.riskPolicy().validateReferences(codes,
                release.items().stream().map(PipelineRelease.ItemMapping::itemCode).collect(java.util.stream.Collectors.toSet()),release.labelPolicy());
        if(release.actionPolicy()!=null)release.actionPolicy().validateReferences(codes,
                release.items().stream().map(PipelineRelease.ItemMapping::itemCode).collect(java.util.stream.Collectors.toSet()));
        io.github.opensabre.iqc.label.FindingLabelPolicy labelPolicy=null;
        if(release.labelPolicy()!=null) {
            release.labelPolicy().validateReferences(codes);
            if(labelPolicies==null)throw IqcException.invalidState("标签目录冻结服务未配置");
            labelPolicy=labelPolicies.getObject().freeze(release.labelPolicy());
            labelPolicy.validateFindingTypes(findingTypes);
        }
        if(release.reviewPolicy()!=null)release.reviewPolicy().validateReferences(codes,release.items().stream().map(PipelineRelease.ItemMapping::itemCode).collect(java.util.stream.Collectors.toSet()),labelPolicy,release.actionPolicy());
        release.scoring().validateReferences(codes,release.items().stream().map(PipelineRelease.ItemMapping::itemCode).collect(java.util.stream.Collectors.toSet()),labelPolicy);
        io.github.opensabre.iqc.label.FindingLabelActionConfiguration.validateDeclaredActions(release.actionPolicy(),labelPolicy);
        var frozen=new PipelineRelease(release.schemaVersion(),new PipelineDefinition(release.pipeline().schemaVersion(),nodes,release.pipeline().completionPolicy()),release.fusion(),release.scoring(),release.items(),release.publicationGate(),release.actionPolicy(),labelPolicy,release.riskPolicy(),release.reviewPolicy());
        runtime.validate(frozen.pipeline()); return frozen;
    }
    private static Map<String,Finding.ValueType> labelFindingTypes(PipelineDefinition pipeline) {
        var types=new HashMap<String,Finding.ValueType>();
        for(var node:pipeline.nodes()) {
            var definition=node.config().path("findingDefinition");
            var type=Finding.ValueType.valueOf(definition.path("valueType").asText());
            var previous=types.putIfAbsent(definition.path("code").asText(),type);
            if(previous!=null && previous!=type)throw IqcException.invalidState("同一事实编码的冻结类型不一致");
        }
        return types;
    }
    private void validateFrozen(PipelineRelease release) { validateFrozen(release,true); }
    private void validateFrozen(PipelineRelease release,boolean requireExecutionAvailability) {
        if(requireExecutionAvailability) {
            runtime.validate(release.pipeline());
            try {release.validateFindingTypes(labelFindingTypes(release.pipeline()));}
            catch(IllegalArgumentException invalid){throw IqcException.invalidState("冻结业务比较与事实类型不一致");}
        } else runtime.validateStructure(release.pipeline());
        if(release.labelPolicy()!=null) {
            release.labelPolicy().validateFrozen();
            release.labelPolicy().validateFindingTypes(labelFindingTypes(release.pipeline()));
            release.labelPolicy().validateReferences(release.pipeline().nodes().stream()
                    .map(node->node.config().path("findingDefinition").path("code").asText()).collect(java.util.stream.Collectors.toSet()));
        }
        if(release.actionPolicy()!=null)release.actionPolicy().validateReferences(
                release.pipeline().nodes().stream().map(node->node.config().path("findingDefinition").path("code").asText()).collect(java.util.stream.Collectors.toSet()),
                release.items().stream().map(PipelineRelease.ItemMapping::itemCode).collect(java.util.stream.Collectors.toSet()));
        if(release.riskPolicy()!=null)release.riskPolicy().validateReferences(release.pipeline().nodes().stream()
                .map(node->node.config().path("findingDefinition").path("code").asText()).collect(java.util.stream.Collectors.toSet()),
                release.items().stream().map(PipelineRelease.ItemMapping::itemCode).collect(java.util.stream.Collectors.toSet()),release.labelPolicy());
        release.scoring().validateReferences(release.pipeline().nodes().stream().map(node->node.config().path("findingDefinition").path("code").asText()).collect(java.util.stream.Collectors.toSet()),
                release.items().stream().map(PipelineRelease.ItemMapping::itemCode).collect(java.util.stream.Collectors.toSet()),release.labelPolicy());
        if(release.reviewPolicy()!=null)release.reviewPolicy().validateReferences(release.pipeline().nodes().stream().map(node->node.config().path("findingDefinition").path("code").asText()).collect(java.util.stream.Collectors.toSet()),
                release.items().stream().map(PipelineRelease.ItemMapping::itemCode).collect(java.util.stream.Collectors.toSet()),release.labelPolicy(),release.actionPolicy());
        if(!requireExecutionAvailability)return;
        for(var node:release.pipeline().nodes()) {
            if("METRIC".equals(node.type())) {
                var config=node.config();var reference=config.path("findingReference");validateReference(reference);
                findings.publishedDefinitionForExecution(reference.path("id").asText(),reference.path("versionNo").intValue());
                try {
                    io.github.opensabre.iqc.pipelinebridge.TranscriptMetricEngine.validateConfiguration(node.version(),config,
                            json.treeToValue(config.path("findingDefinition"),FindingDefinition.class));
                } catch(com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException invalid) {
                    throw IqcException.invalidState("已发布转写指标冻结定义无效");
                }
                continue;
            }
            if("SMALL_MODEL".equals(node.type())) {
                var config=node.config();
                try {io.github.opensabre.iqc.pipelinebridge.SmallModelContract.validateConfiguration(node.version(),config,
                        json.treeToValue(config.path("findingDefinition"),FindingDefinition.class));}
                catch(com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException invalid){throw IqcException.invalidState("已发布专用模型协议无效");}
                var ref=config.path("findingReference");findings.publishedDefinitionForExecution(ref.path("id").asText(),ref.path("versionNo").intValue());
                var agent=agents.selectById(config.path("agentReference").path("id").asText());
                if(agent==null || "DISABLED".equals(agent.getStatus()))throw IqcException.invalidState("专用模型智能体已停用");
                agentAssets.validateInferenceCapabilities(config.path("modelSnapshot").path("id").asText(),config.path("mcpSnapshot").path("id").asText());
                continue;
            }
            if("SKILL_API".equals(node.type())) {
                var config=node.config();
                try {io.github.opensabre.iqc.pipelinebridge.SkillApiDetectorEngine.validateConfiguration(node.version(),config);}
                catch(IllegalArgumentException invalid){throw IqcException.invalidState("Skill/API冻结协议无效");}
                var reference=config.path("findingReference");validateReference(reference);
                findings.publishedDefinitionForExecution(reference.path("id").asText(),reference.path("versionNo").intValue());
                var agent=agents.selectById(config.path("agentReference").path("id").asText());
                if(agent==null || "DISABLED".equals(agent.getStatus()))throw IqcException.invalidState("Skill/API智能体已停用");
                agentAssets.validateDetectionCapabilities(config.path("skillSnapshot").path("id").asText(),config.path("mcpSnapshot").path("id").asText());
                continue;
            }
            var config=node.config(); var rule=config.path("ruleSnapshot"); var current=rules.selectById(rule.path("id").asText());
            if(current==null || "DISABLED".equals(current.getStatus())) throw IqcException.invalidState("流水线依赖规则已停用");
            // Check reference availability while retaining the frozen body.
            var ref=config.path("findingReference"); findings.publishedDefinitionForExecution(ref.path("id").asText(),ref.path("versionNo").asInt());
            if("SESSION".equals(node.type())) {
                validateReference(config.path("ruleReference"));validateReference(ref);
                try {io.github.opensabre.iqc.pipelinebridge.SessionAggregationEngine.validateConfiguration(json,node.version(),config,
                        json.treeToValue(config.path("findingDefinition"),FindingDefinition.class));}
                catch(com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException invalid){throw IqcException.invalidState("已发布会话聚合冻结定义无效");}
            }
            if("LLM".equals(node.type())) {
                var agent=agents.selectById(config.path("agentSnapshot").path("id").asText());
                if(agent==null || "DISABLED".equals(agent.getStatus())) throw IqcException.invalidState("流水线智能体已停用");
            }
        }
    }
    /** Freezes Skill/MCP bodies from one published Agent version without remote inspection or latest substitution. */
    private void freezeSkillApi(ObjectNode config,String version) {freezeStructuredTool(config,version,false);}
    private void freezeStructuredTool(ObjectNode config,String version,boolean inference) {
        var allowed=new java.util.HashSet<>(Set.of("agentReference","skillReference","mcpReference","findingReference","toolName",
                "toolInputSchema","toolOutputSchema","skillSnapshot","mcpSnapshot","findingDefinition","inputHashAlgorithm"));
        if(inference){allowed.remove("skillReference");allowed.remove("skillSnapshot");
            allowed.addAll(Set.of("modelReference","modelSnapshot","operation","inputSchema","outputSchema"));}
        for(var field:config.properties())if(!allowed.contains(field.getKey()))throw IqcException.invalidArgument("Skill/API不支持该配置字段");
        if(!config.has("inputHashAlgorithm"))config.put("inputHashAlgorithm",io.github.opensabre.iqc.pipelinebridge.StructuredToolInputHash.EXACT);
        io.github.opensabre.iqc.pipelinebridge.StructuredToolInputHash.validate(config);
        var agentRef=config.path("agentReference");validateReference(agentRef);
        var current=agents.selectById(agentRef.path("id").asText());
        var published=agentVersions.selectOne(Wrappers.<io.github.opensabre.iqc.agent.model.QualityAgentVersion>lambdaQuery()
                .eq(io.github.opensabre.iqc.agent.model.QualityAgentVersion::getAgentId,agentRef.path("id").asText())
                .eq(io.github.opensabre.iqc.agent.model.QualityAgentVersion::getVersionNo,agentRef.path("versionNo").intValue())
                .eq(io.github.opensabre.iqc.agent.model.QualityAgentVersion::getStatus,"PUBLISHED"));
        if(current==null || "DISABLED".equals(current.getStatus()) || published==null)throw IqcException.invalidArgument("Skill/API须引用可用已发布智能体版本");
        var agentConfig=parse(published.getConfigJson());
        if(!"3.0".equals(agentConfig.path("schemaVersion").asText()))throw IqcException.invalidArgument("Skill/API须使用新版冻结能力资产");
        if(inference) {
            var snapshots=agentConfig.path("assetSnapshots");var models=json.createArrayNode();
            if(snapshots.path("primaryModel").isObject())models.add(snapshots.path("primaryModel"));
            if(snapshots.path("fallbackModels").isArray())for(var model:snapshots.path("fallbackModels"))models.add(model);
            config.set("modelSnapshot",frozenCapability(models,config.path("modelReference")));
        } else config.set("skillSnapshot",frozenCapability(agentConfig.path("assetSnapshots").path("skills"),config.path("skillReference")));
        config.set("mcpSnapshot",frozenCapability(agentConfig.path("assetSnapshots").path("mcpServers"),config.path("mcpReference")));
        if(inference)agentAssets.validateInferenceCapabilities(config.path("modelSnapshot").path("id").asText(),config.path("mcpSnapshot").path("id").asText());
        else agentAssets.validateDetectionCapabilities(config.path("skillSnapshot").path("id").asText(),config.path("mcpSnapshot").path("id").asText());
        var reference=config.path("findingReference");validateReference(reference);
        config.set("findingDefinition",json.valueToTree(findings.publishedDefinition(reference.path("id").asText(),reference.path("versionNo").intValue())));
        try {
            if(inference) {
                io.github.opensabre.iqc.pipelinebridge.SmallModelContract.validateConfiguration(version,config,
                        json.treeToValue(config.path("findingDefinition"),FindingDefinition.class));
                return;
            }
            io.github.opensabre.iqc.pipelinebridge.SkillApiDetectorEngine.validateConfiguration(version,config);
            var skill=config.path("skillSnapshot");
            io.github.opensabre.iqc.pipeline.ValueSchema.validateSchema(json.readTree(skill.path("inputSchemaJson").asText()));
            io.github.opensabre.iqc.pipeline.ValueSchema.validateSchema(json.readTree(skill.path("outputSchemaJson").asText()));
        } catch(com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException invalid) {
            throw IqcException.invalidArgument("Skill/API冻结版本或结构化协议无效");
        }
    }
    private JsonNode frozenCapability(JsonNode assets,JsonNode reference) {
        validateReference(reference);
        if(!assets.isArray())throw IqcException.invalidArgument("智能体缺少冻结能力资产");
        var matching=new ArrayList<JsonNode>();
        for(var asset:assets)if(reference.path("id").asText().equals(asset.path("id").asText())
                && asset.path("versionNo").isIntegralNumber() && asset.path("versionNo").canConvertToInt()
                && reference.path("versionNo").intValue()==asset.path("versionNo").intValue())matching.add(asset);
        if(matching.size()!=1)throw IqcException.invalidArgument("能力引用必须匹配智能体中唯一的冻结版本");
        return matching.getFirst().deepCopy();
    }
    private void validateReference(JsonNode reference) {
        if(!reference.isObject() || !reference.path("id").isTextual() || reference.path("id").asText().isBlank()
                || !reference.path("versionNo").isIntegralNumber() || !reference.path("versionNo").canConvertToInt() || reference.path("versionNo").asInt()<1)
            throw IqcException.invalidArgument("节点必须引用明确的资产ID和正整数版本");
    }
    private PipelineAsset require(String id,boolean lock) {
        var query=Wrappers.<PipelineAsset>lambdaQuery().eq(PipelineAsset::getId,id); if(lock) query.last("FOR UPDATE");
        var asset=assets.selectOne(query); if(asset==null) throw IqcException.notFound("流水线不存在");
        if(!scope.canView(asset.getCreatedBy(),asset.getOwnerGroupId())) throw IqcException.accessDenied("无权访问流水线"); return asset;
    }
    private void requireActive(PipelineAsset asset) { if(!"ACTIVE".equals(asset.getStatus())) throw IqcException.invalidState("流水线已停用"); }
    private void checkRevision(PipelineAsset asset,int revision) { if(asset.getDraftRevision()!=revision) throw IqcException.invalidState("流水线草稿修订已变化"); }
    private void validateName(String name) { if(name==null || name.isBlank() || name.length()>100) throw IqcException.invalidArgument("流水线名称不能为空或超过100字符"); }
    private PipelineRelease read(String value) { return convert(parse(value)); }
    private PipelineRelease convert(JsonNode value) { try { return json.treeToValue(value,PipelineRelease.class); } catch(Exception ex) { throw IqcException.invalidArgument("无效流水线版本",ex); } }
    private JsonNode parseExact(String value){
        try{return json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(value);}
        catch(Exception invalid){throw IqcException.invalidState("流水线JSON无效");}
    }
    private JsonNode parse(String value) { try { return readFrozenTree(json,value); } catch(Exception ex) { throw IqcException.invalidState("流水线JSON无效"); } }
    public String write(Object value) { try { return json.writeValueAsString(value); } catch(Exception ex) { throw IqcException.invalidState("无法冻结流水线配置"); } }
    public static String hash(String value) {
        try {
            JsonNode parsed=readFrozenTree(new ObjectMapper(),value);
            if(usesExactNumbers(parsed))return io.github.opensabre.iqc.selector.SelectorAssetService.hash(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(parsed).toString().getBytes(StandardCharsets.UTF_8)));
        } catch(Exception ex) { throw new IllegalStateException("Invalid frozen JSON",ex); }
    }
    /** Dispatches recognized release envelopes and root Gold/dataset protocols; nested user fields cannot opt into v2. */
    public static boolean usesExactNumbers(JsonNode value) {
        if(value==null)return false;
        // Only recognized root protocols opt in; arbitrary nested user configuration cannot.
        String schema=value.path("schemaVersion").asText();
        return io.github.opensabre.iqc.quality.evaluation.GoldDraft.EXACT_SCHEMA.equals(schema)
                || io.github.opensabre.iqc.quality.evaluation.GoldConversation.EXACT_SCHEMA.equals(schema)
                || io.github.opensabre.iqc.quality.evaluation.DatasetSnapshot.EXACT_SCHEMA.equals(schema)
                || PipelineRelease.EXACT_SCHEMA.equals(schema)
                || PipelineRelease.EXACT_SCHEMA.equals(value.path("release").path("schemaVersion").asText())
                || PipelineRelease.EXACT_SCHEMA.equals(value.path("pipelineSnapshot").path("release").path("schemaVersion").asText());
    }
    /** Reads persisted release/task JSON without changing historical v1 number representation. */
    public static JsonNode readFrozenTree(ObjectMapper mapper,String body) throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode exact=mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(body);
        return usesExactNumbers(exact)?exact:mapper.readTree(body);
    }
    /** Trace decoding follows its frozen release, never a current mutable configuration. */
    public static <T> T readFrozenValue(ObjectMapper mapper,String body,Class<T> type,PipelineRelease release)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        var reader=mapper.readerFor(type);
        if(PipelineRelease.EXACT_SCHEMA.equals(release.schemaVersion()))
            reader=reader.with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        return reader.readValue(body);
    }
    private static JsonNode canonical(JsonNode value) {
        var factory=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        if(value.isObject()) { var result=factory.objectNode(); var fields=new TreeMap<String,JsonNode>();
            value.properties().forEach(field -> fields.put(field.getKey(),field.getValue()));
            fields.forEach((key,node)->result.set(key,canonical(node))); return result; }
        if(value.isArray()) { var result=factory.arrayNode(); value.forEach(node->result.add(canonical(node))); return result; }
        return value.deepCopy();
    }
}
