package io.github.opensabre.iqc.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.quality.evaluation.*;
import io.github.opensabre.iqc.quality.model.EvaluationDataset;
import io.github.opensabre.iqc.rest.*;
import io.github.opensabre.iqc.shared.IqcPage;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.webmvc.rest.RestResponseBodyAdvice;
import java.math.BigDecimal;
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

/** Default HTTP serialization of comparison metadata and protected dataset drafts. External services are isolated. */
class BenchmarkDraftHttpPrivacyTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final BenchmarkService benchmarks=mock(BenchmarkService.class);
    private final EvaluationDatasetService datasets=mock(EvaluationDatasetService.class);
    private final SensitiveReadAccessService grants=mock(SensitiveReadAccessService.class);
    private final ResultReadProjection projection=new ResultReadProjection(json);
    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new BenchmarkController(benchmarks,grants,projection),new EvaluationDatasetController(datasets,grants,projection))
                .setControllerAdvice(new RestResponseBodyAdvice(),new io.github.opensabre.iqc.governance.IqcExceptionHandlerAdvice(),
                        new io.github.opensabre.webmvc.exception.DefaultWebMvcExceptionHandlerAdvice()).build();
    }
    private void allow(boolean full,boolean pii,boolean prompt){
        when(grants.current(anySet())).thenReturn(new SensitiveReadAccessService.Access("test",full,pii,prompt,Instant.now()));
    }
    private InspectionTask side(String id){
        var row=new InspectionTask();row.setId(id);row.setName("Call 13812345678");row.setStatus("PENDING");row.setProcessedMessages(0);
        row.setAgentSnapshotJson("private-agent");row.setRuleSnapshotJson("private-rule");row.setAutoExpandPrompt("private-prompt");
        row.setSelectionFilterJson("private-filter");row.setTriggerRequestJson("private-trigger");return row;
    }
    @ParameterizedTest @CsvSource({"false,false,false","false,false,true","false,true,false","false,true,true","true,false,false","true,false,true","true,true,false","true,true,true"})
    void benchmarkAllThreeResponsesProtectBothTasksAndKeepComparison(boolean full,boolean pii,boolean prompt) throws Exception {
        allow(full,pii,prompt);
        var pair=new BenchmarkService.Pair("schema","experiment","Call 13812345678","d",1,"dataset-hash",
                json.readTree("{\"dimension\":\"PROMPT\",\"nodeId\":\"n\"}"),json.readTree("{\"allowedPaths\":[\"nodes/n/prompt\"],\"invariantHash\":\"stable\"}"),side("b"),side("c"));
        String original=json.writeValueAsString(pair);
        var report=new BenchmarkService.Report("report",pair,null,null,false,Map.of("zero",BigDecimal.ZERO,"delta",new BigDecimal("-0.12345678901234567890123456789")));
        when(benchmarks.create(any())).thenReturn(pair);when(benchmarks.pair("b")).thenReturn(pair);when(benchmarks.report("b","be","ce")).thenReturn(report);
        var mvc=mvc();String request="{\"name\":\"name\",\"datasetId\":\"d\",\"datasetVersion\":1,\"baseline\":{\"pipelineId\":\"p\",\"pipelineVersion\":1},\"candidate\":{\"pipelineId\":\"p\",\"pipelineVersion\":2},\"selection\":{\"dimension\":\"PROMPT\",\"nodeId\":\"n\"}}";
        var responses=List.of(mvc.perform(post("/api/iqc/evaluation/benchmarks").contentType("application/json").content(request)).andReturn().getResponse(),
                mvc.perform(get("/api/iqc/evaluation/benchmarks/b")).andReturn().getResponse(),
                mvc.perform(get("/api/iqc/evaluation/benchmarks/b/report?baselineExecutionId=be&candidateExecutionId=ce")).andReturn().getResponse());
        for(int i=0;i<responses.size();i++){
            var response=responses.get(i);assertEquals(200,response.getStatus());assertEquals("private, no-store",response.getHeader("Cache-Control"));assertEquals("nosniff",response.getHeader("X-Content-Type-Options"));
            String body=response.getContentAsString();var envelope=json.readTree(body);assertEquals("000000",envelope.path("code").asText());
            assertFalse(body.contains("private-"));assertEquals(full&&pii,body.contains("13812345678"));
            var data=envelope.path("data");var experiment=i==2?data.path("experiment"):data;
            assertEquals("b",experiment.at("/baseline/id").asText());assertEquals("c",experiment.at("/candidate/id").asText());
            assertEquals("dataset-hash",experiment.path("datasetHash").asText());assertEquals("PROMPT",experiment.at("/selection/dimension").asText());
            assertEquals(pair.selection(),experiment.path("selection"));assertEquals(pair.comparison(),experiment.path("comparison"));
            assertEquals("stable",experiment.at("/comparison/invariantHash").asText());assertEquals(0,experiment.at("/baseline/processedMessages").asInt(-1));
            if(i==2){assertFalse(data.path("complete").asBoolean(true));assertEquals(0,data.at("/deltas/zero").asInt(-1));assertTrue(body.contains("-0.12345678901234567890123456789"));}
        }
        assertEquals(original,json.writeValueAsString(pair));
        verify(grants).current(Set.of("iqc:benchmark:create"));verify(grants).current(Set.of("iqc:benchmark:view"));verify(grants).current(Set.of("iqc:benchmark:report:view"));
    }
    private EvaluationDataset draft() throws Exception {
        var row=new EvaluationDataset();row.setId("d");row.setName("Call 13812345678");row.setDraftRevision(3);row.setStatus("ENABLED");
        row.setDraftConfigJson(json.writeValueAsString(new DatasetDraft(DatasetDraft.SCHEMA,DatasetDraft.Type.BASIC,"private-description",List.of(new DatasetDraft.Member("s",2)))));return row;
    }
    @ParameterizedTest @CsvSource({"false,false","false,true","true,false","true,true"})
    void draftDetailSeparatesReferencesFromEditableBodyAndWritesReturnMetadata(boolean full,boolean pii) throws Exception {
        allow(full,pii,false);var row=draft();String original=json.writeValueAsString(row);
        when(datasets.get("d")).thenReturn(row);when(datasets.page(1,20)).thenReturn(new IqcPage<>(List.of(row),1,20,1));
        when(datasets.create(any(),any())).thenReturn(row);when(datasets.update(eq("d"),eq(3),any(),any())).thenReturn(row);when(datasets.availability("d",true)).thenReturn(row);
        var mvc=mvc();String detail=mvc.perform(get("/api/iqc/evaluation/datasets/d")).andExpect(jsonPath("$.code").value("000000"))
                .andExpect(header().string("Cache-Control","private, no-store")).andReturn().getResponse().getContentAsString();
        assertEquals(full&&pii,detail.contains("private-description"));
        if(!(full&&pii)){var data=json.readTree(detail).path("data");assertEquals("s",data.at("/privacy/members/0/sampleId").asText());assertEquals(2,data.at("/privacy/members/0/versionNo").asInt());}
        String edit="{\"name\":\"name\",\"expectedRevision\":3,\"draft\":{\"schemaVersion\":\"iqc-dataset-draft-v1\",\"type\":\"BASIC\",\"description\":\"private-description\",\"members\":[{\"sampleId\":\"s\",\"versionNo\":2}]}}";
        var requests=List.of(get("/api/iqc/evaluation/datasets"),post("/api/iqc/evaluation/datasets").contentType("application/json").content(edit),
                put("/api/iqc/evaluation/datasets/d").contentType("application/json").content(edit),post("/api/iqc/evaluation/datasets/d/availability").contentType("application/json").content("{\"enabled\":true}"));
        for(var request:requests){String body=mvc.perform(request).andExpect(jsonPath("$.code").value("000000")).andExpect(header().string("X-Content-Type-Options","nosniff")).andReturn().getResponse().getContentAsString();assertFalse(body.contains("private-description"));assertFalse(body.contains("\"members\""));}
        assertEquals(original,json.writeValueAsString(row));
        for(String resource:List.of("detail:view","view","manage","update","availability"))verify(grants).current(Set.of("iqc:dataset:"+resource));
    }
    @Test void damagedDraftIsExplicitAndNeverReturnsRawContent() throws Exception {
        allow(false,false,false);var row=draft();row.setDraftConfigJson("private-invalid");var copy=projection.dataset(row,grants.current(Set.of()),true);
        assertNull(copy.getDraftConfigJson());assertEquals("INVALID_DRAFT",copy.getPrivacy().get("previewState"));assertFalse(copy.getPrivacy().containsKey("members"));
    }
    @Test void revokedBaseGrantsStopBenchmarkAndDatasetDomains() throws Exception {
        when(grants.current(anySet())).thenThrow(io.github.opensabre.iqc.governance.IqcException.accessDenied("revoked"));var mvc=mvc();
        var requests=List.of(post("/api/iqc/evaluation/benchmarks").contentType("application/json").content("{}"),get("/api/iqc/evaluation/benchmarks/b"),
                get("/api/iqc/evaluation/benchmarks/b/report?baselineExecutionId=be&candidateExecutionId=ce"),get("/api/iqc/evaluation/datasets"),get("/api/iqc/evaluation/datasets/d"),
                post("/api/iqc/evaluation/datasets").contentType("application/json").content("{}"),put("/api/iqc/evaluation/datasets/d").contentType("application/json").content("{}"),
                post("/api/iqc/evaluation/datasets/d/availability").contentType("application/json").content("{\"enabled\":true}"));
        for(var request:requests)mvc.perform(request).andExpect(jsonPath("$.code").value("IQC-1005")).andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(benchmarks,datasets);
    }
    @Test void optionalUsageSurvivesProjectionAndHttpWithoutChangingDefaultReport() throws Exception {
        allow(false,false,false);
        var pair=new BenchmarkService.Pair("schema","experiment","name","d",1,"hash",null,null,side("b"),side("c"));
        var recorded=new io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.RunUsage(
                new io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.CostSummary("3","1","2",List.of()),
                new io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.CallLatencySummary("3","2","1","12.5"));
        var usage=new BenchmarkService.ModelUsage("iqc-benchmark-model-usage-v1","RECORDED_CALLS_OF_VALIDATED_SAMPLE_SOURCE_RUNS",
                new BenchmarkService.UsageSide(2,1,1,recorded),new BenchmarkService.UsageSide(2,2,0,recorded));
        when(benchmarks.report("b","be","ce",true)).thenReturn(new BenchmarkService.Report("report",pair,null,null,false,Map.of(),usage));
        when(benchmarks.report("b","be","ce")).thenReturn(new BenchmarkService.Report("report",pair,null,null,false,Map.of()));
        var mvc=mvc();
        mvc.perform(get("/api/iqc/evaluation/benchmarks/b/report?baselineExecutionId=be&candidateExecutionId=ce&includeModelUsage=true"))
                .andExpect(jsonPath("$.code").value("000000"))
                .andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(jsonPath("$.data.modelUsage.baseline.unboundSamples").value(1))
                .andExpect(jsonPath("$.data.modelUsage.baseline.recorded.costs.unknownCount").value("2"))
                .andExpect(jsonPath("$.data.modelUsage.candidate.boundSamples").value(2));
        mvc.perform(get("/api/iqc/evaluation/benchmarks/b/report?baselineExecutionId=be&candidateExecutionId=ce"))
                .andExpect(jsonPath("$.code").value("000000"))
                .andExpect(jsonPath("$.data.modelUsage").doesNotExist());
        verify(benchmarks).report("b","be","ce",true);
        verify(benchmarks).report("b","be","ce");
    }

    @Test void realServiceRejectsMissingUsageGrantBeforeAnyReportOrLedgerReadOverHttp() throws Exception {
        var actors=mock(io.github.opensabre.iqc.schedule.ScheduledActorAccessService.class);
        var bound=new io.github.opensabre.iqc.schedule.ScheduledActorAccessService.CreationBound("schema","user","owner",false,"group");
        when(actors.capture()).thenReturn(bound);
        when(actors.resolveCurrent(eq(bound),anySet())).thenAnswer(invocation->{
            Set<String> resources=invocation.getArgument(1);
            if(resources.contains("iqc:task:model-calls:view"))throw io.github.opensabre.iqc.governance.IqcException.accessDenied("usage revoked");
            return new io.github.opensabre.iqc.schedule.ScheduledActorAccessService.Resolved("user","owner",false,"group",Set.of(),resources,Instant.now());
        });
        var sensitive=new SensitiveReadAccessService(actors);
        var evaluations=mock(PipelineEvaluationService.class);
        var ledger=mock(io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.class);
        var service=new BenchmarkService(evaluations,null,null,null,json,ledger,sensitive);
        var mvc=MockMvcBuilders.standaloneSetup(new BenchmarkController(service,sensitive,projection))
                .setControllerAdvice(new RestResponseBodyAdvice(),new io.github.opensabre.iqc.governance.IqcExceptionHandlerAdvice(),
                        new io.github.opensabre.webmvc.exception.DefaultWebMvcExceptionHandlerAdvice()).build();
        mvc.perform(get("/api/iqc/evaluation/benchmarks/b/report?baselineExecutionId=be&candidateExecutionId=ce&includeModelUsage=true"))
                .andExpect(jsonPath("$.code").value("IQC-1005"))
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(actors).resolveCurrent(bound,Set.of("iqc:benchmark:report:view"));
        verify(actors).resolveCurrent(bound,Set.of("iqc:benchmark:report:view","iqc:task:model-calls:view"));
        verifyNoInteractions(evaluations,ledger);
    }

}
