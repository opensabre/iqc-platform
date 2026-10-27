package io.github.opensabre.iqc.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService.CreationBound;
import io.github.opensabre.iqc.selector.ConversationSelectionService;
import io.github.opensabre.iqc.selector.SelectorDefinition;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.dao.TaskExecutionMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;

/** Bounded transactions only. No model invocation or child execution occurs under the parent lock. */
@Service
@RequiredArgsConstructor
public class BulkTaskMaterializationService {
    public static final String TYPE="PIPELINE_BULK";
    public static final Set<String> GRANTS=Set.of("iqc:pipeline:task:create","iqc:task:execute");
    private final InspectionTaskMapper tasks;
    private final TaskExecutionMapper executions;
    private final InspectionTaskService materializer;
    private final PipelineAssetService pipelines;
    private final ConversationSelectionService selections;
    private final IqcDataScope scope;
    private final ObjectMapper json;

    public record Definition(String schemaVersion,PipelineAssetService.Prepared pipeline,SelectorDefinition selector,
            Instant anchor,CreationBound ownerBound,int scanSize,int concurrency,String name) { }
    public record Progress(String schemaVersion,String afterId,int nextShardNo,long scannedConversations,
            long selectedConversations,boolean scanComplete) { }
    public record Step(String parentTaskId,String childTaskId,Progress progress) { }

    /** Caller resolves current organization grants and enters the original owner-bound scope first. */
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public InspectionTask create(String pipelineId,int versionNo,SelectorDefinition selector,CreationBound ownerBound,
            String name,int scanSize,int concurrency,String requestId) {
        scope.requireScheduledResources(GRANTS);
        scope.requireScheduledBound(ownerBound);
        selections.validate(selector);
        if(selector.sampling().mode()!=SelectorDefinition.SamplingMode.ALL || scanSize<1
                || scanSize>selector.maximumCount() || concurrency<1 || concurrency>32
                || name==null || name.isBlank() || name.length()>200
                || requestId==null || !requestId.matches("[A-Za-z0-9:_-]{1,128}"))
            throw IqcException.invalidArgument("批次需要ALL、有效片大小/并发、名称和幂等标识");
        String taskId="b"+PipelineAssetService.hash(json.createArrayNode().add(scope.owner()).add(pipelineId).add(requestId).toString()).substring(0,63);
        var existing=tasks.selectOne(Wrappers.<InspectionTask>lambdaQuery().eq(InspectionTask::getId,taskId).last("FOR UPDATE"));
        if(existing!=null) {
            var original=definition(existing);
            if(!Objects.equals(existing.getCreatedBy(),scope.owner()) || !Objects.equals(original.ownerBound(),ownerBound)
                    || !Objects.equals(original.pipeline().pipelineId(),pipelineId) || !Objects.equals(original.pipeline().versionNo(),versionNo)
                    || !json.valueToTree(original.selector()).equals(json.valueToTree(selector)) || original.scanSize()!=scanSize
                    || original.concurrency()!=concurrency || !original.name().equals(name.trim()))
                throw IqcException.invalidState("批次幂等请求内容不同");
            return existing; // The original anchor and committed children must never be selected again here.
        }
        var prepared=pipelines.publishedReleaseForTask(pipelineId,versionNo);
        var frozen=new Definition("iqc-bulk-definition-v1",prepared,selector,Instant.now().truncatedTo(ChronoUnit.MILLIS),
                ownerBound,scanSize,concurrency,name.trim());
        JsonNode body=json.valueToTree(frozen);
        if(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16*1024*1024)
            throw IqcException.invalidArgument("批次冻结定义超过16MiB");
        var envelope=json.createObjectNode().put("schemaVersion","iqc-bulk-task-v1").put("definitionHash",PipelineAssetService.hash(body.toString()));
        envelope.set("definition",body);
        var task=new InspectionTask();task.setId(taskId);task.setCreatedBy(scope.owner());task.setName(name.trim());
        task.setTaskType(TYPE);task.setStatus("CREATED");task.setConcurrencyLimit(concurrency);task.setRunCount(1);
        task.setConversationIdsJson("[]");task.setRuleIdsJson("[]");task.setOwnerGroupId(null);
        // A future projection may expose aggregate units explicitly; never call shard/conversation counts messages.
        task.setTotalMessages(0);task.setProcessedMessages(0);task.setFailedMessages(0);task.setAttemptCount(0);
        task.setRuleSnapshotJson(json.createObjectNode().set("bulkSnapshot",envelope).toString());
        task.setBulkProgressJson(write(new Progress("iqc-bulk-progress-v1",null,0,0,0,false)));
        if(tasks.insert(task)!=1)throw IqcException.invalidState("批次父任务保存失败");
        return task;
    }

    /** All child input/selection snapshots, lineage and cursor changes share this single transaction. */
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Step materializeNext(String parentId,String executionId) {
        scope.requireScheduledResources(GRANTS);
        if(parentId==null || !parentId.matches("[A-Za-z0-9:_-]{1,64}") || executionId==null
                || !executionId.matches("[A-Za-z0-9:_-]{1,64}"))throw IqcException.invalidArgument("批次执行标识无效");
        var parent=tasks.selectOne(Wrappers.<InspectionTask>lambdaQuery().eq(InspectionTask::getId,parentId).last("FOR UPDATE"));
        var frozen=definition(parent);
        scope.requireScheduledBound(frozen.ownerBound());
        if(!scope.canView(parent.getCreatedBy(),parent.getOwnerGroupId()))throw IqcException.accessDenied("无权继续批次");
        if(!"RUNNING".equals(parent.getStatus()) || !executionId.equals(parent.getCurrentExecutionId())
                || Boolean.TRUE.equals(parent.getPauseRequested()) || Boolean.TRUE.equals(parent.getCancelRequested()))
            throw IqcException.invalidState("批次执行已变化或已请求停止");
        var execution=executions.selectById(executionId);
        if(execution==null || !parentId.equals(execution.getTaskId()) || !"RUNNING".equals(execution.getStatus()))
            throw IqcException.invalidState("批次执行租约无效");
        var progress=progress(parent.getBulkProgressJson());
        if(progress.scanComplete())return new Step(parentId,null,progress);
        var current=pipelines.publishedReleaseForTask(frozen.pipeline().pipelineId(),frozen.pipeline().versionNo());
        if(!current.contentHash().equals(frozen.pipeline().contentHash()))throw IqcException.invalidState("批次流水线发布内容已变化");
        var page=selections.scanAllPageAt(frozen.selector(),frozen.anchor(),progress.afterId(),frozen.scanSize());
        String childId=null;
        if(page.selection()!=null && !page.selection().selectedConversationIds().isEmpty()) {
            // A committed child without its checkpoint is corruption, not permission to silently recapture live input.
            var unexpected=tasks.selectOne(Wrappers.<InspectionTask>lambdaQuery()
                    .eq(InspectionTask::getBulkParentTaskId,parentId).eq(InspectionTask::getBulkShardNo,progress.nextShardNo()));
            if(unexpected!=null)throw IqcException.invalidState("批次分片与检查点不一致");
            String request="bulk:"+parentId+":"+progress.nextShardNo();
            String evidenceHash=PipelineAssetService.hash(json.valueToTree(page).toString());
            var scanEvidence=json.createObjectNode().put("schemaVersion","iqc-bulk-shard-scan-v1")
                    .put("parentTaskId",parentId).put("shardNo",progress.nextShardNo())
                    .put("scanPageHash",evidenceHash).put("scannedCount",page.scannedCount())
                    .put("hasMore",page.hasMore());
            scanEvidence.set("anchor",json.valueToTree(page.anchor()));
            scanEvidence.set("createdBeforeExclusive",json.valueToTree(page.createdBeforeExclusive()));
            scanEvidence.put("afterId",page.afterId());scanEvidence.put("nextAfterId",page.nextAfterId());
            var child=materializer.createBulkShardUsingPipelineSnapshot(frozen.pipeline(),frozen.name(),frozen.concurrency(),request,page.selection(),evidenceHash,scanEvidence);
            if(child.getBulkParentTaskId()!=null || child.getBulkShardNo()!=null || !"CREATED".equals(child.getStatus()))
                throw IqcException.invalidState("批次子任务幂等状态不一致");
            int linked=tasks.update(null,Wrappers.<InspectionTask>lambdaUpdate().eq(InspectionTask::getId,child.getId())
                    .isNull(InspectionTask::getBulkParentTaskId).isNull(InspectionTask::getBulkShardNo)
                    .set(InspectionTask::getBulkParentTaskId,parentId).set(InspectionTask::getBulkShardNo,progress.nextShardNo())
                    .set(InspectionTask::getQueuePriority,parent.getQueuePriority()==null?0L:parent.getQueuePriority()));
            if(linked!=1)throw IqcException.invalidState("批次子任务引用保存失败");
            childId=child.getId();
        }
        var next=new Progress("iqc-bulk-progress-v1",page.nextAfterId(),Math.incrementExact(progress.nextShardNo()),
                Math.addExact(progress.scannedConversations(),page.scannedCount()),
                Math.addExact(progress.selectedConversations(),page.selection()==null?0:page.selection().selectedConversationIds().size()),!page.hasMore());
        if(tasks.update(null,Wrappers.<InspectionTask>lambdaUpdate().eq(InspectionTask::getId,parentId)
                .eq(InspectionTask::getCurrentExecutionId,executionId).eq(InspectionTask::getStatus,"RUNNING")
                .set(InspectionTask::getBulkProgressJson,write(next)))!=1)throw IqcException.invalidState("批次游标保存失败");
        return new Step(parentId,childId,next);
    }

    Definition definition(InspectionTask task) {
        if(task==null || !TYPE.equals(task.getTaskType()) || task.getBulkParentTaskId()!=null)
            throw IqcException.invalidState("批次父任务不存在或类型无效");
        try {
            var exact=json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
            var envelope=exact.readTree(task.getRuleSnapshotJson()).path("bulkSnapshot");var body=envelope.path("definition");
            if(!"iqc-bulk-task-v1".equals(envelope.path("schemaVersion").asText())
                    || !PipelineAssetService.hash(body.toString()).equals(envelope.path("definitionHash").asText()))
                throw IqcException.invalidState("批次冻结摘要无效");
            var value=exact.treeToValue(body,Definition.class);
            if(!"iqc-bulk-definition-v1".equals(value.schemaVersion()) || value.pipeline()==null || value.pipeline().versionNo()==null
                    || value.anchor()==null || value.ownerBound()==null || !Objects.equals(task.getCreatedBy(),value.ownerBound().owner())
                    || value.selector()==null || value.selector().sampling().mode()!=SelectorDefinition.SamplingMode.ALL
                    || value.scanSize()<1 || value.scanSize()>value.selector().maximumCount() || value.concurrency()<1 || value.concurrency()>32)
                throw IqcException.invalidState("批次冻结定义无效");
            return value;
        } catch(com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException bad) {
            throw IqcException.invalidState("批次冻结定义无法读取");
        }
    }
    Progress progress(String body) {return readProgress(json,body);}
    /** Shared bounded aggregate decoder. It never returns source snapshots or owner identities. */
    public static Progress readProgress(ObjectMapper json,String body) {
        try {
            if(body==null || body.length()>4096)throw IqcException.invalidState("批次检查点缺失或过大");
            var tree=json.readTree(body);
            if(tree==null || !tree.isObject() || !tree.path("schemaVersion").isTextual()
                    || !tree.path("nextShardNo").isIntegralNumber() || !tree.path("nextShardNo").canConvertToInt()
                    || !tree.path("scannedConversations").isIntegralNumber() || !tree.path("scannedConversations").canConvertToLong()
                    || !tree.path("selectedConversations").isIntegralNumber() || !tree.path("selectedConversations").canConvertToLong()
                    || !tree.path("scanComplete").isBoolean()
                    || tree.hasNonNull("afterId")&&!tree.path("afterId").isTextual())
                throw IqcException.invalidState("批次检查点字段缺失或类型无效");
            var value=json.treeToValue(tree,Progress.class);
            if(!"iqc-bulk-progress-v1".equals(value.schemaVersion()) || value.nextShardNo()<0 || value.scannedConversations()<0
                    || value.selectedConversations()<0 || value.selectedConversations()>value.scannedConversations()
                    || value.afterId()!=null && !value.afterId().matches("[A-Za-z0-9:_-]{1,64}")
                    || value.scannedConversations()>0 && value.afterId()==null)
                throw IqcException.invalidState("批次检查点无效");
            return value;
        } catch(com.fasterxml.jackson.core.JsonProcessingException bad) {throw IqcException.invalidState("批次检查点无法读取");}
    }
    private String write(Object value) {
        try{return json.writeValueAsString(value);}
        catch(com.fasterxml.jackson.core.JsonProcessingException bad){throw IqcException.invalidState("批次检查点无法保存");}
    }
}
