package io.github.opensabre.iqc.shared;

import io.github.opensabre.common.core.util.UserContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.Locale;

/** IQC 只保存业务归属，不复制 OpenSabre 组织主数据。 */
@Component
@RequiredArgsConstructor
public class IqcDataScope {
    private final IqcOrganizationClient organizationClient;
    private final UserContextHolder userContext = UserContextHolder.getInstance();

    private final ThreadLocal<io.github.opensabre.iqc.schedule.ScheduledActorAccessService.Resolved> scheduledActor=new ThreadLocal<>();

    /** Domain-only synchronous scope boundary; caller must supply a fresh organization projection. */
    public <T> T asScheduledActor(io.github.opensabre.iqc.schedule.ScheduledActorAccessService.Resolved actor,java.util.function.Supplier<T> callback) {
        io.github.opensabre.iqc.schedule.ScheduledActorAccessService.requireBackground();
        java.time.Instant now=java.time.Instant.now();
        if(scheduledActor.get()!=null || actor==null || actor.owner()==null || actor.userId()==null || actor.checkedAt()==null
                || actor.checkedAt().isBefore(now.minusSeconds(30)) || actor.checkedAt().isAfter(now.plusSeconds(5)))
            throw new IllegalStateException("invalid or nested scheduled actor scope");
        var original=new java.util.HashMap<>(userContext.getContext());
        var context=new java.util.HashMap<String,String>();context.put(userContext.KEY_USERNAME,actor.owner());
        context.put(userContext.KEY_USER_ID,actor.userId());context.put(userContext.KEY_ROLES,String.join(",",actor.roles()));
        context.put(userContext.KEY_SCOPES,actor.all()?"iqc:result:all":"");
        scheduledActor.set(actor);userContext.setContext(context);
        try{return callback.get();}finally{scheduledActor.remove();if(original.isEmpty())userContext.clear();else userContext.setContext(original);}
    }
    /** Current-user synchronous scope; all organization comparisons occur before entering the callback transaction. */
    public <T> T asCurrentActor(io.github.opensabre.iqc.schedule.ScheduledActorAccessService.Resolved actor,java.util.function.Supplier<T> callback) {
        var now=java.time.Instant.now();
        if(scheduledActor.get()!=null || actor==null || actor.checkedAt()==null || actor.userId()==null
                || actor.roles()==null || actor.resourceCodes()==null || "system".equals(owner())
                || !java.util.Objects.equals(owner(),actor.owner()) || canViewAll()!=actor.all()
                || !java.util.Objects.equals(groupId(),actor.groupId())
                || actor.checkedAt().isBefore(now.minusSeconds(30)) || actor.checkedAt().isAfter(now.plusSeconds(5)))
            throw io.github.opensabre.iqc.governance.IqcException.accessDenied("当前负责人授权范围无效");
        scheduledActor.set(actor);
        try {return callback.get();} finally {scheduledActor.remove();}
    }
    /** Local side-effect preflight using the existing scheduled actor, without network calls under locks. */
    public void requireScheduledResources(Set<String> resources) {
        var actor=scheduledActor.get();var now=java.time.Instant.now();
        if(resources==null || resources.isEmpty() || actor==null || actor.checkedAt()==null
                || actor.checkedAt().isBefore(now.minusSeconds(30)) || actor.checkedAt().isAfter(now.plusSeconds(5))
                || actor.resourceCodes()==null || !actor.resourceCodes().containsAll(resources))
            throw io.github.opensabre.iqc.governance.IqcException.accessDenied("后台业务操作缺少当前授权证据");
    }
    /** Reuses the owner-bound checks against the existing private scheduled projection. */
    public void requireScheduledBound(io.github.opensabre.iqc.schedule.ScheduledActorAccessService.CreationBound bound) {
        var actor=scheduledActor.get();
        if(actor==null)throw io.github.opensabre.iqc.governance.IqcException.accessDenied("后台操作缺少当前负责人上下文");
        io.github.opensabre.iqc.action.ActionEnqueueService.requireBound(bound,actor);
    }
    /** Stable account identity for routing; never replace it with mutable display names. */
    public String actorUserId(){
        var actor=scheduledActor.get();String id=actor==null?userContext.getUserId():actor.userId();
        if(id==null||id.isBlank())throw io.github.opensabre.iqc.governance.IqcException.accessDenied("缺少稳定账户身份");
        return id;
    }
    public String owner() {
        var actor=scheduledActor.get();if(actor!=null)return actor.owner();
        String username = userContext.getUsername();
        if (username != null && !username.isBlank()) return username;
        String userId = userContext.getUserId();
        return userId == null || userId.isBlank() ? "system" : userId;
    }

    public boolean canViewAll() {
        var actor=scheduledActor.get();if(actor!=null)return actor.all();
        return effectiveRoles().stream().map(this::normalizeRole)
                        .anyMatch(role -> role.equals("ADMIN") || role.equals("SUPER_ADMIN"))
                || effectiveScopes().contains("iqc:result:all");
    }

    /** Force publication needs trusted administrator roles, never a broad read scope. */
    public boolean isIqcAdministrator() {
        return effectiveRoles().stream().map(this::normalizeRole)
                .anyMatch(role -> role.equals("IQC_ADMIN") || role.equals("SUPER_ADMIN"));
    }

    /** Uses the trusted internal context first, then the verified external JWT context. */
    private Set<String> effectiveRoles() {
        Set<String> roles = userContext.getRoles();
        if (!roles.isEmpty()) return roles;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) return Set.of();
        return authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.startsWith("ROLE_") ? value.substring(5) : value)
                .filter(value -> !value.startsWith("SCOPE_"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private Set<String> effectiveScopes() {
        Set<String> scopes = userContext.getScopes();
        if (!scopes.isEmpty()) return scopes;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) return Set.of();
        return authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .filter(value -> value != null && value.startsWith("SCOPE_"))
                .map(value -> value.substring(6))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private String normalizeRole(String role) {
        String normalized = role.startsWith("ROLE_") ? role.substring(5) : role;
        return normalized.toUpperCase(Locale.ROOT);
    }

    public String groupId() {
        var actor=scheduledActor.get();if(actor!=null)return actor.groupId();
        if (organizationClient == null) return null;
        String uniqueId = owner();
        try {
            IqcOrganizationClient.OrganizationUser user = organizationClient.getUserByUniqueId(uniqueId).getData();
            return user == null ? null : user.groupId();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public boolean canView(String owner, String recordGroupId) {
        if (canViewAll()) return true;
        if (owner != null && owner.equals(owner())) return true;
        String currentGroupId = groupId();
        return currentGroupId != null && currentGroupId.equals(recordGroupId);
    }
}
