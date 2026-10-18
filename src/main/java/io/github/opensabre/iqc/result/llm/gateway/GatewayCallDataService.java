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
    public GatewayCallDataService(GatewayCallMapper rows,LlmQualityProperties properties,ObjectProvider<StringEncryptor> encryptors){
        this.rows=rows;this.properties=properties;this.encryptors=encryptors;
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
            if(request!=null)requestEncrypted=encryptor.encrypt(request);
            if(response!=null)responseEncrypted=encryptor.encrypt(response);
            status=request==null?"RESPONSE_ONLY":response==null?"REQUEST_ONLY":"CAPTURED";
        }
        var now=LocalDateTime.now(ZoneOffset.UTC);
        rows.update(null,Wrappers.<GatewayCall>lambdaUpdate().eq(GatewayCall::getCallId,handle.callId())
                .eq(GatewayCall::getModelKey,handle.modelKey()).isNull(GatewayCall::getCallDataRecordedAtUtc)
                .set(GatewayCall::getRequestDataEncrypted,requestEncrypted).set(GatewayCall::getResponseDataEncrypted,responseEncrypted)
                .set(GatewayCall::getCallDataStatus,status).set(GatewayCall::getCallDataRecordedAtUtc,now)
                .set(GatewayCall::getCallDataExpiresAtUtc,now.plusHours(policy.getRetentionHours())));
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
