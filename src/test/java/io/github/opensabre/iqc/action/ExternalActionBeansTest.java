package io.github.opensabre.iqc.action;

import io.github.opensabre.iqc.shared.IqcDataScope;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Spring property binding and exact adapter registration, without any network invocation. */
class ExternalActionBeansTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ExternalActionBeans.class)
            .withBean(ExternalActionPreparationService.class,()->mock(ExternalActionPreparationService.class))
            .withBean(IqcDataScope.class,()->mock(IqcDataScope.class));
    private String[] profile() {
        return new String[]{"iqc.actions.external.enabled=true",
                "iqc.actions.external.destinations[0].id=receiver",
                "iqc.actions.external.destinations[0].version=1",
                "iqc.actions.external.destinations[0].type=WEBHOOK",
                "iqc.actions.external.destinations[0].send-uri=https://receiver.example/events",
                "iqc.actions.external.destinations[0].receipts-uri=https://receiver.example/receipts/",
                "iqc.actions.external.destinations[0].connect-timeout-ms=100",
                "iqc.actions.external.destinations[0].request-timeout-ms=1000",
                "iqc.actions.external.destinations[0].maximum-response-bytes=4096"};
    }
    @Test void disabledByDefaultRegistersNoExternalAdaptersOrClient() {
        runner.run(context->{
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(ExternalActionAdapter.class).isEmpty());
            assertTrue(context.getBeansOfType(HttpExternalActionTransport.class).isEmpty());
        });
    }
    @Test void bindsImmutableDestinationAndRegistersAllThreeExactTypes() {
        runner.withPropertyValues(profile()).run(context->{
            assertNull(context.getStartupFailure());
            var adapters=context.getBeansOfType(ExternalActionAdapter.class);
            assertEquals(3,adapters.size());
            assertEquals(java.util.Set.of(ActionExecutionContract.Type.WEBHOOK,
                    ActionExecutionContract.Type.CREATE_WORK_ORDER,ActionExecutionContract.Type.CREATE_COACHING_TASK),
                    adapters.values().stream().map(adapter->adapter.definition().type()).collect(java.util.stream.Collectors.toSet()));
            var config=new ExternalActionConfiguration("receiver",1,new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());
            assertEquals("receiver",context.getBean(ExternalActionDestinations.class).require(config,ActionExecutionContract.Type.WEBHOOK).id());
            assertThrows(IllegalArgumentException.class,()->context.getBean("workOrderActionAdapter",ExternalActionAdapter.class)
                    .validateConfiguration(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(config)));
        });
    }
    @Test void invalidTransportProfileFailsBindingBeforeAnyIo() {
        runner.withPropertyValues(profile()).withPropertyValues("iqc.actions.external.destinations[0].send-uri=http://receiver.example/events")
                .run(context->assertNotNull(context.getStartupFailure()));
    }
}
