package io.github.opensabre.iqc.privacy;

import io.github.opensabre.iqc.pipelineasset.*;
import io.github.opensabre.iqc.pipelineasset.model.PipelineAsset;
import io.github.opensabre.iqc.rest.PipelineAssetController;
import io.github.opensabre.iqc.task.InspectionTaskService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Uses the default MVC HTTP codec and captures the complete release at the actual controller boundary. */
class PipelineReleaseHttpContractTest {
    private final PipelineAssetService assets=mock(PipelineAssetService.class);
    private MockMvc mvc() {
        var grants=mock(SensitiveReadAccessService.class);
        when(grants.current(anySet())).thenReturn(new SensitiveReadAccessService.Access("actor",true,true,true,Instant.now()));
        return MockMvcBuilders.standaloneSetup(new PipelineAssetController(assets,mock(InspectionTaskService.class),grants,
                new ResultReadProjection(new com.fasterxml.jackson.databind.ObjectMapper())))
                .setControllerAdvice(new io.github.opensabre.webmvc.rest.RestResponseBodyAdvice(),
                        new io.github.opensabre.iqc.governance.IqcExceptionHandlerAdvice(),
                        new io.github.opensabre.webmvc.exception.DefaultWebMvcExceptionHandlerAdvice()).build();
    }
    private String body(String threshold) {
        return """
            {"code":"pipeline","name":"Pipeline","expectedRevision":7,"release":{
              "schemaVersion":"iqc-pipeline-release-v1",
              "pipeline":{"schemaVersion":"iqc-pipeline-v1","nodes":[{
                "nodeId":"rule","type":"RULE","name":"Rule","version":"rule-v1","dependsOn":[],"inputMapping":{},
                "config":{"value":0.900000000000000001,"large":9007199254740993},
                "condition":{"pointer":"/value","operator":"GREATER_THAN","value":0.900000000000000002},
                "timeoutMs":1000,"retries":0,"onError":"FAIL_PIPELINE","enabled":true,
                "outputSchema":{"type":"number","minimum":0.900000000000000003}}]},
              "fusion":{"id":"fusion","version":1,"method":"PRIORITY","nodePriority":{},"overrideNodeByCode":{},"calibratedNodeIds":[]},
              "scoring":{"version":"iqc-score-v2","mode":"DEDUCTION","baseScore":100,"passingScore":60,
                "items":[{"itemCode":"item","points":20,"veto":false}]},
              "actionPolicy":{"schemaVersion":"iqc-action-policy-v1","actions":[{
                "actionId":"save","type":"SAVE_RESULT","idempotency":"PRODUCTION_SOURCE_V1","failureHandling":"RECORD_ONLY",
                "condition":{"kind":"FINDING_EQUALS","code":"COMPLIANCE","value":0.900000000000000005,"children":[]},
                "configuration":{"threshold":0.900000000000000006}}]},
              "items":[{"itemCode":"item","findingCode":"COMPLIANCE","hitMeaning":"COMPLIANCE",
                "numericPredicate":{"operator":"GREATER_THAN","threshold":THRESHOLD}}]}}
            """.replace("THRESHOLD",threshold);
    }
    private PipelineAsset receipt() {var row=new PipelineAsset();row.setId("p");return row;}
    private void exact(PipelineRelease release) {
        var node=release.pipeline().nodes().getFirst();
        assertThat(node.config().path("value").decimalValue()).isEqualByComparingTo("0.900000000000000001");
        assertThat(node.config().path("large").bigIntegerValue().toString()).isEqualTo("9007199254740993");
        assertThat(node.condition().value().decimalValue()).isEqualByComparingTo("0.900000000000000002");
        assertThat(node.outputSchema().path("minimum").decimalValue()).isEqualByComparingTo("0.900000000000000003");
        assertThat(release.items().getFirst().numericPredicate().threshold()).isEqualTo("0.900000000000000004");
        var action=release.actionPolicy().actions().getFirst();
        assertThat(action.condition().value().decimalValue()).isEqualByComparingTo("0.900000000000000005");
        assertThat(action.configuration().path("threshold").decimalValue()).isEqualByComparingTo("0.900000000000000006");
    }
    @Test void createPreservesEveryNestedExactNumber() throws Exception {
        when(assets.create(anyString(),anyString(),any())).thenReturn(receipt());
        mvc().perform(post("/api/iqc/pipelines").contentType("application/json").content(body("\"0.900000000000000004\"")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("000000"));
        var value=org.mockito.ArgumentCaptor.forClass(PipelineRelease.class);
        verify(assets).create(eq("pipeline"),eq("Pipeline"),value.capture());exact(value.getValue());
    }
    @Test void updatePreservesEveryNestedExactNumber() throws Exception {
        when(assets.update(anyString(),anyInt(),anyString(),any())).thenReturn(receipt());
        mvc().perform(put("/api/iqc/pipelines/p").contentType("application/json").content(body("\"0.900000000000000004\"")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("000000"));
        var value=org.mockito.ArgumentCaptor.forClass(PipelineRelease.class);
        verify(assets).update(eq("p"),eq(7),eq("Pipeline"),value.capture());exact(value.getValue());
    }
    @Test void numericThresholdTokenIsRejectedBeforeService() throws Exception {
        var result=mvc().perform(post("/api/iqc/pipelines").contentType("application/json").content(body("0.900000000000000004")))
                .andReturn();
        assertThat(result.getResolvedException()).hasStackTraceContaining("Numeric threshold must be a decimal JSON string");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("\"code\":\"000000\"");
        verifyNoInteractions(assets);
    }
    private String calibrationBody(String references) {
        return body("\"0.900000000000000004\"")
                .replace("\"calibratedNodeIds\":[]", "\"confidenceCalibrationId\":\"calibration\",\"calibratedNodeIds\":[\"rule\"],\"calibrationReferences\":"+references);
    }
    private String reference(String hash) {
        return "{\"taskId\":\"source-task\",\"executionId\":\"source-execution\",\"nodeId\":\"rule\",\"contentHash\":\""+hash+"\"}";
    }
    @Test void calibrationReferenceSurvivesDefaultMvcCodecWithoutClientCoefficients() throws Exception {
        when(assets.create(anyString(),anyString(),any())).thenReturn(receipt());
        mvc().perform(post("/api/iqc/pipelines").contentType("application/json")
                .content(calibrationBody("["+reference("a".repeat(64))+"]")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("000000"));
        var value=org.mockito.ArgumentCaptor.forClass(PipelineRelease.class);
        verify(assets).create(eq("pipeline"),eq("Pipeline"),value.capture());
        assertThat(value.getValue().fusion().calibrationReferences()).containsExactly(
                new io.github.opensabre.iqc.quality.evaluation.EmpiricalConfidenceCalibration.Reference("source-task","source-execution","rule","a".repeat(64)));
        exact(value.getValue());
    }
    @Test void rejectsMalformedCalibrationReferencesBeforeCallingService() throws Exception {
        String valid=reference("a".repeat(64));
        for(String references:java.util.List.of("["+reference("A".repeat(64))+"]",
                "["+valid+","+valid+"]", "["+valid.replace("}",",\"coefficient\":0.99}")+"]",
                "["+valid.replace("\"source-task\"","123")+"]",
                "["+valid.replace("\"source-task\"","null")+"]",
                "["+valid.replace("}",",\"taskId\":\"source-task\"}")+"]")) {
            var result=mvc().perform(post("/api/iqc/pipelines").contentType("application/json")
                    .content(calibrationBody(references))).andReturn();
            assertThat(result.getResolvedException()).as(references).isNotNull();
            assertThat(result.getResponse().getContentAsString()).doesNotContain("\"code\":\"000000\"");
        }
        verifyNoInteractions(assets);
    }

}
