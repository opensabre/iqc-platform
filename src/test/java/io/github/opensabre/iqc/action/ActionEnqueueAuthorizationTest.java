package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActionEnqueueAuthorizationTest {
    private final ActionProductionSourceValidator sources=mock(ActionProductionSourceValidator.class);
    private final ActionExecutionMapper rows=mock(ActionExecutionMapper.class);
    private final ActionDiscoveryMapper discovery=mock(ActionDiscoveryMapper.class);
    private final ActionAdapterRegistry adapters=mock(ActionAdapterRegistry.class);
    private final IqcDataScope scope=mock(IqcDataScope.class);
    private final ActionEnqueueService service=new ActionEnqueueService(sources,rows,discovery,adapters,scope,new ObjectMapper());
    private final ActionProductionSourceValidator.Reference reference=new ActionProductionSourceValidator.Reference("task","execution","conversation","result","alice");
    @BeforeEach void scope() {when(scope.owner()).thenReturn("alice");when(scope.groupId()).thenReturn("group");}
    private ScheduledActorAccessService.Resolved actor(String owner,boolean all,String group,Set<String> grants,Instant time) {
        return new ScheduledActorAccessService.Resolved("user",owner,all,group,Set.of(),grants,time);
    }
    private void rejected(ScheduledActorAccessService.Resolved actor) {
        assertThrows(RuntimeException.class,()->service.enqueue(reference,actor));
        verifyNoInteractions(sources,rows,adapters);
    }
    @Test void missingActorRejectedBeforeSourceAccess() {rejected(null);}
    @Test void expiredAuthorizationRejectedBeforeSourceAccess() {
        rejected(actor("alice",false,"group",ActionEnqueueService.REQUIRED_GRANTS,Instant.now().minusSeconds(60)));
    }
    @Test void futureAuthorizationRejectedBeforeSourceAccess() {
        rejected(actor("alice",false,"group",ActionEnqueueService.REQUIRED_GRANTS,Instant.now().plusSeconds(60)));
    }
    @Test void eachFixedGrantIsRequired() {
        for(var missing:ActionEnqueueService.REQUIRED_GRANTS) {
            var grants=new java.util.HashSet<>(ActionEnqueueService.REQUIRED_GRANTS);grants.remove(missing);
            rejected(actor("alice",false,"group",grants,Instant.now()));
        }
    }
    @Test void ownerMismatchRejectedBeforeSourceAccess() {
        rejected(actor("other",false,"group",ActionEnqueueService.REQUIRED_GRANTS,Instant.now()));
    }
    @Test void broadenedScopeRejectedBeforeSourceAccess() {
        rejected(actor("alice",true,"group",ActionEnqueueService.REQUIRED_GRANTS,Instant.now()));
    }
    @Test void groupMismatchRejectedBeforeSourceAccess() {
        rejected(actor("alice",false,"other",ActionEnqueueService.REQUIRED_GRANTS,Instant.now()));
    }
}
