package io.github.opensabre.iqc.result.llm.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.result.llm.LlmQualityProperties;
import io.github.opensabre.iqc.task.InspectionTaskService;
import io.github.opensabre.iqc.governance.IqcException;
import java.time.*;
import org.jasypt.encryption.StringEncryptor;
import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual encrypted envelopes and read service; task authorization and mapper are isolated. */
class GatewayCallDataReadTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final GatewayCallMapper rows=mock(GatewayCallMapper.class);
    private final InspectionTaskService tasks=mock(InspectionTaskService.class);
    @SuppressWarnings("unchecked") private final ObjectProvider<InspectionTaskService> taskProvider=mock(ObjectProvider.class);
    @SuppressWarnings("unchecked") private final ObjectProvider<StringEncryptor> encryptors=mock(ObjectProvider.class);
    private final StandardPBEStringEncryptor cipher=new StandardPBEStringEncryptor();
    private final GatewayCallDataService service=new GatewayCallDataService(rows,new LlmQualityProperties(),encryptors,taskProvider,json);
    private GatewayCall row;
    @BeforeEach void fixture(){
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),"read"),GatewayCall.class);
        cipher.setPassword("local-validation-only");cipher.setAlgorithm("PBEWITHHMACSHA512ANDAES_256");cipher.setIvGenerator(new org.jasypt.iv.RandomIvGenerator());
        when(taskProvider.getIfAvailable()).thenReturn(tasks);when(encryptors.getIfAvailable()).thenReturn(cipher);
        row=new GatewayCall();row.setCallId("call");row.setModelKey("model");row.setTaskId("task");row.setExecutionId("run");row.setCallDataStatus("CAPTURED");
        row.setCallDataRecordedAtUtc(LocalDateTime.now(ZoneOffset.UTC));row.setCallDataExpiresAtUtc(row.getCallDataRecordedAtUtc().plusHours(1));
        row.setRequestDataEncrypted(envelope("call","model","REQUEST","private-request"));row.setResponseDataEncrypted(envelope("call","model","RESPONSE","private-response"));when(rows.selectOne(any())).thenReturn(row);
    }
    private String envelope(String id,String model,String side,String body){return cipher.encrypt(json.createObjectNode().put("schemaVersion","iqc-model-call-data-v1").put("callId",id).put("modelKey",model).put("side",side).put("body",body).toString());}
    @Test void validEnvelopesReadWithoutChangingStoredCiphertext()throws Exception{
        String before=json.writeValueAsString(row);var view=service.read("task","call");assertEquals("CAPTURED",view.state());assertEquals("private-request",view.requestJson());assertEquals("private-response",view.responseJson());
        assertEquals(before,json.writeValueAsString(row));var order=inOrder(tasks,rows,encryptors);order.verify(tasks).get("task");order.verify(rows).selectOne(any());order.verify(encryptors).getIfAvailable();
        var captor=org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);verify(rows).selectOne(captor.capture());
        String sql=captor.getValue().getSqlSegment();assertTrue(sql.contains("call_id"));assertTrue(sql.contains("task_id"));
        assertEquals("call",view.callId());assertEquals("run",view.executionId());
    }
    @ParameterizedTest @ValueSource(strings={"call","model","side","schema","plaintext","trailing","status","ciphertext"})
    void invalidBindingOrFormatNeverReturnsEitherBody(String tamper){
        switch(tamper){
            case "call"->row.setRequestDataEncrypted(envelope("other","model","REQUEST","private-request"));
            case "model"->row.setRequestDataEncrypted(envelope("call","other","REQUEST","private-request"));
            case "side"->row.setRequestDataEncrypted(row.getResponseDataEncrypted());
            case "schema"->row.setRequestDataEncrypted(cipher.encrypt("{\"schemaVersion\":\"unknown\"}"));
            case "plaintext"->row.setRequestDataEncrypted(cipher.encrypt("private-request"));
            case "trailing"->row.setRequestDataEncrypted(cipher.encrypt("{} {}"));
            case "status"->row.setCallDataStatus("REQUEST_ONLY");
            case "ciphertext"->row.setResponseDataEncrypted("broken-ciphertext");
        }
        var view=service.read("task","call");assertEquals("UNREADABLE",view.state());assertNull(view.requestJson());assertNull(view.responseJson());
    }
    @Test void expiredBodyIsDeniedBeforeDecryptEvenBeforePhysicalPurge(){
        row.setCallDataExpiresAtUtc(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));var view=service.read("task","call");assertEquals("EXPIRED",view.state());assertNull(view.requestJson());assertNull(view.responseJson());verifyNoInteractions(encryptors);
    }
    @Test void taskScopeDenialPreventsLedgerAndEncryptorAccess(){
        when(tasks.get("task")).thenThrow(IqcException.accessDenied("scope"));assertThrows(IqcException.class,()->service.read("task","call"));verifyNoInteractions(rows,encryptors);
    }
    @Test void missingTaskOrCallCannotDecrypt(){
        when(taskProvider.getIfAvailable()).thenReturn(null);assertThrows(IqcException.class,()->service.read("task","call"));verifyNoInteractions(rows,encryptors);
        when(taskProvider.getIfAvailable()).thenReturn(tasks);when(rows.selectOne(any())).thenReturn(null);assertThrows(IqcException.class,()->service.read("task","call"));verifyNoInteractions(encryptors);
    }
    @Test void absentAndSizeLimitedArchivesNeverDecrypt(){
        row.setCallDataStatus(null);row.setRequestDataEncrypted(null);row.setResponseDataEncrypted(null);assertEquals("NOT_RECORDED",service.read("task","call").state());
        row.setCallDataStatus("SIZE_LIMIT");assertEquals("SIZE_LIMIT",service.read("task","call").state());verifyNoInteractions(encryptors);
    }
    @Test void unavailableKeyWithholdsAllBodies(){
        when(encryptors.getIfAvailable()).thenReturn(null);var view=service.read("task","call");assertEquals("UNREADABLE",view.state());assertNull(view.requestJson());assertNull(view.responseJson());
    }
}
