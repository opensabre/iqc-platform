package io.github.opensabre.iqc.rest;

import io.github.opensabre.iqc.quality.QualityOperationsService;
import io.github.opensabre.iqc.privacy.ResultReadProjection;
import io.github.opensabre.iqc.privacy.SensitiveReadAccessService;
import io.github.opensabre.iqc.shared.IqcPage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC argument binding/serialization with controlled service and authorization dependencies. */
class CandidatePaginationHttpTest {
    private final QualityOperationsService service=mock(QualityOperationsService.class);
    private final SensitiveReadAccessService access=mock(SensitiveReadAccessService.class);
    private final ResultReadProjection projection=mock(ResultReadProjection.class);
    private final org.springframework.test.web.servlet.MockMvc mvc=MockMvcBuilders.standaloneSetup(
            new QualityOperationsController(service,null,null,null,null,access,projection)).build();
    private static final String URL="/api/iqc/quality-operations/samples";

    @Test void bindsPageAndKeywordAndSerializesMetadataWithPrivateHeaders() throws Exception {
        when(service.samples("UNCLASSIFIED","CANDIDATE",2,20,"精确样本")).thenReturn(new IqcPage<>(List.of(),2,20,41));
        when(projection.samples(anyList(),any())).thenReturn(List.of());
        mvc.perform(get(URL).param("current","2").param("size","20").param("keyword","精确样本")
                .param("type","UNCLASSIFIED").param("status","CANDIDATE"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andExpect(jsonPath("$.current").value(2)).andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(41)).andExpect(jsonPath("$.records").isArray());
        verify(service).samples("UNCLASSIFIED","CANDIDATE",2,20,"精确样本");
    }
    @Test void absentPaginationRetainsArrayResponse() throws Exception {
        when(service.samples(null,null)).thenReturn(List.of());when(projection.samples(anyList(),any())).thenReturn(List.of());
        mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(service).samples(null,null);verifyNoMoreInteractions(service);
    }
    @Test void malformedNumericParametersFailBeforeAuthorizationAndService() throws Exception {
        mvc.perform(get(URL).param("current","not-a-number").param("size","20")).andExpect(status().isBadRequest());
        mvc.perform(get(URL).param("current","1").param("size","9223372036854775808")).andExpect(status().isBadRequest());
        verifyNoInteractions(service,access,projection);
    }
}
