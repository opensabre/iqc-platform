package io.github.opensabre.iqc.result.llm.gateway;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.opensabre.iqc.result.llm.LlmQualityProperties;
import org.jasypt.encryption.StringEncryptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.nio.charset.StandardCharsets;
import java.time.*;

/** Optional encrypted diagnostic payloads on the original call ledger; never used for billing or replay. */
@Service
public class GatewayCallDataService {
    private final GatewayCallMapper rows;
    private final LlmQualityProperties properties;
    private final ObjectProvider<StringEncryptor> encryptors;
    private final ObjectProvider<io.github.opensabre.iqc.task.InspectionTaskService> tasks;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired
    public GatewayCallDataService(GatewayCallMapper rows,LlmQualityProperties properties,ObjectProvider<StringEncryptor> encryptors,
            ObjectProvider<io.github.opensabre.iqc.task.InspectionTaskService> tasks,com.fasterxml.jackson.databind.ObjectMapper json){
        this.rows=rows;this.properties=properties;this.encryptors=encryptors;this.tasks=tasks;this.json=json;
    }
    public GatewayCallDataService(GatewayCallMapper rows,LlmQualityProperties properties,ObjectProvider<StringEncryptor> encryptors){
        this(rows,properties,encryptors,null,new com.fasterxml.jackson.databind.ObjectMapper());
    }
    private String envelope(GatewayCallService.Handle handle,String side,String body){
        return json.createObjectNode().put("schemaVersion","iqc-model-call-data-v1").put("callId",handle.callId())
                .put("modelKey",handle.modelKey()).put("side",side).put("body",body).toString();
    }
    public boolean enabled(){return properties.getCallData()!=null && properties.getCallData().isEnabled();}
    private boolean fits(String value,int limit){return value==null || value.length()<=limit && value.getBytes(StandardCharsets.UTF_8).length<=limit;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void store(GatewayCallService.Handle handle,String request,String response){
        if(!enabled() || request==null && response==null)return;
        var policy=properties.getCallData();
        if(policy.getMaxBytesPerBody()<1 || policy.getMaxBytesPerBody()>1048576 || policy.getRetentionHours()<1 || policy.getRetentionHours()>720)
            throw new IllegalStateException("Model diagnostic retention configuration invalid");
        String requestEncrypted=null,responseEncrypted=null,status;
        if(!fits(request,policy.getMaxBytesPerBody()) || !fits(response,policy.getMaxBytesPerBody()))status="SIZE_LIMIT";
        else{
            var encryptor=encryptors.getIfAvailable();
            if(encryptor==null)throw new IllegalStateException("Model diagnostic encryptor unavailable");
            if(request!=null)requestEncrypted=encryptor.encrypt(envelope(handle,"REQUEST",request));
            if(response!=null)responseEncrypted=encryptor.encrypt(envelope(handle,"RESPONSE",response));
            status=request==null?"RESPONSE_ONLY":response==null?"REQUEST_ONLY":"CAPTURED";
        }
        var now=LocalDateTime.now(ZoneOffset.UTC);
        rows.update(null,Wrappers.<GatewayCall>lambdaUpdate().eq(GatewayCall::getCallId,handle.callId())
                .eq(GatewayCall::getModelKey,handle.modelKey()).isNull(GatewayCall::getCallDataRecordedAtUtc)
                .set(GatewayCall::getRequestDataEncrypted,requestEncrypted).set(GatewayCall::getResponseDataEncrypted,responseEncrypted)
                .set(GatewayCall::getCallDataStatus,status).set(GatewayCall::getCallDataRecordedAtUtc,now)
                .set(GatewayCall::getCallDataExpiresAtUtc,now.plusHours(policy.getRetentionHours())));
    }
    public record View(String schemaVersion,String callId,String taskId,String executionId,String state,
            LocalDateTime recordedAtUtc,LocalDateTime expiresAtUtc,String requestJson,String responseJson) { }
    private View view(GatewayCall row,String state,String request,String response){
        return new View("iqc-model-call-data-v1",row.getCallId(),row.getTaskId(),row.getExecutionId(),state,
                row.getCallDataRecordedAtUtc(),row.getCallDataExpiresAtUtc(),request,response);
    }
    private String decode(GatewayCall row,String side,String ciphertext,StringEncryptor encryptor) throws java.io.IOException {
        if(ciphertext==null)return null;
        if(ciphertext.length()>8*1048576)throw new IllegalArgumentException();
        var plaintext=encryptor.decrypt(ciphertext);
        if(plaintext==null || plaintext.length()>4*1048576)throw new IllegalArgumentException();
        var body=json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(plaintext);
        if(!body.isObject() || !"iqc-model-call-data-v1".equals(body.path("schemaVersion").asText())
                || !body.path("callId").isTextual() || !row.getCallId().equals(body.path("callId").textValue())
                || !body.path("modelKey").isTextual() || !row.getModelKey().equals(body.path("modelKey").textValue())
                || !side.equals(body.path("side").asText()) || !body.path("body").isTextual()
                || !fits(body.path("body").textValue(),1048576))throw new IllegalArgumentException();
        return body.path("body").textValue();
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public View read(String taskId,String callId){
        var taskService=tasks==null?null:tasks.getIfAvailable();
        if(taskService==null)throw io.github.opensabre.iqc.governance.IqcException.invalidState("诊断正文任务范围组件不可用");
        taskService.get(taskId);
        var row=rows.selectOne(Wrappers.<GatewayCall>lambdaQuery().eq(GatewayCall::getCallId,callId).eq(GatewayCall::getTaskId,taskId));
        if(row==null)throw io.github.opensabre.iqc.governance.IqcException.notFound("任务调用不存在");
        if("EXPIRED".equals(row.getCallDataStatus()) || row.getCallDataExpiresAtUtc()!=null
                && !row.getCallDataExpiresAtUtc().isAfter(LocalDateTime.now(ZoneOffset.UTC)))return view(row,"EXPIRED",null,null);
        if(row.getCallDataStatus()==null && row.getRequestDataEncrypted()==null && row.getResponseDataEncrypted()==null)
            return view(row,"NOT_RECORDED",null,null);
        if(row.getCallDataRecordedAtUtc()==null || row.getCallDataExpiresAtUtc()==null)return view(row,"UNREADABLE",null,null);
        if("SIZE_LIMIT".equals(row.getCallDataStatus()))return view(row,"SIZE_LIMIT",null,null);
        try{
            var encryptor=encryptors.getIfAvailable();if(encryptor==null)throw new IllegalStateException();
            var request=decode(row,"REQUEST",row.getRequestDataEncrypted(),encryptor);
            var response=decode(row,"RESPONSE",row.getResponseDataEncrypted(),encryptor);
            String expected=request==null?response==null?"": "RESPONSE_ONLY":response==null?"REQUEST_ONLY":"CAPTURED";
            if(!expected.equals(row.getCallDataStatus()))throw new IllegalArgumentException();
            if(!row.getCallDataExpiresAtUtc().isAfter(LocalDateTime.now(ZoneOffset.UTC)))return view(row,"EXPIRED",null,null);
            return view(row,expected,request,response);
        }catch(java.io.IOException | RuntimeException invalid){return view(row,"UNREADABLE",null,null);}
    }
    /** Purges existing archives even after capture is disabled; bounded work on the existing scheduler. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${iqc.llm.call-data.purge-delay-millis:60000}")
    @Transactional
    public void purgeExpired(){
        rows.update(null,Wrappers.<GatewayCall>lambdaUpdate().le(GatewayCall::getCallDataExpiresAtUtc,LocalDateTime.now(ZoneOffset.UTC))
                .isNotNull(GatewayCall::getCallDataExpiresAtUtc)
                .set(GatewayCall::getRequestDataEncrypted,null).set(GatewayCall::getResponseDataEncrypted,null)
                .set(GatewayCall::getCallDataStatus,"EXPIRED").set(GatewayCall::getCallDataExpiresAtUtc,null).last("LIMIT 200"));
    }
}
