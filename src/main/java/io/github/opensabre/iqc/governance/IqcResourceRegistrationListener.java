package io.github.opensabre.iqc.governance;

import io.github.opensabre.boot.entity.ResourceMappingSnapshot;
import io.github.opensabre.boot.metadata.OpensabreVersion;
import io.github.opensabre.boot.rest.MappingInfoHandler;
import io.github.opensabre.common.core.entity.vo.Result;
import io.github.opensabre.governance.registration.GovernanceRegistrationCoordinator;
import io.github.opensabre.iqc.shared.IqcOrganizationClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Registers IQC's complete permission-resource snapshot in OpenSabre Organization after startup.
 */
@Component
@ConditionalOnProperty(prefix = "opensabre.resource-registration", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class IqcResourceRegistrationListener {
    private final MappingInfoHandler mappingInfoHandler;
    private final IqcOrganizationClient organizationClient;
    private final Environment environment;
    private final GovernanceRegistrationCoordinator registrationCoordinator;

    /** Schedules bounded, observable registration without blocking application startup. */
    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        registrationCoordinator.submit("resource-permissions", this::registerOnce);
    }

    private void registerOnce() {
        String application = environment.getProperty("spring.application.name", "iqc-platform");
        String token = environment.getProperty("opensabre.governance.registration-token", "");
        if (token.isBlank()) throw new IllegalStateException("IQC resource registration token is missing");
        var resources = new java.util.HashSet<>(mappingInfoHandler.getMappingInfo());
        // Reuse a declared HTTP capability when present; older releases need a background declaration.
        // Both use the same existing authorization code and never grant roles automatically.
        if (resources.stream().noneMatch(value -> "iqc:action:enqueue".equals(value.getCode()))) {
            resources.add(io.github.opensabre.boot.entity.RestMappingInfo.builder()
                .url("/internal/iqc/actions/enqueue").method("BACKGROUND")
                .code("iqc:action:enqueue").name("生产动作入队").type("iqc")
                .description("后台按负责人当前授权将已提交生产结果匹配动作入账，不授予发送权限或自动赋权")
                .handlerClass(io.github.opensabre.iqc.action.ActionEnqueueService.class.getName())
                .handlerMethod("enqueue").declaredPermission(true).build());
        }
        ResourceMappingSnapshot snapshot = ResourceMappingSnapshot.builder()
                .application(application)
                .version(OpensabreVersion.getVersion())
                .resources(resources)
                .build();
        Result<IqcOrganizationClient.ResourceRegistrationResult> response = organizationClient.registerResources(
                application, true, token, snapshot);
        IqcOrganizationClient.ResourceRegistrationResult result = response.getData();
        if (result == null) throw new IllegalStateException("IQC resource registration returned no data");
        log.info("IQC resources registered: application={} created={} updated={} stale={} total={}",
                application, result.created(), result.updated(), result.stale(), result.total());
    }
}
