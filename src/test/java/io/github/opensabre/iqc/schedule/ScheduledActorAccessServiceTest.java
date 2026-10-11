package io.github.opensabre.iqc.schedule;

import io.github.opensabre.common.core.entity.vo.Result;
import io.github.opensabre.common.core.util.UserContextHolder;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.shared.IqcOrganizationClient;
import io.github.opensabre.security.context.InternalTokenUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScheduledActorAccessServiceTest {
    private final IqcOrganizationClient organization = mock(IqcOrganizationClient.class);
    private final InternalTokenUserContext tokens = mock(InternalTokenUserContext.class);
    private final IqcDataScope scope=mock(IqcDataScope.class);
    private final ScheduledActorAccessService service = new ScheduledActorAccessService(organization, scope, tokens);
    @SuppressWarnings("unchecked")
    private final Result<IqcOrganizationClient.SchedulerAccess> response = mock(Result.class);
    private final Set<String> required = Set.of("iqc:pipeline:task:create", "iqc:task:execute", "iqc:schedule:publish");
    @BeforeEach
    void background() {
        clear(); when(organization.schedulerAccess("user")).thenReturn(response);
        projection("user", "owner", true, "group", Set.of("INSPECTOR"), required, Instant.now());
    }
    @AfterEach
    void clear() { UserContextHolder.getInstance().clear(); SecurityContextHolder.clearContext(); }
    private ScheduledActorAccessService.CreationBound bound(boolean all, String group) {
        return new ScheduledActorAccessService.CreationBound("iqc-schedule-owner-scope-v1", "user", "owner", all, group);
    }
    private void projection(String id, String owner, boolean valid, String group, Set<String> roles, Set<String> resources, Instant checked) {
        when(response.getData()).thenReturn(new IqcOrganizationClient.SchedulerAccess("iqc-scheduler-access-v1", id, owner, valid, group, roles, resources, checked));
    }
    @Test
    void currentAdministratorCannotExpandCreationScope() {
        projection("user", "owner", true, "group", Set.of("ROLE_ADMIN"), required, Instant.now());
        var resolved = service.resolve(bound(false, null));
        assertThat(resolved.all()).isFalse(); assertThat(resolved.groupId()).isNull();
    }
    @Test
    void revokedGlobalScopeNarrowsPreviouslyGlobalCreator() {
        assertThat(service.resolve(bound(true, "group")).all()).isFalse();
    }
    @Test
    void matchingCurrentAndCreationBoundsRetainGlobalAndGroup() {
        projection("user", "owner", true, "group", Set.of("ROLE_SUPER_ADMIN"), required, Instant.now());
        var resolved = service.resolve(bound(true, "group"));
        assertThat(resolved.all()).isTrue(); assertThat(resolved.groupId()).isEqualTo("group");
        assertThat(resolved.owner()).isEqualTo("owner"); assertThat(resolved.userId()).isEqualTo("user");
    }
    @Test
    void changedGroupNeverUsesTheNewGroupToWidenSavedBound() {
        projection("user", "owner", true, "new-group", Set.of("INSPECTOR"), required, Instant.now());
        assertThat(service.resolve(bound(false, "group")).groupId()).isNull();
    }
    @Test
    void missingAnyRequiredWorkflowGrantRejectsEvenAdministrator() {
        for (String missing : required) {
            var granted = new java.util.HashSet<>(required); granted.remove(missing);
            projection("user", "owner", true, "group", Set.of("ADMIN"), Set.copyOf(granted), Instant.now());
            assertThatThrownBy(() -> service.resolve(bound(true, "group"))).hasMessageContaining("已失去");
        }
    }
    @Test
    void staleFutureInvalidAccountAndMismatchedIdentityReject() {
        for (Instant checked : new Instant[]{Instant.now().minusSeconds(60), Instant.now().plusSeconds(60)}) {
            projection("user", "owner", true, "group", Set.of("ADMIN"), required, checked);
            assertThatThrownBy(() -> service.resolve(bound(true, "group"))).hasMessageContaining("当前授权证据");
        }
        projection("user", "owner", false, "group", Set.of("ADMIN"), required, Instant.now());
        assertThatThrownBy(() -> service.resolve(bound(true, "group"))).hasMessageContaining("当前授权证据");
        projection("other", "owner", true, "group", Set.of("ADMIN"), required, Instant.now());
        assertThatThrownBy(() -> service.resolve(bound(true, "group"))).hasMessageContaining("当前授权证据");
        projection("user", "renamed", true, "group", Set.of("ADMIN"), required, Instant.now());
        assertThatThrownBy(() -> service.resolve(bound(true, "group"))).hasMessageContaining("当前授权证据");
    }
    @Test
    void userContextOrAuthenticationCannotLeakIntoBackgroundResolution() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("owner", "none"));
        assertThatThrownBy(() -> service.resolve(bound(false, "group"))).hasMessageContaining("无用户上下文");
        SecurityContextHolder.clearContext();
        UserContextHolder.getInstance().setContext(Map.of(UserContextHolder.getInstance().KEY_USERNAME, "owner"));
        assertThatThrownBy(() -> service.resolve(bound(false, "group"))).hasMessageContaining("无用户上下文");
        verifyNoInteractions(organization);
    }
    @Test void currentResolutionKeepsAuthenticationAndRequiresOriginalWorkflowGrants() {
        var authentication=new TestingAuthenticationToken("owner","none");SecurityContextHolder.getContext().setAuthentication(authentication);
        when(scope.owner()).thenReturn("owner");when(scope.groupId()).thenReturn("group");
        var resolved=service.resolveCurrent(bound(false,"group"),required);
        assertThat(resolved.userId()).isEqualTo("user");assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(authentication);
        projection("user","owner",true,"group",Set.of("ADMIN"),Set.of(),Instant.now());
        assertThatThrownBy(()->service.resolveCurrent(bound(false,"group"),required)).hasMessageContaining("已失去");
    }
    @Test void mismatchedCurrentOwnerRejectsBeforeOrganizationLookup() {
        when(scope.owner()).thenReturn("other");
        assertThatThrownBy(()->service.resolveCurrent(bound(false,"group"),required)).hasMessageContaining("范围已变化");
        verifyNoInteractions(organization);
    }

}
