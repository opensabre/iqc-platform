package io.github.opensabre.iqc.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.knowledge.KnowledgeBaseService;
import io.github.opensabre.iqc.knowledge.model.KnowledgeBase;
import io.github.opensabre.iqc.privacy.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.Set;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Default MVC converters must retain exact JSON types before domain writes. */
class KnowledgeHttpContractTest {
    private final KnowledgeBaseService bases=mock(KnowledgeBaseService.class);
    private final SensitiveReadAccessService sensitive=mock(SensitiveReadAccessService.class);
    private final MockMvc mvc=MockMvcBuilders.standaloneSetup(new KnowledgeBaseController(bases,sensitive,new ResultReadProjection(new ObjectMapper())))
            .setControllerAdvice(new io.github.opensabre.webmvc.rest.RestResponseBodyAdvice(),new io.github.opensabre.iqc.governance.IqcExceptionHandlerAdvice(),
                    new io.github.opensabre.webmvc.exception.DefaultWebMvcExceptionHandlerAdvice()).build();
    KnowledgeHttpContractTest(){when(sensitive.current(anySet())).thenReturn(new SensitiveReadAccessService.Access("iqc-sensitive-read-v1",true,true,true,Instant.now()));}
    @ParameterizedTest @ValueSource(strings={"null","0","-1","1.0","1.5","\"1\"","true","2147483648","{}"})
    void invalidRevisionStopsBeforePublish(String value)throws Exception{
        mvc.perform(post("/api/iqc/knowledge-bases/kb/publish").contentType("application/json").content("{\"expectedRevision\":"+value+"}"))
                .andExpect(jsonPath("$.code").value("IQC-1003"));
        verifyNoInteractions(bases);
    }
    @ParameterizedTest @ValueSource(strings={"null","0","1","\"true\"","{}"})
    void invalidBooleanStopsBeforeAvailability(String value)throws Exception{
        mvc.perform(post("/api/iqc/knowledge-bases/kb/availability").contentType("application/json").content("{\"enabled\":"+value+"}"))
                .andExpect(jsonPath("$.code").value("IQC-1003"));verifyNoInteractions(bases);
    }
    @Test void booleanWriteReceiptWithFullReadGrantsStillHasNoBody()throws Exception{
        var row=new KnowledgeBase();row.setId("kb");row.setDraftConfigJson("private corpus");when(bases.availability("kb",true)).thenReturn(row);
        mvc.perform(post("/api/iqc/knowledge-bases/kb/availability").contentType("application/json").content("{\"enabled\":true}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private corpus"))));
        verify(sensitive).current(Set.of("iqc:knowledge:availability"));verify(bases).availability("kb",true);
    }
    @Test void revokedCurrentGrantStopsBeforeReadingDomain()throws Exception{
        when(sensitive.current(Set.of("iqc:knowledge:detail:view"))).thenThrow(io.github.opensabre.iqc.governance.IqcException.accessDenied("revoked"));
        mvc.perform(get("/api/iqc/knowledge-bases/kb")).andExpect(jsonPath("$.code").value("IQC-1005"));verifyNoInteractions(bases);
    }
}
