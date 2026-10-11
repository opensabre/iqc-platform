package io.github.opensabre.iqc.action;

import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import java.time.Instant;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Request boundary only; does not substitute for real HTTP authorization or transaction tests. */
class ActionRecoveryRequestServiceTest {
    private final ScheduledActorAccessService actors=mock(ScheduledActorAccessService.class);
    private final IqcDataScope scope=mock(IqcDataScope.class);
    private final ActionRecoveryService states=mock(ActionRecoveryService.class);
    private final ActionRecoveryRequestService service=new ActionRecoveryRequestService(actors,scope,states);
    private final String id="a".repeat(64);
    private void enabled(){ReflectionTestUtils.setField(service,"enabled",true);}
    @Test void disabledProcessingNeverResolvesOrWrites() {
        assertThrows(IqcException.class,()->service.recover("result",id,0,"原因"));
        verifyNoInteractions(actors,scope,states);
    }
    @Test void invalidVersionOrReasonNeverContactsOrganizationOrState() {
        enabled();
        for(long version:new long[]{-1,Long.MAX_VALUE-2,Long.MAX_VALUE-1,Long.MAX_VALUE})
            assertThrows(IqcException.class,()->service.recover("result",id,version,"原因"));
        for(String reason:new String[]{null," ","x".repeat(1001)})
            assertThrows(IqcException.class,()->service.recover("result",id,0,reason));
        verifyNoInteractions(actors,scope,states);
    }
    @Test void currentOrganizationDenialCannotReachTheStateTransaction() {
        enabled();when(actors.resolveCurrent(any(),anySet())).thenThrow(IqcException.accessDenied("拒绝"));
        assertThrows(IqcException.class,()->service.recover("result",id,0,"原因"));
        verifyNoInteractions(scope,states);
    }
    @Test void fixedServerGrantsAndResolvedScopeGuardTheOriginalObservation() {
        enabled();var grants=new java.util.HashSet<>(ActionEnqueueService.REQUIRED_GRANTS);grants.add(ActionRecoveryService.RESOURCE);
        var bound=new ScheduledActorAccessService.CreationBound("iqc-schedule-owner-scope-v1","user","alice",false,"group");
        var actor=new ScheduledActorAccessService.Resolved("user","alice",false,"group",Set.of(),grants,Instant.now());
        when(actors.capture()).thenReturn(bound);when(actors.resolveCurrent(bound,Set.copyOf(grants))).thenReturn(actor);
        when(scope.asCurrentActor(eq(actor),any())).thenAnswer(call->((Supplier<?>)call.getArgument(1)).get());
        when(states.recover("result",id,0,actor,"原因")).thenReturn(true);
        assertEquals("RECOVERED",service.recover("result",id,0,"原因").state());
        verify(actors).resolveCurrent(bound,Set.copyOf(grants));verify(states).recover("result",id,0,actor,"原因");
        when(states.recover("result",id,1,actor,"原因")).thenReturn(false);
        assertEquals("NOT_RECOVERED",service.recover("result",id,1,"原因").state());
    }
}
