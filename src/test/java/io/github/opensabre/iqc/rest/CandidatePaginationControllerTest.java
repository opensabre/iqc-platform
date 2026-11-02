package io.github.opensabre.iqc.rest;

import io.github.opensabre.iqc.quality.QualityOperationsService;
import io.github.opensabre.iqc.privacy.SensitiveReadAccessService;
import io.github.opensabre.iqc.privacy.ResultReadProjection;
import io.github.opensabre.iqc.shared.IqcPage;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Controller routing and authorization order; service persistence is verified separately. */
class CandidatePaginationControllerTest {
    private final QualityOperationsService service=mock(QualityOperationsService.class);
    private final SensitiveReadAccessService access=mock(SensitiveReadAccessService.class);
    private final ResultReadProjection projection=mock(ResultReadProjection.class);
    private final QualityOperationsController controller=new QualityOperationsController(service,null,null,null,null,access,projection);

    @Test void pagedRequestRetainsFilteredMetadataAndProjectsRecords() {
        var records=List.<io.github.opensabre.iqc.quality.model.QualitySample>of();
        when(service.samples("UNCLASSIFIED","CANDIDATE",2,20,"needle")).thenReturn(new IqcPage<>(records,2,20,41));
        when(projection.samples(eq(records),any())).thenReturn(records);
        assertThat(controller.samples("UNCLASSIFIED","CANDIDATE",2L,20L,"needle"))
                .isEqualTo(new IqcPage<>(records,2,20,41));
        var order=inOrder(access,service,projection);
        order.verify(access).current(Set.of("iqc:sample:view"));
        order.verify(service).samples("UNCLASSIFIED","CANDIDATE",2,20,"needle");
        order.verify(projection).samples(eq(records),any());
    }
    @Test void unpagedRequestKeepsLegacyListRouting() {
        when(service.samples(null,null)).thenReturn(List.of());
        when(projection.samples(anyList(),any())).thenReturn(List.of());
        assertThat(controller.samples(null,null,null,null,null)).isEqualTo(List.of());
        verify(service).samples(null,null);verifyNoMoreInteractions(service);
    }
    @Test void missingPagePartnerOrKeywordWithoutPagingFailsBeforeService() {
        assertThatThrownBy(()->controller.samples(null,null,1L,null,null)).hasMessageContaining("current和size");
        assertThatThrownBy(()->controller.samples(null,null,null,20L,null)).hasMessageContaining("current和size");
        assertThatThrownBy(()->controller.samples(null,null,null,null,"needle")).hasMessageContaining("current和size");
        verifyNoInteractions(service,projection);
    }
    @Test void authorizationFailurePreventsServiceAndProjection() {
        when(access.current(anySet())).thenThrow(new IllegalStateException("revoked"));
        assertThatThrownBy(()->controller.samples(null,null,1L,20L,"needle")).hasMessage("revoked");
        verifyNoInteractions(service,projection);
    }
}
