package io.github.opensabre.iqc.result.llm.gateway;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.github.opensabre.iqc.result.llm.LlmQualityProperties;
import java.time.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.jasypt.encryption.StringEncryptor;
import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real encryption and update predicates; mapper I/O is isolated, SQL behavior needs its own validation. */
class GatewayCallDataBehaviorTest {
    private final GatewayCallMapper rows=mock(GatewayCallMapper.class);
    private final LlmQualityProperties properties=new LlmQualityProperties();
    @SuppressWarnings("unchecked") private final ObjectProvider<StringEncryptor> encryptors=mock(ObjectProvider.class);
    private final GatewayCallDataService service=new GatewayCallDataService(rows,properties,encryptors);
    private final GatewayCallService.Handle handle=new GatewayCallService.Handle("call","model");
    @BeforeAll static void metadata(){TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"test"),GatewayCall.class);}
    @SuppressWarnings({"rawtypes","unchecked"}) private LambdaUpdateWrapper<GatewayCall> update(){
        var captor=org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(rows).update(isNull(),captor.capture());var wrapper=(LambdaUpdateWrapper<GatewayCall>)captor.getValue();
        wrapper.getSqlSet();wrapper.getSqlSegment();return wrapper;
    }
    @Test void defaultsDisableCaptureWithoutTouchingEncryptorOrMapper(){
        assertFalse(service.enabled());assertEquals(65536,properties.getCallData().getMaxBytesPerBody());assertEquals(24,properties.getCallData().getRetentionHours());
        service.store(handle,"secret","response");verifyNoInteractions(rows,encryptors);
    }
    @Test void enabledCaptureEncryptsBothSidesAndFencesFirstWriteWithBoundedExpiry(){
        properties.getCallData().setEnabled(true);
        var cipher=new StandardPBEStringEncryptor();cipher.setPassword("local-validation-only");cipher.setAlgorithm("PBEWITHHMACSHA512ANDAES_256");cipher.setIvGenerator(new org.jasypt.iv.RandomIvGenerator());
        when(encryptors.getIfAvailable()).thenReturn(cipher);var before=LocalDateTime.now(ZoneOffset.UTC);
        service.store(handle,"request 私密","response 私密");var update=update();
        assertTrue(update.getSqlSegment().contains("call_data_recorded_at_utc IS NULL"));
        assertTrue(update.getSqlSegment().contains("call_id"));assertTrue(update.getSqlSegment().contains("model_key"));
        var values=update.getParamNameValuePairs().values();assertTrue(values.contains("call"));assertTrue(values.contains("model"));assertTrue(values.contains("CAPTURED"));
        assertFalse(values.contains("request 私密"));assertFalse(values.contains("response 私密"));
        var decoded=new java.util.ArrayList<String>();
        for(Object value:values)if(value instanceof String text)try{decoded.add(new com.fasterxml.jackson.databind.ObjectMapper().readTree(cipher.decrypt(text)).path("body").asText());}catch(org.jasypt.exceptions.EncryptionOperationNotPossibleException | java.io.IOException ignored){}
        assertEquals(java.util.Set.of("request 私密","response 私密"),new java.util.HashSet<>(decoded));
        var times=values.stream().filter(LocalDateTime.class::isInstance).map(LocalDateTime.class::cast).sorted().toList();
        assertEquals(2,times.size());assertFalse(times.get(0).isBefore(before));assertEquals(Duration.ofHours(24),Duration.between(times.get(0),times.get(1)));
    }
    @Test void utf8LimitStoresNoPartialBodyAndDoesNotEncrypt(){
        properties.getCallData().setEnabled(true);properties.getCallData().setMaxBytesPerBody(4);
        service.store(handle,"中文","ok");var update=update();assertTrue(update.getParamNameValuePairs().containsValue("SIZE_LIMIT"));verifyNoInteractions(encryptors);
        assertFalse(update.getParamNameValuePairs().containsValue("ok"));assertFalse(update.getParamNameValuePairs().containsValue("中文"));
    }
    @Test void missingEncryptorOrEncryptionFailureWritesNothing(){
        properties.getCallData().setEnabled(true);assertThrows(IllegalStateException.class,()->service.store(handle,"req","res"));
        var cipher=mock(StringEncryptor.class);when(encryptors.getIfAvailable()).thenReturn(cipher);when(cipher.encrypt(anyString())).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(IllegalStateException.class,()->service.store(handle,"req","res"));verifyNoInteractions(rows);
    }
    @Test void requestOnlyCaptureIsExplicitAndEmptyCaptureIsSkipped(){
        properties.getCallData().setEnabled(true);service.store(handle,null,null);verifyNoInteractions(rows,encryptors);
        var cipher=mock(StringEncryptor.class);when(encryptors.getIfAvailable()).thenReturn(cipher);when(cipher.encrypt(anyString())).thenReturn("encrypted");
        service.store(handle,"req",null);assertTrue(update().getParamNameValuePairs().containsValue("REQUEST_ONLY"));
    }
    @Test void invalidRetentionOrSizeConfigurationWritesNothing(){
        properties.getCallData().setEnabled(true);
        for(int value:new int[]{0,1048577}){properties.getCallData().setMaxBytesPerBody(value);assertThrows(IllegalStateException.class,()->service.store(handle,"req","res"));}
        properties.getCallData().setMaxBytesPerBody(4);
        for(int value:new int[]{0,721}){properties.getCallData().setRetentionHours(value);assertThrows(IllegalStateException.class,()->service.store(handle,"req","res"));}
        verifyNoInteractions(rows,encryptors);
    }
    @Test void purgeRunsWhenCaptureDisabledAndOnlyClearsPayloadFields(){
        assertFalse(service.enabled());service.purgeExpired();var update=update();
        assertTrue(update.getSqlSegment().contains("LIMIT 200"));assertTrue(update.getSqlSegment().contains("call_data_expires_at_utc"));
        assertTrue(update.getParamNameValuePairs().containsValue("EXPIRED"));
        String sql=update.getSqlSet();assertTrue(sql.contains("request_data_encrypted"));assertTrue(sql.contains("response_data_encrypted"));
        assertFalse(sql.contains("total_tokens"));assertFalse(sql.contains("calculated_cost"));assertFalse(java.util.regex.Pattern.compile("(?:^|,)\\s*status\\s*=").matcher(sql).find());verifyNoInteractions(encryptors);
    }
}
