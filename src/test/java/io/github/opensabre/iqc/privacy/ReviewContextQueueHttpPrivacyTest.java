package io.github.opensabre.iqc.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.label.LabelResultReviewService;
import io.github.opensabre.iqc.label.model.LabelReviewQueueItem;
import io.github.opensabre.iqc.quality.*;
import io.github.opensabre.iqc.quality.model.BusinessReviewQueueItem;
import io.github.opensabre.iqc.rest.QualityOperationsController;
import io.github.opensabre.iqc.shared.IqcPage;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Tests the HTTP response boundary, not a live gateway or organization database. */
class ReviewContextQueueHttpPrivacyTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final BusinessItemReviewService business = mock(BusinessItemReviewService.class);
    private final LabelResultReviewService labels = mock(LabelResultReviewService.class);
    private final SensitiveReadAccessService grants = mock(SensitiveReadAccessService.class);
    private MockMvc mvc() {
        var controller = new QualityOperationsController(mock(QualityOperationsService.class), business,
                mock(BusinessQualityReportService.class), labels, mock(BusinessReviewSampleService.class),
                grants, new ResultReadProjection(json));
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(
                new io.github.opensabre.webmvc.rest.RestResponseBodyAdvice(),
                new io.github.opensabre.iqc.governance.IqcExceptionHandlerAdvice(),
                new io.github.opensabre.webmvc.exception.DefaultWebMvcExceptionHandlerAdvice()).build();
    }
    private void allow(boolean full, boolean pii, boolean prompt) {
        when(grants.current(anySet())).thenReturn(new SensitiveReadAccessService.Access("test",full,pii,prompt,Instant.now()));
    }
    private com.fasterxml.jackson.databind.JsonNode data(MockMvc mvc, String url) throws Exception {
        var body = mvc.perform(get(url)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value("000000"))
                .andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data");
    }
    @ParameterizedTest
    @CsvSource({"false,false,false","false,false,true","false,true,false","false,true,true",
            "true,false,false","true,false,true","true,true,false","true,true,true"})
    void findingContextRequiresBothDataGrantsAndPreservesExactFrozenSource(boolean full, boolean pii, boolean prompt) throws Exception {
        allow(full,pii,prompt);
        var messages = json.readTree("[{\"id\":\"m1\",\"content\":\"private conversation 13812345678\"}]");
        var fact = new BusinessItemReviewService.FindingEntry("amount","NUMBER","KNOWN","0.900000000000000001",
                "HIGH","finding-hash",List.of(),List.of("CONVERSATION"),true,
                List.of(new BusinessItemReviewService.FindingEvidence("e1","CONVERSATION","m1")),List.of("e1"),List.of());
        var source = new BusinessItemReviewService.FindingContext("r1","source-hash",true,null,List.of(fact),messages);
        String original = json.writeValueAsString(source);
        when(business.findingContext("r1")).thenReturn(source);
        var mvc = mvc();
        var shown = data(mvc,"/api/iqc/quality-operations/reviews?targetType=BUSINESS&view=FINDING_CONTEXT&resultId=r1");
        assertEquals("r1",shown.path("sourceResultId").asText());
        assertEquals("source-hash",shown.path("sourceHash").asText());
        assertEquals(full&&pii,shown.path("available").asBoolean());
        assertEquals(full&&pii,shown.path("privacy").path("canonicalPayloadVisible").asBoolean());
        if (full&&pii) {
            assertEquals("0.900000000000000001",shown.path("findings").get(0).path("valueJson").asText());
            assertEquals(messages,shown.path("messages"));
        } else {
            assertTrue(shown.path("findings").isEmpty());assertTrue(shown.path("messages").isEmpty());
            assertFalse(shown.toString().contains("private conversation"));
        }
        assertEquals(original,json.writeValueAsString(source));
        verify(grants).current(Set.of("iqc:review:view"));
    }
    @ParameterizedTest
    @CsvSource({"false,false","false,true","true,false","true,true"})
    void queueProjectionKeepsPaginationAndEscalationStagesWithoutMutatingRows(boolean full, boolean pii) throws Exception {
        allow(full,pii,false);
        var rows = new ArrayList<BusinessReviewQueueItem>();
        for (String stage:List.of("INITIAL","SECOND_REVIEW","ARBITRATION","INVALID"))
            rows.add(new BusinessReviewQueueItem("review-"+stage,"r1","t1","Call 13812345678","c1",3,
                    "PENDING","private request comment","13812345678",null,"SUCCEEDED",true,stage));
        var page = new IqcPage<>(rows,2,4,19);
        var label = new LabelReviewQueueItem();label.setReviewId("lr1");label.setLabelResultId("label-result");
        label.setTaskName("Call 13812345678");label.setLabelName("Call 13812345678");label.setValueDescription("13812345678");
        label.setValueCode("owned");label.setStatus("PENDING");label.setCurrentResult(false);label.setReviewRevision(3);
        label.setRequestComment("private label comment");label.setCreatedBy("13812345678");
        String original = json.writeValueAsString(page), originalLabel = json.writeValueAsString(label);
        when(business.queue("PENDING","t1",2,4)).thenReturn(page);
        when(labels.queue("PENDING","t1",2,4)).thenReturn(new IqcPage<>(List.of(label),2,4,19));
        var mvc = mvc();
        for (String url:List.of("/reviews?targetType=BUSINESS&view=QUEUE&status=PENDING&taskId=t1&current=2&size=4",
                "/label-reviews?status=PENDING&taskId=t1&current=2&size=4")) {
            var shown = data(mvc,"/api/iqc/quality-operations"+url);
            assertEquals(2,shown.path("current").asInt());assertEquals(4,shown.path("size").asInt());assertEquals(19,shown.path("total").asInt());
            assertEquals(full&&pii,shown.toString().contains("private "));
            assertEquals(full&&pii,shown.toString().contains("13812345678"));
            if(url.startsWith("/reviews")) for(int i=0;i<rows.size();i++) {
                assertEquals(rows.get(i).stage(),shown.path("records").get(i).path("stage").asText());
                assertTrue(shown.path("records").get(i).path("currentResult").asBoolean());
            } else {
                assertFalse(shown.path("records").get(0).path("currentResult").asBoolean());
                assertEquals("owned",shown.path("records").get(0).path("valueCode").asText());
            }
        }
        assertEquals(original,json.writeValueAsString(page));assertEquals(originalLabel,json.writeValueAsString(label));
        verify(grants).current(Set.of("iqc:review:view"));verify(grants).current(Set.of("iqc:review:label:queue"));
    }
    @Test void revokedCurrentGrantStopsEveryReadBeforeBusinessQueries() throws Exception {
        when(grants.current(anySet())).thenThrow(io.github.opensabre.iqc.governance.IqcException.accessDenied("revoked"));
        var mvc = mvc();
        for(String suffix:List.of("/reviews?targetType=BUSINESS&view=FINDING_CONTEXT&resultId=r1",
                "/reviews?targetType=BUSINESS&view=QUEUE","/label-reviews"))
            mvc.perform(get("/api/iqc/quality-operations"+suffix)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("IQC-1005")).andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(business,labels);
    }
}
