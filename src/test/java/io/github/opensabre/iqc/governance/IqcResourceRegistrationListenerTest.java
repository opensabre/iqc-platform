package io.github.opensabre.iqc.governance;

import io.github.opensabre.boot.entity.RestMappingInfo;
import io.github.opensabre.boot.rest.MappingInfoHandler;
import io.github.opensabre.common.core.entity.vo.Result;
import io.github.opensabre.governance.registration.GovernanceRegistrationCoordinator;
import io.github.opensabre.iqc.shared.IqcOrganizationClient;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IqcResourceRegistrationListenerTest {
    @Test
    void submitsCompletePermissionSnapshotThroughGovernanceCoordinator() {
        MappingInfoHandler mappings = mock(MappingInfoHandler.class);
        IqcOrganizationClient client = mock(IqcOrganizationClient.class);
        GovernanceRegistrationCoordinator coordinator = mock(GovernanceRegistrationCoordinator.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "iqc-platform")
                .withProperty("opensabre.governance.registration-token", "registration-secret");
        when(mappings.getMappingInfo()).thenReturn(Set.of(RestMappingInfo.builder()
                .url("/api/iqc/config/agents").method("GET").code("iqc:agent:view").declaredPermission(true).build()));
        when(client.registerResources(eq("iqc-platform"), eq(true), eq("registration-secret"), any()))
                .thenReturn(Result.success(new IqcOrganizationClient.ResourceRegistrationResult(1, 0, 0, 1)));
        var action = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        when(coordinator.submit(eq("resource-permissions"), action.capture())).thenReturn(true);

        new IqcResourceRegistrationListener(mappings, client, environment, coordinator).register();
        action.getValue().run();

        var snapshot = org.mockito.ArgumentCaptor.forClass(io.github.opensabre.boot.entity.ResourceMappingSnapshot.class);
        verify(client).registerResources(eq("iqc-platform"), eq(true), eq("registration-secret"), snapshot.capture());
        assertThat(snapshot.getValue().getResources()).extracting(RestMappingInfo::getCode).containsExactlyInAnyOrder("iqc:agent:view", "iqc:action:enqueue");
        assertThat(snapshot.getValue().getResources()).filteredOn(value -> "iqc:action:enqueue".equals(value.getCode()))
                .singleElement().satisfies(value -> {
                    assertThat(value.getMethod()).isEqualTo("BACKGROUND");
                    assertThat(value.isDeclaredPermission()).isTrue();
                    assertThat(value.getHandlerMethod()).isEqualTo("enqueue");
                });
    }
    @Test
    void existingHttpCapabilityIsPreservedWithoutDuplicateBackgroundDeclaration() {
        var mappings = mock(MappingInfoHandler.class);
        var client = mock(IqcOrganizationClient.class);
        var coordinator = mock(GovernanceRegistrationCoordinator.class);
        var http = RestMappingInfo.builder()
                .url("/api/iqc/conversation-results/{id}/actions/enqueue").method("POST")
                .code("iqc:action:enqueue").declaredPermission(true).build();
        when(mappings.getMappingInfo()).thenReturn(Set.of(http));
        when(client.registerResources(eq("iqc-platform"), eq(true), eq("local-token"), any()))
                .thenReturn(Result.success(new IqcOrganizationClient.ResourceRegistrationResult(1, 0, 0, 1)));
        var callback = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        when(coordinator.submit(eq("resource-permissions"), callback.capture())).thenReturn(true);
        new IqcResourceRegistrationListener(mappings, client,
                new MockEnvironment().withProperty("opensabre.governance.registration-token", "local-token"),
                coordinator).register();
        callback.getValue().run();

        var snapshot = org.mockito.ArgumentCaptor.forClass(io.github.opensabre.boot.entity.ResourceMappingSnapshot.class);
        verify(client).registerResources(eq("iqc-platform"), eq(true), eq("local-token"), snapshot.capture());
        assertThat(snapshot.getValue().getResources()).containsExactly(http);
        assertThat(snapshot.getValue().getResources()).singleElement().isSameAs(http);
    }
    @Test
    void missingRegistrationTokenCannotPublishAnyCapability() {
        var mappings=mock(MappingInfoHandler.class);var client=mock(IqcOrganizationClient.class);
        var coordinator=mock(GovernanceRegistrationCoordinator.class);var callback=org.mockito.ArgumentCaptor.forClass(Runnable.class);
        when(coordinator.submit(eq("resource-permissions"),callback.capture())).thenReturn(true);
        new IqcResourceRegistrationListener(mappings,client,new MockEnvironment(),coordinator).register();
        org.assertj.core.api.Assertions.assertThatThrownBy(()->callback.getValue().run()).isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(client,mappings);
    }

}
