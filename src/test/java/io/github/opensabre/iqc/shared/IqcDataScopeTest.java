package io.github.opensabre.iqc.shared;

import io.github.opensabre.common.core.util.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IqcDataScopeTest {
    private final UserContextHolder context = UserContextHolder.getInstance();
    private final IqcDataScope scope = new IqcDataScope(null);

    @AfterEach
    void clearContext() {
        context.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void scheduledAuthorizationRefreshesScopeAndFailsClosedAfterRevocationOrLookupFailure() {
        var organization = org.mockito.Mockito.mock(IqcOrganizationClient.class);
        var scheduled = new IqcDataScope(organization);
        var captured = new IqcDataScope.ExecutionScope(true, "alice", "old-team");
        org.mockito.Mockito.when(organization.getAuthorizationUser("alice")).thenReturn(
                io.github.opensabre.common.core.entity.vo.Result.success(new IqcOrganizationClient.AuthorizationUser(
                        "u1", "alice", "new-team", true, true, true, true, "N")));
        org.mockito.Mockito.when(organization.getUserRoles("u1")).thenReturn(
                io.github.opensabre.common.core.entity.vo.Result.success(java.util.List.of(new IqcOrganizationClient.AuthorizationRole("USER"))));
        org.mockito.Mockito.when(organization.getRoleResources("USER")).thenReturn(
                io.github.opensabre.common.core.entity.vo.Result.success(java.util.List.of(new IqcOrganizationClient.AuthorizationResource(
                        "iqc:task:execute", "/api/iqc/tasks/{id}/run", "POST"))));
        assertThat(scheduled.revalidateExecutionScope(captured)).isEqualTo(new IqcDataScope.ExecutionScope(false, "alice", "new-team"));
        org.mockito.Mockito.when(organization.getRoleResources("USER")).thenReturn(
                io.github.opensabre.common.core.entity.vo.Result.success(java.util.List.of()));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scheduled.revalidateExecutionScope(captured)).hasMessageContaining("失权");
        org.mockito.Mockito.when(organization.getAuthorizationUser("alice")).thenReturn(
                io.github.opensabre.common.core.entity.vo.Result.success(new IqcOrganizationClient.AuthorizationUser(
                        "u1", "alice", "new-team", false, true, true, true, "N")));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scheduled.revalidateExecutionScope(captured)).hasMessageContaining("失权");
        org.mockito.Mockito.when(organization.getAuthorizationUser("alice")).thenReturn(null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scheduled.revalidateExecutionScope(captured)).hasMessageContaining("失权");
    }

    @Test
    void scheduledScopeIsThreadLocalAndRestoredAfterNestedFailure() throws Exception {
        context.setContext(Map.of(context.KEY_USERNAME, "alice"));
        var captured = new IqcDataScope.ExecutionScope(false, "schedule-owner", "team");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scope.withinExecutionScope(captured, () -> {
            assertThat(scope.owner()).isEqualTo("schedule-owner"); assertThat(scope.groupId()).isEqualTo("team");
            assertThat(scope.canView("another", "team")).isTrue(); assertThat(scope.canView("another", "other")).isFalse();
            assertThat(java.util.concurrent.CompletableFuture.supplyAsync(scope::owner).join()).isEqualTo("system");
            return scope.withinExecutionScope(new IqcDataScope.ExecutionScope(true, "nested", null), () -> {
                assertThat(scope.canViewAll()).isTrue(); throw new IllegalStateException("failed");
            });
        })).hasMessageContaining("failed");
        assertThat(scope.owner()).isEqualTo("alice"); assertThat(scope.canViewAll()).isFalse();
    }

    @Test
    void ordinaryUserIsRestrictedToOwnRecords() {
        context.setContext(Map.of(context.KEY_USERNAME, "alice", context.KEY_USER_ID, "u-1"));
        assertThat(scope.owner()).isEqualTo("alice");
        assertThat(scope.canViewAll()).isFalse();
    }

    @Test
    void administratorCanViewAllRecords() {
        context.setContext(Map.of(context.KEY_USERNAME, "admin", context.KEY_ROLES, "ADMIN"));
        assertThat(scope.canViewAll()).isTrue();
    }

    @Test
    void administratorRoleFromVerifiedSpringSecurityContextCanViewAllRecords() {
        context.setContext(Map.of(context.KEY_USERNAME, "admin"));
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("admin", "n/a", "ADMIN"));
        assertThat(scope.canViewAll()).isTrue();
    }

    @Test
    void administratorRoleFromVerifiedContextIsCaseInsensitive() {
        context.setContext(Map.of(context.KEY_USERNAME, "admin"));
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("admin", "n/a", "admin"));
        assertThat(scope.canViewAll()).isTrue();
    }
}
