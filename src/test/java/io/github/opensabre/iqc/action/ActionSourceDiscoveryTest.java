package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActionSourceDiscoveryTest {
    private final ActionSourceDiscoveryMapper sources=mock(ActionSourceDiscoveryMapper.class);
    private final InspectionTaskMapper tasks=mock(InspectionTaskMapper.class);
    private final ScheduledActorAccessService actors=mock(ScheduledActorAccessService.class);
    private final IqcDataScope scope=mock(IqcDataScope.class);
    private final ActionEnqueueService enqueue=mock(ActionEnqueueService.class);
    private final ActionSourceDiscovery worker=new ActionSourceDiscovery(sources,tasks,actors,scope,enqueue,new ObjectMapper());
    private ActionSourceDiscoveryMapper.Source source(String id) {return new ActionSourceDiscoveryMapper.Source(id,"task","execution","conversation","alice");}
    private void valid() {
        var task=new InspectionTask();task.setRuleSnapshotJson("{\"actionOwnerBound\":{\"schemaVersion\":\"iqc-schedule-owner-scope-v1\",\"userId\":\"user\",\"owner\":\"alice\",\"all\":false}}");
        when(tasks.selectById("task")).thenReturn(task);
        var actor=new ScheduledActorAccessService.Resolved("user","alice",false,null,Set.of(),ActionEnqueueService.REQUIRED_GRANTS,Instant.now());
        when(actors.resolve(any(),eq(ActionEnqueueService.REQUIRED_GRANTS))).thenReturn(actor);
        doAnswer(call->((java.util.function.Supplier<?>)call.getArgument(1)).get()).when(scope).asScheduledActor(eq(actor),any());
    }
    @Test void advancesPastFullPageWithNoIdentityToReachLaterSource() {
        var first=java.util.stream.IntStream.rangeClosed(1,100).mapToObj(i->source(String.format("r%03d",i))).toList();
        when(sources.findAfter("")).thenReturn(first);when(sources.findAfter("r100")).thenReturn(List.of(source("r101")));
        var absent=new InspectionTask();absent.setRuleSnapshotJson("{}");when(tasks.selectById("task")).thenReturn(absent);
        worker.tick();verifyNoInteractions(actors,enqueue);
        valid();worker.tick();verify(enqueue).enqueue(eq(new ActionProductionSourceValidator.Reference("task","execution","conversation","r101","alice")),any());
    }
    @Test void perSourceFailureDoesNotStarveNextSource() {
        valid();when(sources.findAfter("")).thenReturn(List.of(source("a"),source("b")));
        when(enqueue.enqueue(argThat(r->r.resultId().equals("a")),any())).thenThrow(new IllegalStateException("unavailable"));
        worker.tick();verify(enqueue).enqueue(argThat(r->r.resultId().equals("b")),any());
    }
    @Test void endOfScanWrapsForRecoveryAndReplay() {
        valid();when(sources.findAfter("")).thenReturn(List.of(source("a")));when(sources.findAfter("a")).thenReturn(List.of());
        worker.tick();worker.tick();worker.tick();verify(enqueue,times(2)).enqueue(any(),any());
    }
}
