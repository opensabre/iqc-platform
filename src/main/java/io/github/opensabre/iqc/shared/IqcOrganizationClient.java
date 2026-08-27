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
