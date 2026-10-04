package io.github.opensabre.iqc.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.iqc.scheme.InspectionSchemeService;
import io.github.opensabre.iqc.scheme.SchemePublicationService;
import io.github.opensabre.iqc.scheme.SchemeTaskService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Verifies API permission declarations and payload boundaries; gateway authorization needs deployment verification. */
class InspectionSchemeControllerTest {
    @Test
    void ordinaryUseAndExpertPublicationHaveSeparatePermissions() throws Exception {
        assertThat(permission("templates", String.class, Integer.class)).isEqualTo("iqc:scheme:view");
        assertThat(permission("drafts")).isEqualTo("iqc:scheme:manage");
        assertThat(permission("history", String.class, Integer.class)).isEqualTo("iqc:scheme:manage");
        assertThat(permission("task", String.class, int.class, InspectionSchemeController.TaskRequest.class)).isEqualTo("iqc:scheme:use");
        assertThat(permission("publish", String.class, InspectionSchemeController.PublishRequest.class)).isEqualTo("iqc:scheme:publish");
        assertThat(permission("trial", String.class, InspectionSchemeController.TrialRequest.class)).isEqualTo("iqc:scheme:trial:create");
        var codes = java.util.Arrays.stream(InspectionSchemeController.class.getDeclaredMethods())
                .map(m -> m.getAnnotation(ResourcePermission.class)).filter(java.util.Objects::nonNull)
                .map(ResourcePermission::code).toList();
        assertThat(codes).hasSize(10);
        assertThat(codes.stream().distinct().toList()).hasSize(9); // History reuses expert maintenance authorization.
    }

    @Test
    void historyPassesTheCursorWithoutPublishingOrCreatingTasks() {
        var schemes = mock(InspectionSchemeService.class);
        var gate = mock(SchemePublicationService.class);
        var tasks = mock(SchemeTaskService.class);
        var controller = new InspectionSchemeController(schemes, gate, tasks);
        controller.history("s1", 7);
        verify(schemes).history("s1", 7);
        verifyNoInteractions(gate, tasks);
    }

    @Test
    void publicationAlwaysUsesTrialGateAndAcknowledgement() {
        var schemes = mock(InspectionSchemeService.class);
        var gate = mock(SchemePublicationService.class);
        var controller = new InspectionSchemeController(schemes, gate, mock(SchemeTaskService.class));
        controller.publish("s1", new InspectionSchemeController.PublishRequest(2, "trial-1", true, null));
        verify(gate).publish("s1", 2, "trial-1", true);
        verifyNoInteractions(schemes);
    }

    @Test
    void availabilityReusesPublicationPermissionWithoutBypassingTheTrialGate() {
        var schemes = mock(InspectionSchemeService.class);
        var gate = mock(SchemePublicationService.class);
        var controller = new InspectionSchemeController(schemes, gate, mock(SchemeTaskService.class));
        controller.publish("s1", new InspectionSchemeController.PublishRequest(2, null, false, "DISABLE"));
        controller.publish("s1", new InspectionSchemeController.PublishRequest(3, null, false, "ENABLE"));
        verify(schemes).changeAvailability("s1", 2, false);
        verify(schemes).changeAvailability("s1", 3, true);
        assertThatThrownBy(() -> controller.publish("s1", new InspectionSchemeController.PublishRequest(2, "trial", false, "DISABLE")))
                .hasMessageContaining("不能混入");
        assertThatThrownBy(() -> controller.publish("s1", new InspectionSchemeController.PublishRequest(2, null, false, "UNKNOWN")))
                .hasMessageContaining("无效");
        assertThatThrownBy(() -> controller.publish("s1", new InspectionSchemeController.PublishRequest(2, null, true, null)))
                .hasMessageContaining("试跑");
        verifyNoInteractions(gate);
    }

    @Test
    void versionArchiveAndRestoreReusePublicationPermissionAndRequireOnlyAnExactVersion() throws Exception {
        var schemes = mock(InspectionSchemeService.class);
        var gate = mock(SchemePublicationService.class);
        var controller = new InspectionSchemeController(schemes, gate, mock(SchemeTaskService.class));
        controller.publish("s1", new InspectionSchemeController.PublishRequest(2, null, false, "ARCHIVE_VERSION", 1));
        controller.publish("s1", new InspectionSchemeController.PublishRequest(2, null, false, "RESTORE_VERSION", 1));
        verify(schemes).changeVersionArchive("s1", 2, 1, true);
        verify(schemes).changeVersionArchive("s1", 2, 1, false);
        assertThat(permission("publish", String.class, InspectionSchemeController.PublishRequest.class)).isEqualTo("iqc:scheme:publish");

        assertThatThrownBy(() -> controller.publish("s1", new InspectionSchemeController.PublishRequest(2, null, false, "ARCHIVE_VERSION")))
                .hasMessageContaining("必须只指定目标版本");
        assertThatThrownBy(() -> controller.publish("s1", new InspectionSchemeController.PublishRequest(2, "trial", false, "ARCHIVE_VERSION", 1)))
                .hasMessageContaining("必须只指定目标版本");
        assertThatThrownBy(() -> controller.publish("s1", new InspectionSchemeController.PublishRequest(2, null, true, "RESTORE_VERSION", 1)))
                .hasMessageContaining("必须只指定目标版本");
        verifyNoInteractions(gate);
    }

    @Test
    void requestValidationRejectsEmptyOversizedTrialsAndInvalidTaskKeys() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new InspectionSchemeController.TrialRequest(1, List.of()))).isNotEmpty();
            assertThat(validator.validate(new InspectionSchemeController.TrialRequest(1, java.util.Collections.nCopies(21, "c1")))).isNotEmpty();
            assertThat(validator.validate(new InspectionSchemeController.TrialRequest(1, List.of(" ")))).isNotEmpty();
            assertThat(validator.validate(new InspectionSchemeController.TrialRequest(1, List.of("c1"), "short"))).isNotEmpty();
            assertThat(validator.validate(new InspectionSchemeController.TrialRequest(1, List.of("c1"), "trial-request-1234567890"))).isEmpty();
            assertThat(validator.validate(new InspectionSchemeController.TrialRequest(1, List.of("c1"), null, " "))).isNotEmpty();
            assertThat(validator.validate(new InspectionSchemeController.TrialRequest(1, List.of("c1"), null, "standard"))).isEmpty();
            assertThat(validator.validate(new InspectionSchemeController.TrialRequest(1, List.of("c1"), null, null, 5,
                    new java.math.BigDecimal("0.80")))).isEmpty();
            assertThat(validator.validate(new InspectionSchemeController.TrialRequest(1, List.of("c1"), null, null, 6, null))).isNotEmpty();
            assertThat(validator.validate(new InspectionSchemeController.TrialRequest(1, List.of("c1"), null, null, 3,
                    new java.math.BigDecimal("1.01")))).isNotEmpty();
            assertThat(validator.validate(new InspectionSchemeController.TaskRequest("short", "任务", List.of("c1"), 1))).isNotEmpty();
            assertThat(validator.validate(new InspectionSchemeController.TaskRequest("request-1234567890", "任务", List.of("c1"), 33))).isNotEmpty();
            assertThat(validator.validate(new InspectionSchemeController.TaskRequest("request-1234567890", "任务", List.of("c1"), 1))).isEmpty();
            assertThat(validator.validate(new InspectionSchemeController.PublishRequest(1, null, false, "ARCHIVE_VERSION", 0))).isNotEmpty();
        }
    }

    @Test
    void publishedTemplateTaskRejectsUnknownOverrideFieldsWithTheirNames() throws Exception {
        var tasks = mock(SchemeTaskService.class);
        var controller = new InspectionSchemeController(mock(InspectionSchemeService.class),
                mock(SchemePublicationService.class), tasks);
        var request = new ObjectMapper().readValue("""
                {"requestId":"request-1234567890","name":"任务","conversationIds":["c1"],
                 "concurrency":1,"overrides":{"productName":"产品A"}}
                """, InspectionSchemeController.TaskRequest.class);

        assertThatThrownBy(() -> controller.task("s1", 1, request))
                .hasMessageContaining("模板任务不支持字段")
                .hasMessageContaining("overrides");
        verifyNoInteractions(tasks);
    }

    @Test
    void trialPassesOptionalRetryIdentityWithoutChangingTheExistingPermission() {
        var gate = mock(SchemePublicationService.class);
        var controller = new InspectionSchemeController(mock(InspectionSchemeService.class), gate, mock(SchemeTaskService.class));
        controller.trial("s1", new InspectionSchemeController.TrialRequest(2, List.of("c1"), "trial-request-1234567890"));
        verify(gate).trial("s1", 2, List.of("c1"), "trial-request-1234567890", null, null, null);
        controller.trial("s1", new InspectionSchemeController.TrialRequest(2, List.of("c1"), "trial-request-1234567890", "standard"));
        verify(gate).trial("s1", 2, List.of("c1"), "trial-request-1234567890", "standard", null, null);
        controller.trial("s1", new InspectionSchemeController.TrialRequest(2, List.of("c1"), "trial-request-1234567890",
                "standard", 3, new java.math.BigDecimal("0.80")));
        verify(gate).trial("s1", 2, List.of("c1"), "trial-request-1234567890", "standard", 3,
                new java.math.BigDecimal("0.80"));
    }

    private String permission(String method, Class<?>... types) throws Exception {
        return InspectionSchemeController.class.getMethod(method, types).getAnnotation(ResourcePermission.class).code();
    }

    @Test
    void templateTaskPassesOnlyTheAllowedVariantCodeForBatchAndSchedule() throws Exception {
        var tasks = mock(SchemeTaskService.class);
        var controller = new InspectionSchemeController(mock(InspectionSchemeService.class), mock(SchemePublicationService.class), tasks);
        var request = new ObjectMapper().readValue("""
                {"requestId":"request-1234567890","name":"任务","conversationIds":["c1"],"concurrency":1,"variantCode":"standard"}
                """, InspectionSchemeController.TaskRequest.class);
        assertThat(request.unsupportedFields()).isEmpty();
        controller.task("s1", 1, request);
        verify(tasks).create("s1", 1, "任务", List.of("c1"), 1, "request-1234567890", "standard");
        request.setTaskType("SCHEDULED"); request.setConversationIds(null); request.setScheduledTime("2030-01-01T10:00:00");
        controller.task("s1", 1, request);
        verify(tasks).createScheduled("s1", 1, "任务", null, java.time.LocalDateTime.parse("2030-01-01T10:00:00"), 1,
                "request-1234567890", "standard");
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            request.setVariantCode(" ");
            assertThat(factory.getValidator().validate(request)).isNotEmpty();
        }
    }
}
