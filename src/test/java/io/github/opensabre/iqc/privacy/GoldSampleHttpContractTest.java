package io.github.opensabre.iqc.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.quality.QualityOperationsService;
import io.github.opensabre.iqc.quality.evaluation.*;
import io.github.opensabre.iqc.quality.model.QualitySample;
import io.github.opensabre.iqc.rest.*;
import io.github.opensabre.webmvc.rest.RestResponseBodyAdvice;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MVC binding and serialization contracts; external authorization and domain persistence are isolated. */
class GoldSampleHttpContractTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final QualityOperationsService samples=mock(QualityOperationsService.class);
    private final GoldSampleService gold=mock(GoldSampleService.class);
    private final SensitiveReadAccessService grants=mock(SensitiveReadAccessService.class);
    private final ResultReadProjection projection=new ResultReadProjection(json);
    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new GoldSampleController(gold,grants,projection),
                new QualityOperationsController(samples,null,null,null,null,grants,projection))
                .setControllerAdvice(new RestResponseBodyAdvice(),new io.github.opensabre.iqc.governance.IqcExceptionHandlerAdvice()).build();
    }
    private QualitySample source() throws Exception {
        var messages=new ArrayList<GoldConversation.Message>();
        for(int i=1;i<=4;i++)messages.add(new GoldConversation.Message("m"+i,"CUSTOMER","Call 13812345678 "+"x".repeat(230),i,null));
        var draft=new GoldDraft(GoldDraft.SCHEMA,"s","c",messages,null,List.of(
                new GoldFinding("FACT",Finding.ValueType.STRING,Finding.State.KNOWN,json.valueToTree("gold-private"),List.of("m4"))),
                "annotator","candidate",List.of(),GoldConversation.Difficulty.BASIC,null);
        var row=new QualitySample();row.setId("s");row.setConversationId("c");row.setName("Call 13812345678");
        row.setContentSnapshot("candidate-private");row.setExpectedJson("expected-private");row.setTagsJson("tags-private");
        row.setGoldDraftJson(json.writeValueAsString(draft));return row;
    }
    private void allow(boolean full,boolean pii) {
        when(grants.current(anySet())).thenReturn(new SensitiveReadAccessService.Access("test",full,pii,false,Instant.now()));
    }
    @ParameterizedTest @CsvSource({"false,false","false,true","true,false","true,true"})
    void listAndGoldHttpReadsKeepSeparateDisclosureBoundaries(boolean full,boolean pii) throws Exception {
        allow(full,pii);var source=source();String original=json.writeValueAsString(source);
        when(samples.samples(null,null)).thenReturn(List.of(source));when(gold.get("s")).thenReturn(source);
        var mvc=mvc();var listed=mvc.perform(get("/api/iqc/quality-operations/samples"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("000000"))
                .andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(listed.contains("gold-private"));assertEquals(full&&pii,listed.contains("candidate-private"));
        var response=mvc.perform(get("/api/iqc/quality-operations/samples/s/gold"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("000000"))
                .andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andReturn().getResponse().getContentAsString();
        var data=json.readTree(response).path("data");
        assertEquals(full&&pii,response.contains("gold-private"));
        if(!(full&&pii)) {
            assertFalse(response.contains("13812345678"));
            var messages=data.at("/privacy/preview/messages");assertEquals(full?4:3,messages.size());
            if(!full)for(var message:messages)assertTrue(message.path("content").asText().length()<=200);
        }
        assertEquals(original,json.writeValueAsString(source));
        verify(grants).current(Set.of("iqc:sample:view"));verify(grants).current(Set.of("iqc:gold:view"));
    }
    @Test void annotationBindsExactNestedNumbersAndReturnsNoCanonicalPayload() throws Exception {
        allow(false,false);var source=source();when(gold.annotate(eq("s"),any())).thenReturn(source);
        String decimal="0.12345678901234567890123456789";
        String body="""
                {"expectedRevision":1,"findings":[{"code":"AMOUNT","valueType":"NUMBER","state":"KNOWN","value":%s,"evidenceMessageIds":[]}],
                 "businessContext":{"amount":%s,"count":9007199254740993},"tags":[],"difficulty":"BASIC",
                 "decision":{"score":0,"scoreStatus":"FINAL","labels":{"AMOUNT":{"value":%s}},"risk":"NONE"}}
                """.formatted(decimal,decimal,decimal);
        var response=mvc().perform(put("/api/iqc/quality-operations/samples/s/gold").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("000000"))
                .andExpect(jsonPath("$.data.privacy.previewState").value("RELOAD_REQUIRED"))
                .andExpect(header().string("Cache-Control","private, no-store"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("gold-private"));assertFalse(response.contains("candidate-private"));assertFalse(response.contains("expected-private"));
        var captured=ArgumentCaptor.forClass(GoldSampleService.Annotation.class);verify(gold).annotate(eq("s"),captured.capture());
        var request=captured.getValue();assertEquals(new java.math.BigDecimal(decimal),request.findings().getFirst().value().decimalValue());
        assertEquals(new java.math.BigDecimal(decimal),request.businessContext().get("amount").decimalValue());
        assertEquals("9007199254740993",request.businessContext().get("count").bigIntegerValue().toString());
        assertEquals(new java.math.BigDecimal(decimal),request.decision().labels().get("AMOUNT").get("value").decimalValue());
        verify(grants).current(Set.of("iqc:gold:annotate"));
    }
    @Test void outerMvcReaderStillRejectsTrailingJsonDocumentsBeforeDomainAccess() throws Exception {
        mvc().perform(put("/api/iqc/quality-operations/samples/s/gold").contentType("application/json")
                .content("{\"expectedRevision\":1,\"findings\":[],\"businessContext\":{\"amount\":1.25},\"tags\":[],\"difficulty\":\"BASIC\"} {}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(gold,grants);
    }

    @Test void sampleCreateReturnsMetadataAndRevocationDeniesNextHttpRead() throws Exception {
        allow(true,true);var source=source();when(samples.createSample("r","name","POSITIVE",null,null)).thenReturn(source);
        var mvc=mvc();String receipt=mvc.perform(post("/api/iqc/quality-operations/results/r/samples")
                .contentType("application/json").content("{\"name\":\"name\",\"sampleType\":\"POSITIVE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("000000"))
                .andExpect(jsonPath("$.data.privacy.previewState").value("RELOAD_REQUIRED"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(receipt.contains("gold-private"));assertFalse(receipt.contains("candidate-private"));
        verify(grants).current(Set.of("iqc:sample:manage"));clearInvocations(samples);
        when(grants.current(Set.of("iqc:sample:view"))).thenThrow(new IllegalStateException("revoked"));
        mvc.perform(get("/api/iqc/quality-operations/samples"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("IQC-1006"))
                .andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(samples);
    }
}
