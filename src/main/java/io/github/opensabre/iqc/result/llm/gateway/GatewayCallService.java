package io.github.opensabre.iqc.result.llm.gateway;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Short database transactions; never performs model I/O under a database lock. */
@Service @RequiredArgsConstructor
public class GatewayCallService {
    public record PriceBinding(String profileId,int version,String pricingJson,boolean billingSupported){
        public PriceBinding {
            if(profileId==null || !profileId.matches("[A-Za-z0-9:_-]{1,64}") || version<1)throw new GatewayAdmissionException("MODEL_PRICE_SOURCE_INVALID");
            try{pricingJson=io.github.opensabre.iqc.modelprofile.ModelPricing.canonical(pricingJson);}
            catch(RuntimeException invalid){throw new GatewayAdmissionException("MODEL_PRICE_PROTOCOL_INVALID");}
        }
    }
    public record OutputConstraint(int limit,String parameter,boolean singleResponseScope){
        public OutputConstraint {
            if(limit<1 || limit>1000000 || !Set.of("MAX_TOKENS","MAX_COMPLETION_TOKENS").contains(parameter==null?"":parameter))
                throw new GatewayAdmissionException("MODEL_OUTPUT_CONSTRAINT_INVALID");
        }
    }
    public record DeploymentDeclaration(Integer contextWindowTokens,String deploymentRef){
        public DeploymentDeclaration {
            if(contextWindowTokens!=null && (contextWindowTokens<1 || contextWindowTokens>100000000))throw new GatewayAdmissionException("MODEL_CONTEXT_WINDOW_INVALID");
            if(deploymentRef!=null && !deploymentRef.matches("[A-Za-z0-9][A-Za-z0-9:_./@+-]{0,127}"))throw new GatewayAdmissionException("MODEL_DEPLOYMENT_REFERENCE_INVALID");
        }
    }
    public record ModelIdentity(String provider,String endpoint,String modelName,PriceBinding price,OutputConstraint output,DeploymentDeclaration deployment) {
        public ModelIdentity(String provider,String endpoint,String modelName,PriceBinding price){this(provider,endpoint,modelName,price,null,null);}
        public ModelIdentity(String provider,String endpoint,String modelName){this(provider,endpoint,modelName,null,null,null);}
        public ModelIdentity(String provider,String endpoint,String modelName,PriceBinding price,OutputConstraint output){this(provider,endpoint,modelName,price,output,null);}
        public ModelIdentity {
            if(deployment!=null && deployment.contextWindowTokens()!=null && output!=null && output.limit()>deployment.contextWindowTokens())
                throw new GatewayAdmissionException("MODEL_OUTPUT_EXCEEDS_CONTEXT_WINDOW");
            if(provider==null || provider.isBlank() || provider.length()>64 || endpoint==null || endpoint.length()>2048
                    || modelName==null || modelName.isBlank() || modelName.length()>256)
                throw new GatewayAdmissionException("MODEL_IDENTITY_INVALID");
            if(Set.of("SPRING_AI","OPENAI","DASHSCOPE","OLLAMA","HTTP","OpenAiChatModel").contains(provider))provider="OPENAI_COMPATIBLE";
            if(!"deployment-default".equals(endpoint))try{
                var uri=java.net.URI.create(endpoint);
                if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
                    throw new IllegalArgumentException();
                String path=uri.getRawPath()==null?"":uri.getRawPath();while(path.endsWith("/"))path=path.substring(0,path.length()-1);
                int port=uri.getPort();if(("https".equals(uri.getScheme()) && port==443) || ("http".equals(uri.getScheme()) && port==80))port=-1;
                endpoint=uri.getScheme()+"://"+uri.getHost().toLowerCase(java.util.Locale.ROOT)+(port<0?"":":"+port)+path;
            }catch(RuntimeException invalid){throw new GatewayAdmissionException("MODEL_ENDPOINT_IDENTITY_INVALID");}
        }
        public String key(){return SelectorAssetService.hash(frame(provider)+frame(endpoint)+frame(modelName));}
        private static String frame(String value){return value.getBytes(StandardCharsets.UTF_8).length+":"+value;}
    }
    public record Context(String logicalRecordId,String ruleId,String owner,io.github.opensabre.iqc.result.llm.ModelCallContext execution,Integer toolRoundIndex) {
        public Context(String logicalRecordId,String ruleId,String owner){this(logicalRecordId,ruleId,owner,null,null);}
        public Context(String logicalRecordId,String ruleId,String owner,io.github.opensabre.iqc.result.llm.ModelCallContext execution){this(logicalRecordId,ruleId,owner,execution,null);}
        public Context forToolRound(int index){return new Context(logicalRecordId,ruleId,owner,execution,index);}
        public Context {if(toolRoundIndex!=null && (toolRoundIndex<0 || toolRoundIndex>32))throw new GatewayAdmissionException("MODEL_TOOL_ROUND_INVALID");bounded(logicalRecordId,512);bounded(ruleId,128);bounded(owner,128);}
        private static void bounded(String value,int size){if(value!=null && (value.length()>size || value.codePoints().anyMatch(Character::isISOControl)))
            throw new GatewayAdmissionException("CALL_CONTEXT_INVALID");}
    }
    public record Handle(String callId,String modelKey) { }
    public record Usage(Long inputTokens,Long outputTokens,Long totalTokens,String responseModelId,Long cacheReadInputTokens,Long cacheWriteInputTokens) {
        public Usage(Long inputTokens,Long outputTokens,Long totalTokens,String responseModelId){this(inputTokens,outputTokens,totalTokens,responseModelId,null,null);}
        private boolean invalid(){return negative(inputTokens) || negative(outputTokens) || negative(totalTokens) || negative(cacheReadInputTokens) || negative(cacheWriteInputTokens)
                || (inputTokens!=null && outputTokens!=null && totalTokens!=null
                && (inputTokens>Long.MAX_VALUE-outputTokens || inputTokens+outputTokens!=totalTokens));}
        private static boolean negative(Long value){return value!=null && value<0;}
        public String status(){if(invalid())return "INVALID";if(inputTokens==null && outputTokens==null && totalTokens==null)return "MISSING";
            return inputTokens!=null && outputTokens!=null && totalTokens!=null?"COMPLETE":"PARTIAL";}
        public static Usage missing(){return new Usage(null,null,null,null);}
    }
    private final GatewayCapacityMapper capacities;
    private final GatewayCallMapper calls;
    private final io.github.opensabre.iqc.task.dao.InspectionTaskMapper taskRows;
    private final io.github.opensabre.iqc.task.dao.TaskExecutionMapper executionRows;
    private final io.github.opensabre.iqc.shared.IqcDataScope scope;
    private final io.github.opensabre.iqc.schedule.ScheduledActorAccessService actors;
    private final org.springframework.beans.factory.ObjectProvider<io.github.opensabre.iqc.task.InspectionTaskService> taskService;
    public record CallView(String callId,String modelKey,String logicalRecordId,String ruleId,String owner,String status,
            LocalDateTime reservedAtUtc,LocalDateTime settledAtUtc,
            @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) Long inputTokens,
            @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) Long outputTokens,
            @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) Long totalTokens,
            String responseModelId,String usageStatus,String errorType,LocalDateTime capacityReleasedAtUtc,
            String capacityReleasedBy,String capacityReleaseReason,String capacityReleaseEvidence,
            String taskId,String executionId,String nodeId,Integer runIndex,String conversationId,
            String modelProfileId,Integer modelProfileVersion,String pricingHash,String pricingCurrency,String calculatedCost,String costStatus,String requestedModelName,
            @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) Long cacheReadInputTokens,
            @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) Long cacheWriteInputTokens,Integer maxOutputTokens,String outputTokenParameter,String outputLimitStatus,Integer toolRoundIndex,Integer contextWindowTokens,String deploymentRef,LocalDateTime toolExecutionStartedAtUtc) { }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public io.github.opensabre.iqc.shared.IqcPage<CallView> page(String modelKey,String status,long current,long size){
        if(!scope.isIqcAdministrator())throw IqcException.accessDenied("只有质检管理员可以查看全局模型调用");
        if(current<1 || size<1 || size>100 || (modelKey!=null && !modelKey.matches("[a-f0-9]{64}"))
                || (status!=null && !Set.of("RESERVED","CONFIRMED","UNKNOWN").contains(status)))throw IqcException.invalidArgument("模型调用分页条件无效");
        var query=Wrappers.<GatewayCall>lambdaQuery();if(modelKey!=null)query.eq(GatewayCall::getModelKey,modelKey);
        if(status!=null)query.eq(GatewayCall::getStatus,status);
        return readPage(query,current,size);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public io.github.opensabre.iqc.shared.IqcPage<CallView> taskPage(String taskId,String executionId,String status,long current,long size){
        taskService.getObject().get(taskId);
        if(current<1 || size<1 || size>100 || (status!=null && !Set.of("RESERVED","CONFIRMED","UNKNOWN").contains(status)))
            throw IqcException.invalidArgument("模型调用分页条件无效");
        if(executionId!=null){var run=executionRows.selectById(executionId);
            if(run==null || !Objects.equals(run.getTaskId(),taskId))throw IqcException.notFound("任务执行轮次不存在");}
        var query=Wrappers.<GatewayCall>lambdaQuery().eq(GatewayCall::getTaskId,taskId);
        if(executionId!=null)query.eq(GatewayCall::getExecutionId,executionId);
        if(status!=null)query.eq(GatewayCall::getStatus,status);
        return readPage(query,current,size);
    }
    private io.github.opensabre.iqc.shared.IqcPage<CallView> readPage(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<GatewayCall> query,long current,long size){
        var page=calls.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current,size),
                query.orderByDesc(GatewayCall::getReservedAtUtc).orderByDesc(GatewayCall::getCallId));
        return new io.github.opensabre.iqc.shared.IqcPage<>(page.getRecords().stream().map(value->new CallView(value.getCallId(),value.getModelKey(),
                value.getLogicalRecordId(),value.getRuleId(),value.getOwner(),value.getStatus(),value.getReservedAtUtc(),value.getSettledAtUtc(),
                value.getInputTokens(),value.getOutputTokens(),value.getTotalTokens(),value.getResponseModelId(),value.getUsageStatus(),value.getErrorType(),value.getCapacityReleasedAtUtc(),
                value.getCapacityReleasedBy(),value.getCapacityReleaseReason(),value.getCapacityReleaseEvidence(),
                value.getTaskId(),value.getExecutionId(),value.getNodeId(),value.getRunIndex(),value.getConversationId(),value.getModelProfileId(),
                value.getModelProfileVersion(),value.getPricingHash(),value.getPricingCurrency(),value.getCalculatedCost()==null?null:value.getCalculatedCost().toPlainString(),value.getCostStatus(),value.getRequestedModelName(),value.getCacheReadInputTokens(),value.getCacheWriteInputTokens(),value.getMaxOutputTokens(),value.getOutputTokenParameter(),value.getOutputLimitStatus(),value.getToolRoundIndex(),value.getContextWindowTokens(),value.getDeploymentRef(),value.getToolExecutionStartedAtUtc())).toList(),
                page.getCurrent(),page.getSize(),page.getTotal());
    }
    /** Same snapshot and authorization as the paged facts; totals cover all matching pages. */
    public record CurrencyCost(String currency,String callCount,String knownCount,String unknownCount,String knownSubtotal) { }
    public record CostSummary(String callCount,String knownCount,String unknownCount,List<CurrencyCost> currencies) { }
    public record CallPage(List<CallView> records,long current,long size,long total,CostSummary costSummary) { }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public CallPage pageWithSummary(String modelKey,String status,long current,long size){
        var page=page(modelKey,status,current,size);
        return withSummary(page,modelKey,null,null,status);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public CallPage taskPageWithSummary(String taskId,String executionId,String status,long current,long size){
        var page=taskPage(taskId,executionId,status,current,size);
        return withSummary(page,null,taskId,executionId,status);
    }
    private CallPage withSummary(io.github.opensabre.iqc.shared.IqcPage<CallView> page,String modelKey,String taskId,String executionId,String status){
        java.math.BigInteger count=java.math.BigInteger.ZERO,known=java.math.BigInteger.ZERO;
        var groups=new ArrayList<CurrencyCost>();
        for(var group:calls.costGroups(modelKey,taskId,executionId,status)){
            var n=java.math.BigInteger.valueOf(group.getCallCount());
            var k=java.math.BigInteger.valueOf(group.getKnownCount());
            count=count.add(n);known=known.add(k);
            groups.add(new CurrencyCost(group.getCurrency(),n.toString(),k.toString(),n.subtract(k).toString(),
                    group.getKnownSubtotal()==null?null:group.getKnownSubtotal().toPlainString()));
        }
        return new CallPage(page.records(),page.current(),page.size(),page.total(),
                new CostSummary(count.toString(),known.toString(),count.subtract(known).toString(),List.copyOf(groups)));
    }
    public record CapacityView(String modelKey,
            @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) Long occupied,
            Integer concurrencyLimit,Boolean managed,
            @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) Long version) { }
    public record CapacityChange(CapacityView before,CapacityView after) { }
    public enum ReleaseReason { REMOTE_COMPLETED,REMOTE_CANCELLED,REQUEST_NOT_SENT }
    private void administrator(){if(!scope.isIqcAdministrator())throw IqcException.accessDenied("仅质检管理员可管理模型容量");}
    private static void key(String key){if(key==null || !key.matches("[a-f0-9]{64}"))throw IqcException.invalidArgument("模型键无效");}
    private CapacityView view(GatewayCapacity row){return new CapacityView(row.getModelKey(),row.getOccupied(),row.getConcurrencyLimit(),row.getPolicyManaged(),row.getPolicyVersion());}
    public CapacityView getCapacity(String modelKey){administrator();key(modelKey);var row=capacities.selectById(modelKey);
        if(row==null)throw IqcException.notFound("模型容量不存在");return view(row);}
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public CapacityChange configureCapacity(String modelKey,int limit,long expectedVersion){
        administrator();key(modelKey);actors.capture();
        if(limit<0 || limit>10000 || expectedVersion<0 || expectedVersion==Long.MAX_VALUE)throw IqcException.invalidArgument("并发上限或版本无效");
        var row=capacity(modelKey);if(!Objects.equals(row.getPolicyVersion(),expectedVersion))throw IqcException.invalidState("容量配置已变化，请刷新");
        var before=view(row);row.setConcurrencyLimit(limit);row.setPolicyManaged(true);row.setPolicyVersion(expectedVersion+1);
        if(capacities.updateById(row)!=1)throw IqcException.invalidState("更新模型容量失败");return new CapacityChange(before,view(row));
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public CapacityView releaseCapacity(String callId,String modelKey,ReleaseReason reason,String evidence,boolean remoteStopped){
        administrator();key(modelKey);var actor=actors.capture();
        if(callId==null || !callId.matches("[a-f0-9]{32}") || reason==null || !remoteStopped
                || evidence==null || !evidence.matches("[A-Za-z0-9:_./-]{1,128}"))throw IqcException.invalidArgument("需要确认远端已停止并提供核对凭据编号");
        // Same call -> capacity lock order as response settlement; never capacity -> existing call.
        var call=lock(new Handle(callId,modelKey));var row=capacity(modelKey);
        if(call.getCapacityReleasedAtUtc()!=null){
            if(!reason.name().equals(call.getCapacityReleaseReason()) || !evidence.equals(call.getCapacityReleaseEvidence()))
                throw IqcException.invalidState("恢复凭据已冻结，不可替换");
            return view(row);
        }
        if(!Set.of("RESERVED","UNKNOWN").contains(call.getStatus()))throw IqcException.invalidState("已确认响应无需人工释放");
        if(row.getOccupied()<1)throw IqcException.invalidState("模型占用证据不一致");
        call.setCapacityReleasedAtUtc(utc());call.setCapacityReleasedBy(actor.owner());call.setCapacityReleasedUserId(actor.userId());
        call.setCapacityReleaseReason(reason.name());call.setCapacityReleaseEvidence(evidence);
        row.setOccupied(row.getOccupied()-1);
        if(calls.updateById(call)!=1 || capacities.updateById(row)!=1)throw IqcException.invalidState("恢复模型容量失败");return view(row);
    }
    private static LocalDateTime utc(){return LocalDateTime.ofInstant(Instant.now(),ZoneOffset.UTC);}
    private GatewayCapacity capacity(String key){var row=capacities.selectOne(Wrappers.<GatewayCapacity>lambdaQuery()
            .eq(GatewayCapacity::getModelKey,key).last("FOR UPDATE"));
        if(row==null || row.getOccupied()==null || row.getOccupied()<0)throw IqcException.invalidState("模型容量证据缺失");return row;}

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public void markToolsStarted(Handle handle,Context context){
        var call=lock(handle);assertExecutionActive(context);
        var source=context.execution();
        if(!"CONFIRMED".equals(call.getStatus()) || !Objects.equals(call.getLogicalRecordId(),context.logicalRecordId())
                || !Objects.equals(call.getRuleId(),context.ruleId()) || !Objects.equals(call.getTaskId(),source==null?null:source.taskId())
                || !Objects.equals(call.getExecutionId(),source==null?null:source.executionId()))
            throw new GatewayAdmissionException("MODEL_TOOL_EXPOSURE_SOURCE_INVALID");
        if(call.getToolExecutionStartedAtUtc()!=null)return;
        call.setToolExecutionStartedAtUtc(utc());
        if(calls.updateById(call)!=1)throw new GatewayAdmissionException("MODEL_TOOL_EXPOSURE_UNCONFIRMED");
    }
    public boolean hasToolExposure(String taskId){
        return calls.selectCount(Wrappers.<GatewayCall>lambdaQuery().eq(GatewayCall::getTaskId,taskId).isNotNull(GatewayCall::getToolExecutionStartedAtUtc))>0;
    }
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public void assertExecutionActive(Context context){
        if(context==null)throw new GatewayAdmissionException("CALL_CONTEXT_INVALID");
        var execution=context.execution();
        if(execution!=null){
            var task=taskRows.selectById(execution.taskId());var run=executionRows.selectById(execution.executionId());
            if(task==null || run==null || !Objects.equals(run.getTaskId(),execution.taskId())
                    || !Objects.equals(task.getCurrentExecutionId(),execution.executionId()) || !Objects.equals(task.getCreatedBy(),execution.owner())
                    || !Objects.equals(task.getOwnerGroupId(),execution.ownerGroupId()) || !"RUNNING".equals(task.getStatus())
                    || !"RUNNING".equals(run.getStatus()) || Boolean.TRUE.equals(task.getCancelRequested()) || Boolean.TRUE.equals(task.getPauseRequested()))throw new GatewayAdmissionException("MODEL_EXECUTION_PROVENANCE_STALE");
        }
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public Handle reserve(ModelIdentity model,Context context,int limit){
        if(model==null || context==null || limit<0 || limit>10000)throw new GatewayAdmissionException("GATEWAY_CONFIGURATION_INVALID");
        var execution=context.execution();
        assertExecutionActive(context);
        String key=model.key();capacities.ensure(key,limit);var bucket=capacity(key);
        if(!Boolean.TRUE.equals(bucket.getPolicyManaged()) && !Objects.equals(bucket.getConcurrencyLimit(),limit))throw new GatewayAdmissionException("GATEWAY_CONCURRENCY_POLICY_MISMATCH");
        int effectiveLimit=bucket.getConcurrencyLimit();
        if(bucket.getOccupied()==Long.MAX_VALUE || (effectiveLimit>0 && bucket.getOccupied()>=effectiveLimit))
            throw new GatewayAdmissionException("MODEL_CONCURRENCY_LIMIT_REACHED");
        bucket.setOccupied(bucket.getOccupied()+1);
        var call=new GatewayCall();call.setCallId(UUID.randomUUID().toString().replace("-",""));call.setModelKey(key);call.setRequestedModelName(model.modelName());
        if(model.deployment()!=null){call.setContextWindowTokens(model.deployment().contextWindowTokens());call.setDeploymentRef(model.deployment().deploymentRef());}
        var output=model.output();call.setOutputLimitStatus(output==null?"NOT_DECLARED":"UNKNOWN_RESPONSE");
        if(output!=null){call.setMaxOutputTokens(output.limit());call.setOutputTokenParameter(output.parameter());call.setOutputLimitSingleResponse(output.singleResponseScope());}
        call.setToolRoundIndex(context.toolRoundIndex());call.setLogicalRecordId(context.logicalRecordId());call.setRuleId(context.ruleId());call.setOwner(execution==null?context.owner():execution.owner());
        if(execution!=null){call.setTaskId(execution.taskId());call.setExecutionId(execution.executionId());call.setNodeId(execution.nodeId());
            call.setRunIndex(execution.runIndex());call.setOwnerGroupId(execution.ownerGroupId());call.setConversationId(execution.conversationId());}
        var price=model.price();call.setCostStatus("UNKNOWN_PRICE");
        if(price!=null){call.setModelProfileId(price.profileId());call.setModelProfileVersion(price.version());call.setPricingJson(price.pricingJson());
            call.setPricingHash(price.pricingJson()==null?null:SelectorAssetService.hash(price.pricingJson()));call.setBillingSupported(price.billingSupported());
            var declaration=io.github.opensabre.iqc.modelprofile.ModelPricing.parse(price.pricingJson());
            if(declaration!=null){call.setPricingCurrency(declaration.currency());call.setCostStatus("UNKNOWN_RESPONSE");}}
        GatewayBudgetAccounting.reserve(bucket,call);
        if(capacities.updateById(bucket)!=1)throw IqcException.invalidState("预留模型容量失败");
        call.setStatus("RESERVED");call.setReservedAtUtc(utc());
        if(calls.insert(call)!=1)throw IqcException.invalidState("记录模型调用预留失败");return new Handle(call.getCallId(),key);
    }
    private GatewayCall lock(Handle handle){
        if(handle==null)throw IqcException.invalidState("模型调用身份缺失");
        var call=calls.selectOne(Wrappers.<GatewayCall>lambdaQuery().eq(GatewayCall::getCallId,handle.callId()).last("FOR UPDATE"));
        if(call==null || !Objects.equals(call.getModelKey(),handle.modelKey()))throw IqcException.invalidState("模型调用来源不一致");return call;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public void confirmed(Handle handle,Usage supplied){
        var call=lock(handle);
        var usage=supplied==null?Usage.missing():supplied;
        if("CONFIRMED".equals(call.getStatus())){
            boolean invalid="INVALID".equals(usage.status());
            if(!Objects.equals(call.getUsageStatus(),usage.status()) || !Objects.equals(call.getInputTokens(),invalid?null:usage.inputTokens())
                    || !Objects.equals(call.getOutputTokens(),invalid?null:usage.outputTokens()) || !Objects.equals(call.getTotalTokens(),invalid?null:usage.totalTokens())
                    || !Objects.equals(call.getCacheReadInputTokens(),invalid?null:usage.cacheReadInputTokens())
                    || !Objects.equals(call.getCacheWriteInputTokens(),invalid?null:usage.cacheWriteInputTokens()))
                throw IqcException.invalidState("已核销用量事实不可替换");
            return;
        }
        if(!"RESERVED".equals(call.getStatus()))throw IqcException.invalidState("未知调用不能自动确认为完成");
        var bucket=capacity(handle.modelKey());if(call.getCapacityReleasedAtUtc()==null && bucket.getOccupied()<1)throw IqcException.invalidState("模型占用证据不一致");
        call.setUsageStatus(usage.status());
        if(!"INVALID".equals(usage.status())){call.setInputTokens(usage.inputTokens());call.setOutputTokens(usage.outputTokens());call.setTotalTokens(usage.totalTokens());call.setCacheReadInputTokens(usage.cacheReadInputTokens());call.setCacheWriteInputTokens(usage.cacheWriteInputTokens());}
        String model=usage.responseModelId();
        if(model!=null && model.length()<=256 && model.codePoints().noneMatch(Character::isISOControl))call.setResponseModelId(model);
        observeOutputLimit(call,usage);
        calculateCost(call,usage);
        boolean budgetChanged=GatewayBudgetAccounting.settle(bucket,call,usage);
        call.setStatus("CONFIRMED");call.setSettledAtUtc(utc());
        boolean release=call.getCapacityReleasedAtUtc()==null;
        if(release)bucket.setOccupied(bucket.getOccupied()-1);
        if(calls.updateById(call)!=1 || ((release || budgetChanged) && capacities.updateById(bucket)!=1))throw IqcException.invalidState("核销模型调用失败");
    }
    private static void observeOutputLimit(GatewayCall call,Usage usage){
        if(call.getMaxOutputTokens()==null){call.setOutputLimitStatus("NOT_DECLARED");return;}
        if(!Boolean.TRUE.equals(call.getOutputLimitSingleResponse())){call.setOutputLimitStatus("UNKNOWN_REQUEST_SCOPE");return;}
        if("INVALID".equals(usage.status()) || usage.outputTokens()==null){call.setOutputLimitStatus("UNKNOWN_USAGE");return;}
        call.setOutputLimitStatus(usage.outputTokens()>call.getMaxOutputTokens()?"OBSERVED_OVER_LIMIT":"OBSERVED_WITHIN_LIMIT");
    }
    private void calculateCost(GatewayCall call,Usage usage){
        if(call.getPricingJson()==null){call.setCostStatus("UNKNOWN_PRICE");return;}
        if(!Objects.equals(call.getPricingHash(),SelectorAssetService.hash(call.getPricingJson()))){call.setCostStatus("PRICING_EVIDENCE_INVALID");return;}
        if(!Boolean.TRUE.equals(call.getBillingSupported())){call.setCostStatus("UNKNOWN_REQUEST_SCOPE");return;}
        if(!"COMPLETE".equals(usage.status())){call.setCostStatus("UNKNOWN_USAGE");return;}
        if((usage.cacheReadInputTokens()!=null && usage.cacheReadInputTokens()>0) || (usage.cacheWriteInputTokens()!=null && usage.cacheWriteInputTokens()>0)){
            call.setCostStatus("UNKNOWN_CACHE_BILLING");return;}

        try{var price=io.github.opensabre.iqc.modelprofile.ModelPricing.parse(call.getPricingJson());
            call.setCalculatedCost(price.cost(usage.inputTokens(),usage.outputTokens()));call.setCostStatus("CALCULATED_FROM_DECLARED_PRICE");
        }catch(RuntimeException invalid){call.setCostStatus("PRICING_EVIDENCE_INVALID");}
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public void unknown(Handle handle,RuntimeException failure){
        var call=lock(handle);if(!"RESERVED".equals(call.getStatus()))return;
        call.setStatus("UNKNOWN");call.setSettledAtUtc(utc());call.setUsageStatus("MISSING");
        String type=failure.getClass().getSimpleName();call.setErrorType(type.length()>128?"RuntimeException":type);
        if(calls.updateById(call)!=1)throw IqcException.invalidState("记录未知模型调用失败");
    }
}
