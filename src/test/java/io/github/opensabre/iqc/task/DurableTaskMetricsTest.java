package io.github.opensabre.iqc.task;

import io.github.opensabre.iqc.result.InspectionExecutionService;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper.OperationalCount;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real cached Gauges; SQL rows and monotonic snapshot age are controlled, no database or scheduler. */
class DurableTaskMetricsTest {
    final InspectionTaskMapper tasks=mock(InspectionTaskMapper.class);
    final InspectionTaskService service=mock(InspectionTaskService.class);
    final InspectionExecutionService execution=mock(InspectionExecutionService.class);
    final ThreadPoolTaskExecutor executor=mock(ThreadPoolTaskExecutor.class);
    final SimpleMeterRegistry meters=new SimpleMeterRegistry();
    DurableInspectionWorker worker;
    @BeforeEach @SuppressWarnings("unchecked") void setup(){
        ObjectProvider<MeterRegistry> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(meters);
        worker=new DurableInspectionWorker(tasks,service,execution,executor,provider);worker.validate();
    }
    @AfterEach void closeRegistry(){meters.close();}
    double health(){return meters.get("iqc.task.metrics.sample.success").gauge().value();}
    double age(){return meters.get("iqc.task.metrics.sample.age").gauge().value();}
    double value(String name,String mode,String status){return meters.get(name).tags("mode",mode,"status",status).gauge().value();}
    List<Gauge> counts(){return meters.getMeters().stream().filter(m->!m.getId().getName().startsWith("iqc.task.metrics.sample.")).map(m->(Gauge)m).toList();}
    @Test void unmeasuredStartsAsNanAndScrapesNeverQueryDatabase(){
        assertEquals(36,meters.getMeters().size());assertEquals(34,counts().size());
        for(int pass=0;pass<3;pass++){
            counts().forEach(g->assertTrue(Double.isNaN(g.value())));assertEquals(0,health());assertTrue(Double.isNaN(age()));
        }
        verifyNoInteractions(tasks,service,execution,executor);
    }
    @Test void successfulEmptyReadIsRealZeroInAllFixedDimensions(){
        when(tasks.operationalCounts()).thenReturn(List.of());worker.sampleOperationalMetrics();
        counts().forEach(g->assertEquals(0,g.value()));assertEquals(1,health());assertTrue(Double.isFinite(age()));
        for(var gauge:counts()){
            assertEquals(Set.of("mode","status"),gauge.getId().getTags().stream().map(Tag::getKey).collect(java.util.stream.Collectors.toSet()));
        }
        clearInvocations(tasks);meters.getMeters().forEach(m->((Gauge)m).value());verifyNoInteractions(tasks,service,execution,executor);
    }
    @Test void mapsBothModesAndMeasureFamiliesWithoutLosingBudgetWaitOrUnmeasuredTerminal(){
        when(tasks.operationalCounts()).thenReturn(List.of(
                new OperationalCount("PRODUCTION","TASK_STATE","BUDGET_WAIT",7),
                new OperationalCount("EVALUATION","TASK_STATE","QUEUED",3),
                new OperationalCount("PRODUCTION","COMPLETED_5M","CANCELLED",2),
                new OperationalCount("EVALUATION","UNMEASURED_TERMINAL","FAILED",5)));
        worker.sampleOperationalMetrics();assertEquals(1,health());
        assertEquals(7,value("iqc.task.persisted.state","PRODUCTION","BUDGET_WAIT"));
        assertEquals(3,value("iqc.task.persisted.state","EVALUATION","QUEUED"));
        assertEquals(2,value("iqc.task.execution.completed.window","PRODUCTION","CANCELLED"));
        assertEquals(5,value("iqc.task.execution.unmeasured","EVALUATION","FAILED"));
        assertEquals(0,value("iqc.task.execution.completed.window","PRODUCTION","SUCCEEDED"));
        verify(tasks).operationalCounts();verifyNoInteractions(service,execution,executor);
    }
    @Test void everyMalformedResultInvalidatesTheWholePreviousSample(){
        var good=new OperationalCount("PRODUCTION","TASK_STATE","QUEUED",9);
        var malformed=new ArrayList<List<OperationalCount>>();malformed.add(null);malformed.add(Arrays.asList((OperationalCount)null));
        malformed.add(List.of(good,good));
        malformed.add(List.of(new OperationalCount("PRODUCTION","TASK_STATE","QUEUED",-1)));
        malformed.add(List.of(new OperationalCount("unknown","TASK_STATE","QUEUED",1)));
        malformed.add(List.of(new OperationalCount("PRODUCTION","unknown","QUEUED",1)));
        malformed.add(List.of(new OperationalCount("PRODUCTION","TASK_STATE","SUCCEEDED",1)));
        malformed.add(List.of(new OperationalCount(null,"TASK_STATE","QUEUED",1)));
        malformed.add(List.of(new OperationalCount("PRODUCTION",null,"QUEUED",1)));
        malformed.add(List.of(new OperationalCount("PRODUCTION","TASK_STATE",null,1)));
        for(var rows:malformed){
            when(tasks.operationalCounts()).thenReturn(List.of(good));worker.sampleOperationalMetrics();assertEquals(1,health());
            when(tasks.operationalCounts()).thenReturn(rows);assertDoesNotThrow(worker::sampleOperationalMetrics);
            assertEquals(0,health());counts().forEach(g->assertTrue(Double.isNaN(g.value())));assertTrue(Double.isFinite(age()));
        }
    }
    @Test void databaseFailureCreatesGapsAndCanRecoverOnNextSuccessfulSample(){
        when(tasks.operationalCounts()).thenReturn(List.of());worker.sampleOperationalMetrics();
        when(tasks.operationalCounts()).thenThrow(new IllegalStateException("database unavailable"));
        assertDoesNotThrow(worker::sampleOperationalMetrics);assertEquals(0,health());counts().forEach(g->assertTrue(Double.isNaN(g.value())));
        // Reset the throwing stub before arranging recovery.
        doReturn(List.of(new OperationalCount("PRODUCTION","TASK_STATE","RUNNING",4))).when(tasks).operationalCounts();
        worker.sampleOperationalMetrics();assertEquals(1,health());assertEquals(4,value("iqc.task.persisted.state","PRODUCTION","RUNNING"));
    }
    @Test @SuppressWarnings("unchecked") void staleMonotonicSnapshotIsNotServedAsFresh() throws Exception {
        when(tasks.operationalCounts()).thenReturn(List.of());worker.sampleOperationalMetrics();
        var reference=(AtomicReference<Object>)ReflectionTestUtils.getField(worker,"operational");var snapshot=reference.get();
        var constructor=snapshot.getClass().getDeclaredConstructor(Map.class,long.class);constructor.setAccessible(true);
        long staleTick=System.nanoTime()-TimeUnit.SECONDS.toNanos(91);
        reference.set(constructor.newInstance(ReflectionTestUtils.getField(snapshot,"counts"),staleTick));
        ((AtomicLong)ReflectionTestUtils.getField(worker,"lastOperationalSuccess")).set(staleTick);
        clearInvocations(tasks);assertEquals(0,health());assertTrue(age()>=91);
        counts().forEach(g->assertTrue(Double.isNaN(g.value())));verifyNoInteractions(tasks);
    }
    @Test void originalNoRegistryConstructorNeverSamples(){
        var legacy=new DurableInspectionWorker(tasks,service,execution,executor);legacy.validate();legacy.sampleOperationalMetrics();
        verifyNoInteractions(tasks,service,execution,executor);
    }
    @ParameterizedTest @CsvSource({"999,90000","3600001,10800000","30000,59999","30000,10800001"})
    void rejectsUnsafeSamplingIntervals(long delay,long maximumAge){
        ReflectionTestUtils.setField(worker,"sampleDelayMs",delay);ReflectionTestUtils.setField(worker,"maximumSampleAgeMs",maximumAge);
        assertThrows(IllegalArgumentException.class,worker::validate);verifyNoInteractions(tasks);
    }
    @ParameterizedTest @CsvSource({"1000,2000","30000,90000","3600000,10800000"})
    void acceptsDocumentedSamplingBoundaries(long delay,long maximumAge){
        ReflectionTestUtils.setField(worker,"sampleDelayMs",delay);ReflectionTestUtils.setField(worker,"maximumSampleAgeMs",maximumAge);
        assertDoesNotThrow(worker::validate);verifyNoInteractions(tasks);
    }
}
