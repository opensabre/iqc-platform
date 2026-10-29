package io.github.opensabre.iqc.task;

import io.github.opensabre.iqc.result.InspectionExecutionService;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Bulk withdrawal must not depend on a free model slot; discovery must serve both task families. */
class DurableBulkWorkerTest {
    private final InspectionTaskMapper tasks=mock(InspectionTaskMapper.class);
    private final InspectionTaskService service=mock(InspectionTaskService.class);
    private final InspectionExecutionService execution=mock(InspectionExecutionService.class);
    private final BulkTaskCoordinator coordinator=mock(BulkTaskCoordinator.class);
    private final ThreadPoolTaskExecutor executor=mock(ThreadPoolTaskExecutor.class);
    private final ThreadPoolExecutor pool=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16));
    private final List<Runnable> submitted=new ArrayList<>();
    private DurableInspectionWorker worker;
    @BeforeEach void setup() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "worker-query"), InspectionTask.class);
        var providers=new StaticListableBeanFactory();providers.addBean("bulk",coordinator);
        worker=new DurableInspectionWorker(tasks,service,execution,executor,null,providers.getBeanProvider(BulkTaskCoordinator.class));
        when(executor.getThreadPoolExecutor()).thenReturn(pool);
        doAnswer(call->{submitted.add(call.getArgument(0));return null;}).when(executor).execute(any(Runnable.class));
        worker.validate();
    }
    @AfterEach void close(){pool.shutdownNow();}
    private InspectionTask task(String id,boolean bulk,String status){var t=new InspectionTask();t.setId(id);t.setTaskType(bulk?"PIPELINE_BULK":"PIPELINE");t.setCurrentExecutionId("e-"+id);t.setStatus(status);return t;}

    @Test void fullExecutorStillPropagatesStopsWithoutSubmittingWork() {
        for(int i=0;i<16;i++)pool.getQueue().add(()->{});
        when(tasks.selectList(any())).thenReturn(List.of(task("pause",true,"PAUSE_REQUESTED"),task("cancel",true,"CANCEL_REQUESTED")));
        worker.discoverQueuedTasks();
        verify(coordinator).controlRequested("pause","e-pause");verify(coordinator).controlRequested("cancel","e-cancel");
        verify(coordinator,never()).executePersisted(anyString(),anyString());verifyNoInteractions(execution);
        verify(executor,never()).execute(any(Runnable.class));verify(tasks,times(1)).selectList(any());
    }
    @Test void fullInFlightStillPropagatesNewStopWithoutOpeningMoreSlots() {
        var leaves=java.util.stream.IntStream.range(0,8).mapToObj(i->task("leaf"+i,false,"QUEUED")).toList();
        when(tasks.selectList(any())).thenReturn(List.of(),leaves,List.of(),List.of(task("pause",true,"PAUSE_REQUESTED")));
        worker.discoverQueuedTasks();assertThat(submitted).hasSize(8);
        worker.discoverQueuedTasks();assertThat(submitted).hasSize(8);
        verify(coordinator).controlRequested("pause","e-pause");verify(tasks,times(4)).selectList(any());verifyNoInteractions(execution);
    }
    @Test void oneSlotAlternatesFamiliesEvenWithHundredQueuedParents() {
        ReflectionTestUtils.setField(worker,"maximumInFlight",1);
        var parents=java.util.stream.IntStream.range(0,100).mapToObj(i->task("parent"+i,true,"QUEUED")).toList();
        var leaves=java.util.stream.IntStream.range(0,100).mapToObj(i->task("leaf"+i,false,"QUEUED")).toList();
        when(tasks.selectList(any())).thenReturn(List.of(),leaves,parents,List.of(),leaves,parents);
        worker.discoverQueuedTasks();assertThat(submitted).hasSize(1);submitted.getFirst().run();
        worker.discoverQueuedTasks();assertThat(submitted).hasSize(2);submitted.getLast().run();
        verify(execution).executePersisted("leaf0","e-leaf0");verify(coordinator).executePersisted("parent0","e-parent0");
        verify(execution,never()).executePersisted("leaf1","e-leaf1");
    }
    @Test void completedInlineDispatchStillOffersEachLeaseOnlyOncePerDiscovery() {
        var leaf=task("leaf",false,"QUEUED");var parent=task("parent",true,"QUEUED");
        when(tasks.selectList(any())).thenReturn(List.of(),List.of(leaf,leaf),List.of(parent,parent));
        doAnswer(call->{((Runnable)call.getArgument(0)).run();return null;}).when(executor).execute(any(Runnable.class));
        worker.discoverQueuedTasks();
        verify(execution,times(1)).executePersisted("leaf","e-leaf");verify(coordinator,times(1)).executePersisted("parent","e-parent");
    }
}
