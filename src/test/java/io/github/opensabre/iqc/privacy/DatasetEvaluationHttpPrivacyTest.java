package io.github.opensabre.iqc.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.quality.evaluation.*;
import io.github.opensabre.iqc.quality.model.*;
import io.github.opensabre.iqc.rest.*;
import io.github.opensabre.iqc.shared.IqcPage;
import io.github.opensabre.webmvc.rest.RestResponseBodyAdvice;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Default MVC contracts with real frozen projections; PDP and domain persistence are isolated. */
class DatasetEvaluationHttpPrivacyTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final EvaluationDatasetService datasets=mock(EvaluationDatasetService.class);
    private final PipelineEvaluationService evaluations=mock(PipelineEvaluationService.class);
    private final SensitiveReadAccessService grants=mock(SensitiveReadAccessService.class);
    private final ResultReadProjection projection=new ResultReadProjection(json);
    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new EvaluationDatasetController(datasets,grants,projection),
                new PipelineEvaluationController(evaluations,grants,projection))
                .setControllerAdvice(new RestResponseBodyAdvice(),new io.github.opensabre.iqc.governance.IqcExceptionHandlerAdvice()).build();
    }
    private void allow(boolean full,boolean pii,boolean prompt) {
        when(grants.current(anySet())).thenReturn(new SensitiveReadAccessService.Access("test",full,pii,prompt,Instant.now()));
    }
    private EvaluationDatasetVersion frozen() throws Exception {
        var messages=new ArrayList<GoldConversation.Message>();
        for(int i=1;i<=4;i++)messages.add(new GoldConversation.Message("m"+i,"CUSTOMER","13812345678 "+"x".repeat(240),i,null));
        var gold=new GoldConversation(GoldConversation.SCHEMA,"s","c",messages,null,List.of(
                new GoldFinding("FACT",Finding.ValueType.STRING,Finding.State.KNOWN,json.valueToTree("private-gold"),List.of("m4"))),
                "annotator","reviewer","candidate",List.of(),GoldConversation.Difficulty.BASIC,null);
        var snapshot=new DatasetSnapshot(DatasetSnapshot.SCHEMA,"d","name",DatasetDraft.Type.values()[0],null,
                List.of(new DatasetSnapshot.Member("s",1,1,"a".repeat(64),gold)));
        var row=new EvaluationDatasetVersion();row.setId("dv");row.setDatasetId("d");row.setVersionNo(1);
        row.setSampleCount(1);row.setContentHash("b".repeat(64));row.setSnapshotJson(json.writeValueAsString(snapshot));return row;
    }
    private EvaluationSampleResult output() {
        var row=new EvaluationSampleResult();row.setId("r");row.setTaskId("t");row.setStatus("SUCCESS");
        row.setDurationMs(0L);row.setActualHash("c".repeat(64));
        row.setActualJson("{\"run\":{\"status\":\"SUCCESS\",\"trace\":[{\"prompt\":\"private-prompt\"}],\"findings\":[{\"value\":\"13812345678\"}],\"completionReason\":\"POLICY_SATISFIED\"}}");return row;
    }
    @ParameterizedTest @CsvSource({"false,false","false,true","true,false","true,true"})
    void frozenVersionUsesIndependentReadAndHistoryNeverContainsGold(boolean full,boolean pii) throws Exception {
        allow(full,pii,false);var row=frozen();String original=json.writeValueAsString(row);
        when(datasets.version("d",1)).thenReturn(row);when(datasets.history("d",2,3)).thenReturn(new IqcPage<>(List.of(row),2,3,9));
        when(datasets.freeze("d",1)).thenReturn(row);var mvc=mvc();
        var response=mvc.perform(get("/api/iqc/evaluation/datasets/d/versions/1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("000000"))
                .andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(header().string("X-Content-Type-Options","nosniff")).andReturn().getResponse().getContentAsString();
        var data=json.readTree(response).path("data");assertEquals(full&&pii,response.contains("private-gold"));
        if(!(full&&pii)) {
            assertTrue(data.path("snapshotJson").isNull()||data.path("snapshotJson").isMissingNode());
            assertFalse(response.contains("13812345678"));assertEquals("AVAILABLE",data.at("/privacy/previewState").asText());
            var messages=data.at("/privacy/members/0/privacy/preview/messages");assertEquals(full?4:3,messages.size());
            if(!full)for(var message:messages)assertTrue(message.path("content").asText().length()<=200);
        }
        String history=mvc.perform(get("/api/iqc/evaluation/datasets/d/versions?current=2&size=3"))
                .andExpect(jsonPath("$.code").value("000000")).andExpect(jsonPath("$.data.current").value(2))
                .andExpect(jsonPath("$.data.total").value(9)).andReturn().getResponse().getContentAsString();
        String receipt=mvc.perform(post("/api/iqc/evaluation/datasets/d/freeze").contentType("application/json").content("{\"expectedRevision\":1}"))
                .andExpect(jsonPath("$.code").value("000000")).andReturn().getResponse().getContentAsString();
        for(String shown:List.of(history,receipt)){assertFalse(shown.contains("private-gold"));assertFalse(shown.contains("13812345678"));assertFalse(shown.contains("\"members\""));}
        assertEquals(original,json.writeValueAsString(row));
        verify(grants).current(Set.of("iqc:dataset:version:view"));verify(grants).current(Set.of("iqc:dataset:versions:view"));verify(grants).current(Set.of("iqc:dataset:freeze"));
    }
    @ParameterizedTest @CsvSource({"false,false,false","false,false,true","false,true,false","false,true,true","true,false,false","true,false,true","true,true,false","true,true,true"})
    void evaluationOutputRequiresAllThreeGrantsAndListAlwaysWithholdsIt(boolean full,boolean pii,boolean prompt) throws Exception {
        allow(full,pii,prompt);var row=output();String original=json.writeValueAsString(row);
        when(evaluations.result("t","r")).thenReturn(row);when(evaluations.page("t",null,1,20)).thenReturn(new IqcPage<>(List.of(row),1,20,1));
        var mvc=mvc();String response=mvc.perform(get("/api/iqc/evaluation/tasks/t/results/r"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("000000"))
                .andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andExpect(jsonPath("$.data.durationMs").value(0)).andReturn().getResponse().getContentAsString();
        assertEquals(full&&pii&&prompt,response.contains("private-prompt"));assertEquals(full&&pii&&prompt,response.contains("13812345678"));
        if(!(full&&pii&&prompt)) {
            var data=json.readTree(response).path("data");assertEquals("AVAILABLE",data.at("/privacy/previewState").asText());
            assertEquals(1,data.at("/privacy/preview/traceCount").asInt());assertEquals(1,data.at("/privacy/preview/findingCount").asInt());
        }
        String page=mvc.perform(get("/api/iqc/evaluation/tasks/t/results"))
                .andExpect(jsonPath("$.code").value("000000")).andReturn().getResponse().getContentAsString();
        assertFalse(page.contains("private-prompt"));assertFalse(page.contains("13812345678"));assertEquals(original,json.writeValueAsString(row));
        verify(grants).current(Set.of("iqc:evaluation:result:view"));verify(grants).current(Set.of("iqc:evaluation:results:view"));
    }
    @Test void malformedAndCrossDatasetSnapshotsNeverBecomeEmptySuccessPreviews() throws Exception {
        allow(false,false,false);var row=frozen();row.setDatasetId("other");
        assertEquals("INVALID_DATASET",projection.datasetVersion(row,grants.current(Set.of()),true).getPrivacy().get("previewState"));
        row.setSnapshotJson("{invalid");assertEquals("INVALID_DATASET",projection.datasetVersion(row,grants.current(Set.of()),true).getPrivacy().get("previewState"));
        var result=output();for(String invalid:List.of("{invalid","{}","{\"run\":{\"status\":\"SUCCESS\",\"trace\":[],\"findings\":null}}")){
            result.setActualJson(invalid);var copy=projection.evaluationResult(result,grants.current(Set.of()),true);
            assertNull(copy.getActualJson());assertEquals("INVALID_OUTPUT",copy.getPrivacy().get("previewState"));assertFalse(copy.getPrivacy().containsKey("preview"));
        }
    }
    @Test void authorizationFailureStopsAllProtectedDomainOperations() throws Exception {
        when(grants.current(anySet())).thenThrow(new IllegalStateException("unavailable"));var mvc=mvc();
        for(String path:List.of("/api/iqc/evaluation/datasets/d/versions/1","/api/iqc/evaluation/datasets/d/versions",
                "/api/iqc/evaluation/tasks/t/results","/api/iqc/evaluation/tasks/t/results/r"))
            mvc.perform(get(path)).andExpect(jsonPath("$.code").value("IQC-1006")).andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(post("/api/iqc/evaluation/datasets/d/freeze").contentType("application/json").content("{\"expectedRevision\":1}"))
                .andExpect(jsonPath("$.code").value("IQC-1006"));
        verifyNoInteractions(datasets,evaluations);
    }
}
