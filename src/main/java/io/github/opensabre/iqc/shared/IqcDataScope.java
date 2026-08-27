package io.github.opensabre.iqc.shared;

import io.github.opensabre.common.core.util.UserContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
        return userContext.getRoles().stream().anyMatch(role -> role.equalsIgnoreCase("ADMIN") || role.equalsIgnoreCase("SUPER_ADMIN"))
                || userContext.getScopes().contains("iqc:result:all");
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
