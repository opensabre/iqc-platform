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

    public String owner() {
        String username = userContext.getUsername();
        if (username != null && !username.isBlank()) return username;
        String userId = userContext.getUserId();
        return userId == null || userId.isBlank() ? "system" : userId;
    }

    public boolean canViewAll() {
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
