package io.github.opensabre.iqc.rest;

import io.github.opensabre.governance.usage.UsageCounterRecorder;
import io.github.opensabre.iqc.result.BatchResultQueryService;
import io.github.opensabre.iqc.result.HierarchicalResultService;
import io.github.opensabre.iqc.result.InspectionExecutionService;
import io.github.opensabre.webmvc.rest.RestResponseBodyAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract tests for inspection result endpoints. */
class InspectionResultControllerTest {

    @Test
    void returnsJsonNullWhenHierarchyHasNotBeenGenerated() throws Exception {
        InspectionResultController controller = new InspectionResultController(
                mock(InspectionExecutionService.class), mock(UsageCounterRecorder.class),
                mock(BatchResultQueryService.class), mock(HierarchicalResultService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RestResponseBodyAdvice())
                .build();

        mvc.perform(get("/api/iqc/tasks/task-1/conversations/conversation-1/result-hierarchy"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("000000"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }
}
