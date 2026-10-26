package io.github.opensabre.iqc.result.llm.gateway;

import io.github.opensabre.common.core.entity.vo.Result;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.shared.IqcModelMonitoringClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Facade authorization and bounded wire protocol; control-plane and current actor are controlled. */
class ModelMonitoringServiceTest {
    static final List<String> BASE=List.of("invocationTps","unknownRatio","p50","p95","p99","reportedTokens","usageCoverage","admission","settlement");
    static final List<String> NODES=List.of("pipelineTps","pipelineP95","pipelineActive","nodeAttemptTps","nodeSuccessRatio","nodeP95","nodeSkippedTps","nodeExecutorActive","nodeExecutorQueued");
    static final List<String> TASKS=List.of("taskStates","taskCompletionTps","unmeasuredExecutions","taskMetricsHealth","taskMetricsAge");
    static final Instant START=Instant.parse("2026-10-26T00:00:00Z"),END=START.plusSeconds(3600);
    static final String EMPTY="{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}";
    final IqcModelMonitoringClient control=mock(IqcModelMonitoringClient.class);
    final IqcDataScope scope=mock(IqcDataScope.class);
    final ModelMonitoringService service=new ModelMonitoringService(control,scope);
    @BeforeEach void authorize(){when(scope.isIqcAdministrator()).thenReturn(true);}
    Map<String,String> series(int size){
        var map=new LinkedHashMap<String,String>();BASE.forEach(k->map.put(k,EMPTY));
        if(size>=18)NODES.forEach(k->map.put(k,EMPTY));
        if(size>=23)TASKS.forEach(k->map.put(k,EMPTY));
        if(size==10||size==19||size==24)map.put("activeModelCalls",EMPTY);
        return map;
    }
    IqcModelMonitoringClient.History history(String range,Instant start,Instant end,long step,Map<String,String> data){
        return new IqcModelMonitoringClient.History(range,start,end,step,data);
    }
    void respond(IqcModelMonitoringClient.History data){when(control.history("1h")).thenReturn(Result.success(data));}
    @Test void rechecksCurrentAdministratorBeforeAnyControlPlaneIo(){
        respond(history("1h",START,END,30,series(9)));
        service.history("1h");clearInvocations(control);
        when(scope.isIqcAdministrator()).thenReturn(false);
        assertThrows(IqcException.class,()->service.history("1h"));
        assertThrows(IqcException.class,service::status);verifyNoInteractions(control);
    }
    @ParameterizedTest @ValueSource(ints={9,10,18,19,23,24})
    void supportsOnlyDeclaredCompleteProtocolCombinationsAndReturnsIndependentImmutableMap(int size){
        var input=series(size);respond(history("1h",START,END,30,input));
        var returned=service.history("1h");assertEquals(size,returned.series().size());assertEquals(input,returned.series());
        assertThrows(UnsupportedOperationException.class,()->returned.series().put("unexpected",EMPTY));
        input.put("p95","changed");assertEquals(EMPTY,returned.series().get("p95"));
    }
    @Test void rejectsEveryMissingKeyInMandatoryAndOptionalGroupsAndUnknownKeys(){
        for(int size:List.of(9,18,23))for(String key:series(size).keySet()){
            var partial=series(size);partial.remove(key);respond(history("1h",START,END,30,partial));
            assertThrows(IqcException.class,()->service.history("1h"),"missing "+key+" in "+size);
        }
        var taskWithoutNodes=series(9);TASKS.forEach(k->taskWithoutNodes.put(k,EMPTY));
        respond(history("1h",START,END,30,taskWithoutNodes));assertThrows(IqcException.class,()->service.history("1h"));
        for(int size:List.of(9,10,18,19,23,24)){
            var unknown=series(size);unknown.put("arbitraryPromql",EMPTY);respond(history("1h",START,END,30,unknown));
            assertThrows(IqcException.class,()->service.history("1h"));
        }
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"5m","1H"," 1h","1h ","up{job=\"other\"}"})
    void rejectsUnlistedRangeBeforeIo(String range){assertThrows(IqcException.class,()->service.history(range));verifyNoInteractions(control);}
    @ParameterizedTest @ValueSource(strings={"15m","1h","6h","24h","7d","30d"})
    void forwardsOnlyTheExplicitSupportedRange(String range){
        when(control.history(range)).thenReturn(Result.success(history(range,START,END,30,series(9))));
        assertEquals(range,service.history(range).range());verify(control).history(range);
    }
    @Test void rejectsMalformedHistoryEnvelopeAndNullOrOversizedMatrices(){
        var valid=series(24);var badValue=new LinkedHashMap<>(valid);badValue.put("p95",null);
        var oversized=new LinkedHashMap<>(valid);oversized.put("activeModelCalls","x".repeat(1048577));
        for(var malformed:List.of(history("15m",START,END,30,valid),history("1h",null,END,30,valid),
                history("1h",START,null,30,valid),history("1h",START,START,30,valid),history("1h",END,START,30,valid),
                history("1h",START,END,0,valid),history("1h",START,END,-1,valid),history("1h",START,END,30,null),
                history("1h",START,END,30,badValue),history("1h",START,END,30,oversized))){
            respond(malformed);assertThrows(IqcException.class,()->service.history("1h"));
        }
        valid.put("p95","x".repeat(1048576));respond(history("1h",START,END,30,valid));
        assertEquals(1048576,service.history("1h").series().get("p95").length());
    }
    @Test void unavailableControlPlaneUsesSanitizedErrorsWithoutReturningPartialHistory(){
        when(control.history("1h")).thenReturn(null,Result.success(null));
        for(int i=0;i<2;i++)assertThrows(IqcException.class,()->service.history("1h"));
        when(control.history("1h")).thenThrow(new IllegalStateException("private endpoint and token"));
        var error=assertThrows(IqcException.class,()->service.history("1h"));assertFalse(error.getMessage().contains("token"));
        when(control.status()).thenReturn(null,Result.success(null));
        for(int i=0;i<2;i++)assertThrows(IqcException.class,service::status);
        when(control.status()).thenThrow(new IllegalStateException("private token"));
        assertFalse(assertThrows(IqcException.class,service::status).getMessage().contains("token"));
    }
    @Test void statusSanitizesMessagesAndRemovesTargetsWhenUnavailable(){
        when(control.status()).thenReturn(Result.success(new IqcModelMonitoringClient.SourceStatus(false,"private token","private targets")));
        var unavailable=service.status();assertFalse(unavailable.available());assertNull(unavailable.targets());
        assertEquals("监控数据源不可用",unavailable.message());
        for(String invalid:Arrays.asList(null,"x".repeat(1048577))){
            when(control.status()).thenReturn(Result.success(new IqcModelMonitoringClient.SourceStatus(true,"ignored",invalid)));
            assertThrows(IqcException.class,service::status);
        }
        String boundary="x".repeat(1048576);
        when(control.status()).thenReturn(Result.success(new IqcModelMonitoringClient.SourceStatus(true,"private token",boundary)));
        var available=service.status();assertTrue(available.available());assertEquals(boundary,available.targets());
        assertEquals("监控数据源可查询",available.message());
    }
}
