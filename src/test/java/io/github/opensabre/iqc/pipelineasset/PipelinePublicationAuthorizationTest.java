package io.github.opensabre.iqc.pipelineasset;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.pipelineasset.dao.PipelineAssetMapper;
import io.github.opensabre.iqc.shared.IqcDataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Service authorization executes before asset lookup, independently of UI/API resource permissions. */
class PipelinePublicationAuthorizationTest {
    private final IqcDataScope scope = mock(IqcDataScope.class);
    private final PipelineAssetMapper assets = mock(PipelineAssetMapper.class);
    private final PipelineAssetService service = new PipelineAssetService(assets, null, null, null, null, null,
            null, null, new ObjectMapper(), scope, null, null, null, null);

    @BeforeEach
    void authenticatedOperator() { when(scope.owner()).thenReturn("inspector"); }

    @Test
    void systemCannotPublishEvenWithAdministratorFlag() {
        when(scope.owner()).thenReturn("system");
        when(scope.isIqcAdministrator()).thenReturn(true);
        assertThatThrownBy(() -> publish(true, "emergency")).hasMessageContaining("已认证操作者");
        verifyNoInteractions(assets);
    }
    @Test
    void nonAdministratorCannotForcePublication() {
        assertThatThrownBy(() -> publish(true, "emergency")).hasMessageContaining("可信管理员");
        verifyNoInteractions(assets);
    }
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void administratorMustSupplyNonblankReason(String reason) {
        when(scope.isIqcAdministrator()).thenReturn(true);
        assertThatThrownBy(() -> publish(true, reason)).hasMessageContaining("1–1000字原因");
        verifyNoInteractions(assets);
    }
    @Test
    void rejectsOversizedAdministratorReason() {
        when(scope.isIqcAdministrator()).thenReturn(true);
        assertThatThrownBy(() -> publish(true, "x".repeat(1001))).hasMessageContaining("1–1000字原因");
        verifyNoInteractions(assets);
    }
    @Test
    void ordinaryPublicationCannotAttachAnOverrideReason() {
        assertThatThrownBy(() -> publish(false, "override")).hasMessageContaining("普通发布不能");
        verifyNoInteractions(assets);
    }
    private void publish(boolean force, String reason) {
        service.publishMeasured("pipeline", 1, "trial", true, null, null, force, reason);
    }
}
