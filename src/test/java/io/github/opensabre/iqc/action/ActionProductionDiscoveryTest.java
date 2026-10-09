package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class ActionProductionDiscoveryTest {
    private final ActionDiscoveryMapper sources=mock(ActionDiscoveryMapper.class);
    private final ScheduledActorAccessService actors=mock(ScheduledActorAccessService.class);
    private final IqcDataScope scope=mock(IqcDataScope.class);
    private final ActionEnqueueService enqueue=mock(ActionEnqueueService.class);
    private final ActionProductionDiscovery worker=new ActionProductionDiscovery(sources,enqueue,actors,scope,new ObjectMapper());
    private ActionDiscoveryMapper.Source source(String id) {return new ActionDiscoveryMapper.Source(id,"task","execution","conversation","alice");}
    private void valid() {
        when(sources.taskSnapshot("task")).thenReturn("{\"actionOwnerBound\":{\"schemaVersion\":\"iqc-schedule-owner-scope-v1\",\"userId\":\"user\",\"owner\":\"alice\",\"all\":false}}");
        var actor=new ScheduledActorAccessService.Resolved("user","alice",false,null,Set.of(),ActionEnqueueService.REQUIRED_GRANTS,Instant.now());
        when(actors.resolve(any(),eq(ActionEnqueueService.REQUIRED_GRANTS))).thenReturn(actor);
        doAnswer(call->((java.util.function.Supplier<?>)call.getArgument(1)).get()).when(scope).asScheduledActor(eq(actor),any());
    }
    @Test void successfulSourceUsesResolvedActorWithoutIndependentCompletion() {
        valid();when(sources.findDue(any())).thenReturn(List.of(source("a")));
        worker.tick();verify(enqueue).enqueue(eq(new ActionProductionSourceValidator.Reference("task","execution","conversation","a","alice")),any());
        verify(sources,never()).complete(any());verify(sources,never()).defer(any(),any());
    }
    @Test void failedSourceIsDeferredWithoutStarvingNextSource() {
        valid();when(sources.findDue(any())).thenReturn(List.of(source("a"),source("b")));
        when(enqueue.enqueue(argThat(r->r.resultId().equals("a")),any())).thenThrow(new IllegalStateException("unavailable"));
        worker.tick();verify(sources).defer(eq("a"),argThat(t->t.isAfter(LocalDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(4))));
        verify(enqueue).enqueue(argThat(r->r.resultId().equals("b")),any());
    }
    @Test void deniedOrganizationAccessCannotEnqueueOrMarkComplete() {
        valid();when(sources.findDue(any())).thenReturn(List.of(source("a")));
        when(actors.resolve(any(),any())).thenThrow(new IllegalStateException("revoked"));
        worker.tick();verifyNoInteractions(enqueue);verify(sources).defer(eq("a"),any());verify(sources,never()).complete(any());
    }
}
