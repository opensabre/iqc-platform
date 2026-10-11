package io.github.opensabre.iqc.shared;

import io.github.opensabre.common.core.util.UserContextHolder;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.Instant;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class ScheduledActorScopeTest {
    private final UserContextHolder context = UserContextHolder.getInstance();
    private final IqcDataScope scope = new IqcDataScope(null);
    @BeforeEach @AfterEach
    void clear() { context.clear(); SecurityContextHolder.clearContext(); }
    private ScheduledActorAccessService.Resolved actor(String owner, boolean all, String group, Instant checked) {
        return new ScheduledActorAccessService.Resolved("user-" + owner, owner, all, group, Set.of("ADMIN"), Set.of(), checked);
    }
    private void cleared() {
        assertThat(context.getContext()).isEmpty(); assertThat(scope.owner()).isEqualTo("system");
        assertThat(scope.canViewAll()).isFalse(); assertThat(scope.groupId()).isNull();
    }
    @Test
    void narrowedAdministratorUsesOnlyResolvedOwnerAndGroupThenCleansUp() {
        var result = scope.asScheduledActor(actor("owner", false, "group", Instant.now()), () -> {
            assertThat(context.getUsername()).isEqualTo("owner"); assertThat(context.getUserId()).isEqualTo("user-owner");
            assertThat(scope.owner()).isEqualTo("owner"); assertThat(scope.canViewAll()).isFalse();
            assertThat(scope.canView("owner", "other")).isTrue(); assertThat(scope.canView("other", "group")).isTrue();
            assertThat(scope.canView("other", "elsewhere")).isFalse(); return "completed";
        });
        assertThat(result).isEqualTo("completed"); cleared();
    }
    @Test
    void exceptionalCallbackCannotLeakIdentityToNextOwnerOnSameThread() {
        var failure = new IllegalStateException("worker failure");
        assertThatThrownBy(() -> scope.asScheduledActor(actor("first", true, "group", Instant.now()), () -> { throw failure; })).isSameAs(failure);
        cleared();
        scope.asScheduledActor(actor("second", false, null, Instant.now()), () -> {
            assertThat(scope.owner()).isEqualTo("second"); assertThat(scope.canViewAll()).isFalse();
            assertThat(scope.canView("first", "group")).isFalse(); return null;
        });
        cleared();
    }
    @Test
    void nestedScopeIsRejectedWithoutReplacingOuterIdentity() {
        scope.asScheduledActor(actor("outer", false, null, Instant.now()), () -> {
            assertThatThrownBy(() -> scope.asScheduledActor(actor("inner", true, "group", Instant.now()), () -> null))
                    .hasMessageContaining("无用户上下文");
            assertThat(scope.owner()).isEqualTo("outer"); assertThat(scope.canViewAll()).isFalse(); return null;
        });
        cleared();
    }
    @Test
    void callbackRechecksEvidenceAfterQueueOrLockDelay() {
        for (Instant checked : new Instant[]{Instant.now().minusSeconds(60), Instant.now().plusSeconds(60)})
            assertThatThrownBy(() -> scope.asScheduledActor(actor("owner", true, "group", checked), () -> { throw new AssertionError("must not run"); }))
                    .hasMessageContaining("invalid or nested");
        cleared();
    }
    @Test void currentScopePreservesAuthenticatedContextAndCleansUpAfterFailure() {
        var original=java.util.Map.of(context.KEY_USERNAME,"owner",context.KEY_USER_ID,"user-owner");context.setContext(original);
        var authentication=new org.springframework.security.authentication.TestingAuthenticationToken("owner","none","ROLE_INSPECTOR");
        SecurityContextHolder.getContext().setAuthentication(authentication);
        var failure=new IllegalStateException("callback failed");
        assertThatThrownBy(()->scope.asCurrentActor(actor("owner",false,null,Instant.now()),()->{
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(authentication);
            assertThat(context.getContext()).containsAllEntriesOf(original);
            assertThatThrownBy(()->scope.asCurrentActor(actor("owner",false,null,Instant.now()),()->null)).hasMessageContaining("范围无效");
            throw failure;
        })).isSameAs(failure);
        assertThat(context.getContext()).containsAllEntriesOf(original);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(authentication);
        assertThat(scope.asCurrentActor(actor("owner",false,null,Instant.now()),()->"clean")).isEqualTo("clean");
    }

}
