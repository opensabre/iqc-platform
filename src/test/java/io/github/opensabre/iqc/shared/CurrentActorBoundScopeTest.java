package io.github.opensabre.iqc.shared;

import io.github.opensabre.common.core.entity.vo.Result;
import io.github.opensabre.common.core.util.UserContextHolder;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService.CreationBound;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService.Resolved;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Verifies current authentication and the immutable creation bound intersect without leaking scope. */
class CurrentActorBoundScopeTest {
    private final UserContextHolder context = UserContextHolder.getInstance();
    private final IqcOrganizationClient organization = mock(IqcOrganizationClient.class);
    private final IqcDataScope scope = new IqcDataScope(organization);
    private static final String GRANT = "iqc:task:execute";

    @AfterEach
    void clear() { context.clear(); SecurityContextHolder.clearContext(); }

    @SuppressWarnings("unchecked")
    private Resolved current(boolean all, String group, Instant checked, Set<String> grants) {
        context.setContext(Map.of(context.KEY_USERNAME, "owner", context.KEY_USER_ID, "user",
                context.KEY_SCOPES, all ? "iqc:result:all" : ""));
        Result<IqcOrganizationClient.OrganizationUser> response = mock(Result.class);
        when(response.getData()).thenReturn(new IqcOrganizationClient.OrganizationUser("user", "Owner", "owner", group, "Group"));
        when(organization.getUserByUniqueId("owner")).thenReturn(response);
        return new Resolved("user", "owner", all, group, Set.of("INSPECTOR"), grants, checked);
    }

    private CreationBound bound(boolean all, String group) {
        return new CreationBound("iqc-schedule-owner-scope-v1", "user", "owner", all, group);
    }

    @ParameterizedTest
    @CsvSource({"false,false,g,g,false,g", "true,false,g,g,false,g", "false,true,g,g,false,g",
            "true,true,g,g,true,g", "true,false,new,old,false,NULL", "false,false,new,old,false,NULL",
            "true,false,g,NULL,false,NULL", "false,true,NULL,g,false,NULL"})
    void currentAndOriginalScopesIntersect(boolean currentAll, boolean originalAll, String currentGroup,
            String originalGroup, boolean expectedAll, String expectedGroup) {
        currentGroup = nullable(currentGroup); originalGroup = nullable(originalGroup); expectedGroup = nullable(expectedGroup);
        var actor = current(currentAll, currentGroup, Instant.now(), Set.of(GRANT));
        String expected = expectedGroup;
        scope.asCurrentActorBound(actor, bound(originalAll, originalGroup), () -> {
            assertThat(scope.actorUserId()).isEqualTo("user");
            assertThat(scope.owner()).isEqualTo("owner");
            assertThat(scope.canViewAll()).isEqualTo(expectedAll);
            assertThat(scope.groupId()).isEqualTo(expected);
            assertThat(scope.canView("owner", "foreign")).isTrue();
            assertThat(scope.canView("foreign", "foreign")).isEqualTo(expectedAll);
            scope.requireScheduledResources(Set.of(GRANT));
            return null;
        });
        assertThat(scope.canViewAll()).isEqualTo(currentAll);
        assertThat(scope.groupId()).isEqualTo(currentGroup);
        assertThat(scope.asCurrentActor(actor, () -> "clean")).isEqualTo("clean");
    }

    @Test
    void differentStableAccountOrOwnerAndUnknownProtocolCannotEnterCallback() {
        var actor = current(false, "g", Instant.now(), Set.of(GRANT));
        for (var saved : java.util.List.of(
                new CreationBound("iqc-schedule-owner-scope-v1", "replacement", "owner", false, "g"),
                new CreationBound("iqc-schedule-owner-scope-v1", "user", "other", false, "g"),
                new CreationBound("future", "user", "owner", false, "g"))) {
            assertThatThrownBy(() -> scope.asCurrentActorBound(actor, saved, () -> { throw new AssertionError("entered"); }))
                    .hasMessageContaining("原任务负责人");
        }
    }

    @Test
    void staleOrFutureCurrentProjectionCannotEnterCallback() {
        for (var checked : java.util.List.of(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60))) {
            var actor = current(false, "g", checked, Set.of(GRANT));
            assertThatThrownBy(() -> scope.asCurrentActorBound(actor, bound(false, "g"), () -> { throw new AssertionError("entered"); }))
                    .hasMessageContaining("授权范围无效");
        }
    }

    @Test
    void callbackFailureAndRevokedGrantDoNotLeakNarrowedScope() {
        var actor = current(true, "g", Instant.now(), Set.of());
        var originalContext = Map.copyOf(context.getContext());
        assertThatThrownBy(() -> scope.asCurrentActorBound(actor, bound(false, "g"), () -> {
            assertThat(scope.canViewAll()).isFalse();
            scope.requireScheduledResources(Set.of(GRANT));
            throw new AssertionError("revoked grant accepted");
        })).hasMessageContaining("缺少当前授权证据");
        assertThat(context.getContext()).containsExactlyInAnyOrderEntriesOf(originalContext);
        assertThat(scope.canViewAll()).isTrue();
        var failure = new IllegalStateException("callback failure");
        assertThatThrownBy(() -> scope.asCurrentActorBound(actor, bound(false, null), () -> { throw failure; })).isSameAs(failure);
        assertThat(scope.canViewAll()).isTrue();
        assertThat(scope.asCurrentActor(actor, () -> "clean")).isEqualTo("clean");
    }

    @Test
    void originalStrictEntryStillRejectsProjectionDifferentFromAuthenticatedScope() {
        current(true, "g", Instant.now(), Set.of(GRANT));
        var narrowed = new Resolved("user", "owner", false, "g", Set.of(), Set.of(GRANT), Instant.now());
        assertThatThrownBy(() -> scope.asCurrentActor(narrowed, () -> { throw new AssertionError("entered"); }))
                .hasMessageContaining("授权范围无效");
    }

    private static String nullable(String value) { return "NULL".equals(value) ? null : value; }
}
