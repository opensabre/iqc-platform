package io.github.opensabre.iqc.shared;

import io.github.opensabre.common.core.entity.vo.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import io.github.opensabre.boot.entity.ResourceMappingSnapshot;

/** 复用 OpenSabre 组织服务，只读取当前用户的组织归属。 */
@FeignClient(name = "${iqc.organization.service-id:base-organization}")
public interface IqcOrganizationClient {
    @GetMapping("/user")
    Result<OrganizationUser> getUserByUniqueId(@RequestParam("uniqueId") String uniqueId);

    /** Existing generic identity, role and resource endpoints supply live scheduler authorization. */
    @GetMapping("/user")
    Result<AuthorizationUser> getAuthorizationUser(@RequestParam("uniqueId") String uniqueId);

    @GetMapping("/role/user/{userId}")
    Result<java.util.List<AuthorizationRole>> getUserRoles(@PathVariable("userId") String userId);

    @GetMapping("/resource/role/{roleCode}")
    Result<java.util.List<AuthorizationResource>> getRoleResources(@PathVariable("roleCode") String roleCode);

    record AuthorizationUser(String id, String username, String groupId, Boolean enabled,
                             Boolean accountNonExpired, Boolean credentialsNonExpired, Boolean accountNonLocked, String deleted) { }
    record AuthorizationRole(String code) { }
    record AuthorizationResource(String code, String url, String method) { }

    /** Registers the complete IQC HTTP permission snapshot in OpenSabre Organization. */
    @PutMapping("/internal/resource-registrations/{application}")
    Result<ResourceRegistrationResult> registerResources(@PathVariable("application") String application,
            @RequestParam("markMissing") boolean markMissing,
            @RequestHeader("X-Opensabre-Resource-Registration-Token") String token,
            @RequestBody ResourceMappingSnapshot snapshot);

    record OrganizationUser(String id, String name, String username, String groupId, String groupName) {
    }

    record ResourceRegistrationResult(int created, int updated, int stale, int total) { }
}
