package io.github.opensabre.iqc.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.governance.usage.UsageCounterRecorder;
import io.github.opensabre.iqc.task.InspectionTaskService;
import io.github.opensabre.iqc.task.model.InspectionTask;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InspectionTaskControllerTest {
    @Test
    void exposesScopedExecutionAttemptsForTheBusinessRunSelector() throws Exception {
        var service = mock(InspectionTaskService.class);
        var controller = new InspectionTaskController(service, mock(UsageCounterRecorder.class), mock(io.github.opensabre.iqc.task.TaskWizardService.class));
        controller.executions("task-1");

        verify(service).executions("task-1");
        var method = InspectionTaskController.class.getMethod("executions", String.class);
        assertThat(method.getAnnotation(io.github.opensabre.boot.annotations.ResourcePermission.class).code())
                .isEqualTo("iqc:task:view");
    }

    @Test
    void requestWithoutAgentPassesExplicitRuleOnlyStrategyToService() throws Exception {
        var service = mock(InspectionTaskService.class);
        var usage = mock(UsageCounterRecorder.class);
        var controller = new InspectionTaskController(service, usage, mock(io.github.opensabre.iqc.task.TaskWizardService.class));
        var request = new ObjectMapper().readValue("""
                {"taskType":"BATCH","conversationIds":["c1"],"ruleIds":["r1"],
                 "executionMode":"RULE_ONLY","concurrencyLimit":1}
                """, InspectionTaskController.CreateTaskRequest.class);
        InspectionTask task = new InspectionTask(); task.setId("t1");
        when(service.createConfigured(null, "BATCH", List.of("c1"), null, null, null, null,
                null, null, List.of("r1"), 1, null, null, "RULE_ONLY", null)).thenReturn(task);
        assertThat(controller.create(request)).isSameAs(task);
        verify(usage).record(any());
    }

    @Test
    void oldPayloadDoesNotAcquireImplicitNewMode() throws Exception {
        var request = new ObjectMapper().readValue("{\"taskType\":\"BATCH\",\"agentId\":\"a1\"}",
                InspectionTaskController.CreateTaskRequest.class);
        assertThat(request.executionMode()).isNull();
        assertThat(request.agentId()).isEqualTo("a1");
    }
    @Test
    void prototypePayloadUsesWizardCreationInsteadOfLegacyRuleLabelPath() throws Exception {
        var legacy = mock(InspectionTaskService.class);
        var wizard = mock(io.github.opensabre.iqc.task.TaskWizardService.class);
        var controller = new InspectionTaskController(legacy, mock(UsageCounterRecorder.class), wizard);
        var request = new ObjectMapper().readValue("""
                {"name":"新任务","dataSelection":{"mode":"BATCH","conversationIds":["c1"]},
                 "processingConfiguration":{"schemaVersion":"iqc-task-processing-v1","labeling":{"engines":[
                  {"id":"engine","type":"REGEX","parameters":{"targetId":"label","ruleId":"rule"}}]}},
                 "resultProcessing":{"schemaVersion":"iqc-task-result-processing-v1","labeling":[
                  {"targetId":"label","valueCode":"flag","valueType":"BOOLEAN","aggregation":"REQUIRE_AGREEMENT",
                   "engines":[{"engineInstanceId":"engine","onHit":true,"onMiss":false}]}]}}
                """, InspectionTaskController.CreateTaskRequest.class);
        var created = new InspectionTask(); created.setId("new-task");
        when(wizard.create(any(), any(), any(), any(), any())).thenReturn(created);
        assertThat(controller.create(request)).isSameAs(created);
        verifyNoInteractions(legacy);
        verify(wizard).create("新任务", request.dataSelection(), request.processingConfiguration(), request.resultProcessing(), null);
        var mixed = new ObjectMapper().readValue("""
                {"taskType":"BATCH","processingConfiguration":{"schemaVersion":"iqc-task-processing-v1",
                 "inspection":{"engines":[{"id":"engine","type":"REGEX","parameters":{}}]}}}
                """, InspectionTaskController.CreateTaskRequest.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.create(mixed)).hasMessageContaining("不能混用");
    }
}
