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
