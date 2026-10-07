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
    public record ModelIdentity(String provider,String endpoint,String modelName) {
        public ModelIdentity {
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
    public record Context(String logicalRecordId,String ruleId,String owner) {
        public Context {bounded(logicalRecordId,512);bounded(ruleId,128);bounded(owner,128);}
        private static void bounded(String value,int size){if(value!=null && (value.length()>size || value.codePoints().anyMatch(Character::isISOControl)))
            throw new GatewayAdmissionException("CALL_CONTEXT_INVALID");}
    }
    public record Handle(String callId,String modelKey) { }
    public record Usage(Long inputTokens,Long outputTokens,Long totalTokens,String responseModelId) {
        private boolean invalid(){return negative(inputTokens) || negative(outputTokens) || negative(totalTokens)
                || (inputTokens!=null && outputTokens!=null && totalTokens!=null
                && (inputTokens>Long.MAX_VALUE-outputTokens || inputTokens+outputTokens!=totalTokens));}
        private static boolean negative(Long value){return value!=null && value<0;}
        public String status(){if(invalid())return "INVALID";if(inputTokens==null && outputTokens==null && totalTokens==null)return "MISSING";
            return inputTokens!=null && outputTokens!=null && totalTokens!=null?"COMPLETE":"PARTIAL";}
        public static Usage missing(){return new Usage(null,null,null,null);}
    }
    private final GatewayCapacityMapper capacities;
    private final GatewayCallMapper calls;
    private final io.github.opensabre.iqc.shared.IqcDataScope scope;
    private final io.github.opensabre.iqc.schedule.ScheduledActorAccessService actors;
    public record CallView(String callId,String modelKey,String logicalRecordId,String ruleId,String owner,String status,
            LocalDateTime reservedAtUtc,LocalDateTime settledAtUtc,
            @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) Long inputTokens,
            @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) Long outputTokens,
            @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) Long totalTokens,
            String responseModelId,String usageStatus,String errorType,LocalDateTime capacityReleasedAtUtc,
            String capacityReleasedBy,String capacityReleaseReason,String capacityReleaseEvidence) { }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public io.github.opensabre.iqc.shared.IqcPage<CallView> page(String modelKey,String status,long current,long size){
        if(!scope.isIqcAdministrator())throw IqcException.accessDenied("只有质检管理员可以查看全局模型调用");
        if(current<1 || size<1 || size>100 || (modelKey!=null && !modelKey.matches("[a-f0-9]{64}"))
                || (status!=null && !Set.of("RESERVED","CONFIRMED","UNKNOWN").contains(status)))throw IqcException.invalidArgument("模型调用分页条件无效");
        var query=Wrappers.<GatewayCall>lambdaQuery();if(modelKey!=null)query.eq(GatewayCall::getModelKey,modelKey);
        if(status!=null)query.eq(GatewayCall::getStatus,status);
        var page=calls.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current,size),
                query.orderByDesc(GatewayCall::getReservedAtUtc).orderByDesc(GatewayCall::getCallId));
        return new io.github.opensabre.iqc.shared.IqcPage<>(page.getRecords().stream().map(value->new CallView(value.getCallId(),value.getModelKey(),
                value.getLogicalRecordId(),value.getRuleId(),value.getOwner(),value.getStatus(),value.getReservedAtUtc(),value.getSettledAtUtc(),
                value.getInputTokens(),value.getOutputTokens(),value.getTotalTokens(),value.getResponseModelId(),value.getUsageStatus(),value.getErrorType(),value.getCapacityReleasedAtUtc(),
                value.getCapacityReleasedBy(),value.getCapacityReleaseReason(),value.getCapacityReleaseEvidence())).toList(),
                page.getCurrent(),page.getSize(),page.getTotal());
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
    public Handle reserve(ModelIdentity model,Context context,int limit){
        if(model==null || context==null || limit<0 || limit>10000)throw new GatewayAdmissionException("GATEWAY_CONFIGURATION_INVALID");
        String key=model.key();capacities.ensure(key,limit);var bucket=capacity(key);
        if(!Boolean.TRUE.equals(bucket.getPolicyManaged()) && !Objects.equals(bucket.getConcurrencyLimit(),limit))throw new GatewayAdmissionException("GATEWAY_CONCURRENCY_POLICY_MISMATCH");
        int effectiveLimit=bucket.getConcurrencyLimit();
        if(bucket.getOccupied()==Long.MAX_VALUE || (effectiveLimit>0 && bucket.getOccupied()>=effectiveLimit))
            throw new GatewayAdmissionException("MODEL_CONCURRENCY_LIMIT_REACHED");
        bucket.setOccupied(bucket.getOccupied()+1);
        if(capacities.updateById(bucket)!=1)throw IqcException.invalidState("预留模型容量失败");
        var call=new GatewayCall();call.setCallId(UUID.randomUUID().toString().replace("-",""));call.setModelKey(key);
        call.setLogicalRecordId(context.logicalRecordId());call.setRuleId(context.ruleId());call.setOwner(context.owner());
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
                    || !Objects.equals(call.getOutputTokens(),invalid?null:usage.outputTokens()) || !Objects.equals(call.getTotalTokens(),invalid?null:usage.totalTokens()))
                throw IqcException.invalidState("已核销用量事实不可替换");
            return;
        }
        if(!"RESERVED".equals(call.getStatus()))throw IqcException.invalidState("未知调用不能自动确认为完成");
        var bucket=capacity(handle.modelKey());if(call.getCapacityReleasedAtUtc()==null && bucket.getOccupied()<1)throw IqcException.invalidState("模型占用证据不一致");
        call.setUsageStatus(usage.status());
        if(!"INVALID".equals(usage.status())){call.setInputTokens(usage.inputTokens());call.setOutputTokens(usage.outputTokens());call.setTotalTokens(usage.totalTokens());}
        String model=usage.responseModelId();
        if(model!=null && model.length()<=256 && model.codePoints().noneMatch(Character::isISOControl))call.setResponseModelId(model);
        call.setStatus("CONFIRMED");call.setSettledAtUtc(utc());
        boolean release=call.getCapacityReleasedAtUtc()==null;
        if(release)bucket.setOccupied(bucket.getOccupied()-1);
        if(calls.updateById(call)!=1 || (release && capacities.updateById(bucket)!=1))throw IqcException.invalidState("核销模型调用失败");
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public void unknown(Handle handle,RuntimeException failure){
        var call=lock(handle);if(!"RESERVED".equals(call.getStatus()))return;
        call.setStatus("UNKNOWN");call.setSettledAtUtc(utc());call.setUsageStatus("MISSING");
        String type=failure.getClass().getSimpleName();call.setErrorType(type.length()>128?"RuntimeException":type);
        if(calls.updateById(call)!=1)throw IqcException.invalidState("记录未知模型调用失败");
    }
}
