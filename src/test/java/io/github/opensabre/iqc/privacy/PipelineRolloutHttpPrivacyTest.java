package io.github.opensabre.iqc.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.pipelineasset.*;
import io.github.opensabre.iqc.rest.*;
import io.github.opensabre.iqc.task.model.InspectionTask;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real default MVC codecs and receipt projection; authorization and service persistence are isolated. */
class PipelineRolloutHttpPrivacyTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final PipelineRolloutTaskService tasks=mock(PipelineRolloutTaskService.class);
    private final PipelineRolloutService policies=mock(PipelineRolloutService.class);
    private final SensitiveReadAccessService grants=mock(SensitiveReadAccessService.class);
    private static final Set<String> CREATE=Set.of("iqc:pipeline:rollout:task:create","iqc:pipeline:task:create");
    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new PipelineRolloutTaskController(tasks,grants,new ResultReadProjection(json)),
                new PipelineRolloutController(policies,grants))
            .setControllerAdvice(new io.github.opensabre.webmvc.rest.RestResponseBodyAdvice(),
                new io.github.opensabre.iqc.governance.IqcExceptionHandlerAdvice(),
                new io.github.opensabre.webmvc.exception.DefaultWebMvcExceptionHandlerAdvice()).build();
    }
    private void allow(boolean full,boolean pii) {
        when(grants.current(anySet())).thenReturn(new SensitiveReadAccessService.Access("actor",full,pii,true,Instant.now()));
    }
    private PipelineRelease release(int retries) {
        var node=new io.github.opensabre.iqc.pipeline.PipelineDefinition.Node("rule","RULE","Rule","rule-v1",List.of(),Map.of(),json.createObjectNode(),null,3000,retries,
                io.github.opensabre.iqc.pipeline.PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
        return new PipelineRelease(PipelineRelease.SCHEMA,new io.github.opensabre.iqc.pipeline.PipelineDefinition(io.github.opensabre.iqc.pipeline.PipelineDefinition.SCHEMA,List.of(node)),
                new io.github.opensabre.iqc.fusion.FusionPolicy("fusion",1,io.github.opensabre.iqc.fusion.FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of()),
                new io.github.opensabre.iqc.scoring.InspectionScoring.Policy(io.github.opensabre.iqc.scoring.InspectionScoring.POLICY_VERSION,io.github.opensabre.iqc.scoring.InspectionScoring.Mode.DEDUCTION,100,60,List.of()),List.of());
    }
    private PipelineRolloutPolicy policy() {
        return new PipelineRolloutPolicy(PipelineRolloutPolicy.SCHEMA,"p",1,2,5000,"salt_0001",PipelineRolloutPolicy.State.ACTIVE,
                new io.github.opensabre.iqc.quality.evaluation.BenchmarkVariableComparator.Selection(
                    io.github.opensabre.iqc.quality.evaluation.BenchmarkVariableComparator.Dimension.PIPELINE,null,null));
    }
    private InspectionTask task() {
        var left=release(0);var right=release(1);
        var baseline=new PipelineAssetService.Prepared("p",1,null,left,PipelineAssetService.hash(json.valueToTree(left).toString()));
        var candidate=new PipelineAssetService.Prepared("p",2,null,right,PipelineAssetService.hash(json.valueToTree(right).toString()));
        var policy=policy();var snapshot=new PipelineRolloutService.Snapshot("iqc-pipeline-rollout-revision-v1","p",1,null,policy,
                PipelineAssetService.hash(json.valueToTree(policy).toString()),baseline.contentHash(),candidate.contentHash(),PipelineRolloutService.Change.ACTIVATE);
        var revision=new PipelineRolloutService.Revision("revision-1",1,PipelineAssetService.hash(json.valueToTree(snapshot).toString()),snapshot,null);
        var decision=policy.preview("actor","req",baseline,candidate,json);
        String id=PipelineRolloutTaskEvidence.taskId("actor","p","req",json);
        var evidence=new PipelineRolloutTaskEvidence(PipelineRolloutTaskEvidence.SCHEMA,id,
                PipelineRolloutTaskEvidence.requestFingerprint("name",List.of("c"),1,json),revision,decision);
        var root=json.createObjectNode();root.set("rolloutSnapshot",evidence.envelope(json));
        root.set("pipelineSnapshot",json.valueToTree(decision.side()==PipelineRolloutPolicy.Side.CANDIDATE?candidate:baseline));
        root.put("internal","private-conversation");
        var row=new InspectionTask();row.setId(id);row.setName("Call 13812345678");row.setTaskType("PIPELINE");row.setStatus("CREATED");
        row.setRuleSnapshotJson(root.toString());row.setAgentSnapshotJson("private-agent");row.setLabelScopeSnapshotJson("private-label");
        row.setAutoExpandPrompt("private-prompt");row.setConversationIdsJson("[\"c\"]");row.setSelectionFilterJson("private-selector");
        row.setRetryError("private-error");return row;
    }
    private String createBody(String concurrency,String key) {
        return "{\"name\":\"name\",\"conversationIds\":[\"c\"],\"concurrency\":"+concurrency+",\"requestKey\":"+key+"}";
    }
    @ParameterizedTest @CsvSource({"false,false","false,true","true,false","true,true"})
    void receiptShowsOnlyVerifiedPublicAllocationAndNeverFrozenBodies(boolean full,boolean pii) throws Exception {
        allow(full,pii);var row=task();String original=json.writeValueAsString(row);
        when(tasks.create("p","name",List.of("c"),1,"req")).thenReturn(row);
        var body=mvc().perform(post("/api/iqc/pipelines/p/rollout/tasks").contentType("application/json").content(createBody("1","\"req\"")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("000000"))
            .andExpect(header().string("Cache-Control","private, no-store")).andExpect(header().string("X-Content-Type-Options","nosniff"))
            .andExpect(jsonPath("$.data.id").value(row.getId()))
            .andExpect(jsonPath("$.data.taskPresentation.rollout.policyRevision").value(1))
            .andExpect(jsonPath("$.data.taskPresentation.rollout.selectedVersionNo").isNumber())
            .andExpect(jsonPath("$.data.ruleSnapshotJson").doesNotExist()).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private-"));assertFalse(body.contains("cohortHash"));assertFalse(body.contains("salt_0001"));
        assertFalse(body.contains("requestFingerprint"));assertEquals(full&&pii,body.contains("13812345678"));
        assertEquals(original,json.writeValueAsString(row));verify(grants).current(CREATE);
    }
    @ParameterizedTest @ValueSource(strings={"1.0","\"1\"","true","2147483648","9007199254740993","[]","{}"})
    void invalidConcurrencyTokensRejectBeforeMaterialization(String token) throws Exception {
        allow(false,false);
        mvc().perform(post("/api/iqc/pipelines/p/rollout/tasks").contentType("application/json").content(createBody(token,"\"req\"")))
            .andExpect(jsonPath("$.code").value("IQC-1003"));verifyNoInteractions(tasks);verify(grants).current(CREATE);
    }
    @ParameterizedTest @ValueSource(strings={"null","1","1.0","true","[]","{}"})
    void requestKeyMustRemainAString(String token) throws Exception {
        allow(false,false);
        mvc().perform(post("/api/iqc/pipelines/p/rollout/tasks").contentType("application/json").content(createBody("1",token)))
            .andExpect(jsonPath("$.code").value("IQC-1003"));verifyNoInteractions(tasks);
    }
    @Test void nullConcurrencyUsesServiceDefault() throws Exception {
        allow(false,false);when(tasks.create("p","name",List.of("c"),null,"req")).thenReturn(task());
        mvc().perform(post("/api/iqc/pipelines/p/rollout/tasks").contentType("application/json").content(createBody("null","\"req\"")))
            .andExpect(jsonPath("$.code").value("000000"));verify(tasks).create("p","name",List.of("c"),null,"req");
    }
    @Test void revokedGrantRejectsAllEndpointsBeforeServiceReadsAndWrites() throws Exception {
        when(grants.current(anySet())).thenThrow(io.github.opensabre.iqc.governance.IqcException.accessDenied("revoked"));
        var mvc=mvc();
        for(var request:List.of(get("/api/iqc/pipelines/p/rollout"),get("/api/iqc/pipelines/p/rollout/revisions"),
                post("/api/iqc/pipelines/p/rollout/revisions").contentType("application/json").content("{}"),
                post("/api/iqc/pipelines/p/rollout/tasks").contentType("application/json").content(createBody("1.0","null")))) {
            mvc.perform(request).andExpect(jsonPath("$.code").value("IQC-1005")).andExpect(jsonPath("$.data").doesNotExist());
        }
        verifyNoInteractions(tasks,policies);verify(grants).current(CREATE);
        verify(grants).current(Set.of("iqc:pipeline:rollout:view"));verify(grants).current(Set.of("iqc:pipeline:rollout:history:view"));
        verify(grants).current(Set.of("iqc:pipeline:rollout:manage"));
    }
    @ParameterizedTest @ValueSource(strings={"null","-1","1.0","\"1\"","true","2147483648","9007199254740993","[]","{}"})
    void expectedRevisionMustBeNonnegativeExactInteger(String token) throws Exception {
        allow(false,false);
        mvc().perform(post("/api/iqc/pipelines/p/rollout/revisions").contentType("application/json").content("{\"expectedRevision\":"+token+"}"))
            .andExpect(jsonPath("$.code").value("IQC-1003"));verifyNoInteractions(policies);
    }
    @Test void policySaveUsesDefaultMvcCodecAndBothReadEndpointsHaveNoStoreHeaders() throws Exception {
        allow(false,false);var policy=policy();when(policies.current("p")).thenReturn(new PipelineRolloutService.Current(0,null));
        when(policies.history("p",2,10)).thenReturn(List.of());
        var mvc=mvc();
        mvc.perform(post("/api/iqc/pipelines/p/rollout/revisions").contentType("application/json")
                .content("{\"expectedRevision\":0,\"policy\":"+json.writeValueAsString(policy)+"}"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"));
        verify(policies).append("p",0,policy);
        for(var request:List.of(get("/api/iqc/pipelines/p/rollout"),get("/api/iqc/pipelines/p/rollout/revisions").param("beforeRevision","2").param("limit","10"))) {
            mvc.perform(request).andExpect(jsonPath("$.code").value("000000"))
                .andExpect(header().string("Cache-Control","private, no-store")).andExpect(header().string("X-Content-Type-Options","nosniff"));
        }
        verify(policies).history("p",2,10);
    }
    @Test void transplantedEvidenceNeverProducesPublicAllocationClaim() throws Exception {
        allow(false,false);var row=task();row.setId("g"+"f".repeat(63));
        when(tasks.create("p","name",List.of("c"),1,"req")).thenReturn(row);
        mvc().perform(post("/api/iqc/pipelines/p/rollout/tasks").contentType("application/json").content(createBody("1","\"req\"")))
            .andExpect(jsonPath("$.code").value("000000"))
            .andExpect(jsonPath("$.data.taskPresentation.rollout").doesNotExist()).andExpect(jsonPath("$.data.ruleSnapshotJson").doesNotExist());
    }
    @Test void rolloutAuditDeclarationsNeverCaptureRequestOrResponseBodies() throws Exception {
        for(var type:List.of(PipelineRolloutTaskController.class,PipelineRolloutController.class))for(var method:type.getDeclaredMethods()) {
            var audit=method.getAnnotation(io.github.opensabre.governance.audit.annotations.Audit.class);
            if(audit!=null){assertFalse(audit.request());assertFalse(audit.response());}
        }
    }
}
