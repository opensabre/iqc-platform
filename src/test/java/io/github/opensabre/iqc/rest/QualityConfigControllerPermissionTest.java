package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QualityConfigControllerPermissionTest {

    @Test
    void dlsImportUsesDedicatedPermissionResource() throws Exception {
        ResourcePermission importPermission = QualityConfigController.class
                .getDeclaredMethod("importDls", org.springframework.web.multipart.MultipartFile.class,
                        boolean.class, boolean.class)
                .getAnnotation(ResourcePermission.class);
        ResourcePermission createPermission = QualityConfigController.class
                .getDeclaredMethod("createRule", QualityConfigController.RuleRequest.class)
                .getAnnotation(ResourcePermission.class);

        assertThat(importPermission.code()).isEqualTo("iqc:rule:import");
        assertThat(importPermission.code()).isNotEqualTo(createPermission.code());
    }
}
