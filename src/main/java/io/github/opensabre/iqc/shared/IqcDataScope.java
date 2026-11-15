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

    private final ThreadLocal<ExecutionScope> executionScope = new ThreadLocal<>();

    /** Server-created authorization bound for IQC schedules; never accepted from a public request. */
    public record ExecutionScope(boolean all, String owner, String groupId) {
        public ExecutionScope {
            if (owner == null || owner.isBlank() || "system".equals(owner))
                throw new IllegalArgumentException("调度授权缺少有效用户");
        }
    }

    /** Matches the existing scheduler's captured owner/group/all semantics without copying organization records. */
    public ExecutionScope captureExecutionScope() { return new ExecutionScope(canViewAll(), owner(), groupId()); }

    /** Re-resolves current grants for each scheduled round; failed lookups never reuse captured privilege. */
    public ExecutionScope revalidateExecutionScope(ExecutionScope captured) {
        if (organizationClient == null) throw deniedSchedule();
        var user = authorizationData(organizationClient.getAuthorizationUser(captured.owner()));
        if (user.id() == null || !captured.owner().equals(user.username()) || !Boolean.TRUE.equals(user.enabled())
                || !Boolean.TRUE.equals(user.accountNonExpired()) || !Boolean.TRUE.equals(user.credentialsNonExpired())
                || !Boolean.TRUE.equals(user.accountNonLocked()) || !"N".equals(user.deleted())) throw deniedSchedule();
        var roles = authorizationData(organizationClient.getUserRoles(user.id()));
        boolean execute = false, all = false;
        var matcher = new org.springframework.util.AntPathMatcher();
        for (var role : roles) {
            if (role.code() == null || role.code().isBlank()) throw deniedSchedule();
            String normalized = normalizeRole(role.code());
            all |= normalized.equals("ADMIN") || normalized.equals("SUPER_ADMIN");
            for (var resource : authorizationData(organizationClient.getRoleResources(role.code()))) {
                all |= "iqc:result:all".equals(resource.code());
                execute |= "POST".equalsIgnoreCase(resource.method()) && resource.url() != null
                        && matcher.match(resource.url(), "/api/iqc/tasks/scheduled-round/run");
            }
        }
        if (!execute) throw deniedSchedule();
        return new ExecutionScope(all, captured.owner(), user.groupId());
    }

    private static <T> T authorizationData(io.github.opensabre.common.core.entity.vo.Result<T> response) {
        if (response == null || !io.github.opensabre.common.core.entity.vo.Result.SUCCESSFUL_CODE.equals(response.getCode())
                || response.getData() == null) throw deniedSchedule();
        return response.getData();
    }

    private static io.github.opensabre.iqc.governance.IqcException deniedSchedule() {
        return io.github.opensabre.iqc.governance.IqcException.accessDenied("周期执行用户已失权或无法验证当前权限");
    }

    /** A short queue/freeze operation only; always restores the caller's scope, including on failure. */
    public <T> T withinExecutionScope(ExecutionScope scope, java.util.function.Supplier<T> operation) {
        java.util.Objects.requireNonNull(scope);
        var previous = executionScope.get(); executionScope.set(scope);
        try { return operation.get(); }
        finally { if (previous == null) executionScope.remove(); else executionScope.set(previous); }
    }

    public String owner() {
        if (executionScope.get() != null) return executionScope.get().owner();
        String username = userContext.getUsername();
        if (username != null && !username.isBlank()) return username;
        String userId = userContext.getUserId();
        return userId == null || userId.isBlank() ? "system" : userId;
    }

    public boolean canViewAll() {
        if (executionScope.get() != null) return executionScope.get().all();
        return effectiveRoles().stream().map(this::normalizeRole)
                        .anyMatch(role -> role.equals("ADMIN") || role.equals("SUPER_ADMIN"))
                || effectiveScopes().contains("iqc:result:all");
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
        if (executionScope.get() != null) return executionScope.get().groupId();
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
