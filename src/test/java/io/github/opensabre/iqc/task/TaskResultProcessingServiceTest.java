package io.github.opensabre.iqc.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.label.LabelResolutionService;
import io.github.opensabre.iqc.task.processing.TaskResultProcessingConfiguration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static io.github.opensabre.iqc.task.processing.EngineExecutionResultSet.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TaskResultProcessingServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final LabelResolutionService labels = mock(LabelResolutionService.class);
    private final TaskResultProcessingService service = new TaskResultProcessingService(labels, mapper);

    @Test void freezesPublishedLabelDefinitionAndDefensivelyCopiesItsSnapshot() throws Exception {
        when(labels.resolve(any())).thenReturn(definition("BOOLEAN"));
        var frozen = service.freeze(configuration("BOOLEAN", "label", "engine"), plan());
        verify(labels).resolve(new LabelResolutionService.LabelSelection(null, null, List.of("label"), null));
        ((com.fasterxml.jackson.databind.node.ObjectNode) frozen.labelSnapshot()).put("schemaVersion", "changed");
        assertThat(frozen.labelSnapshot().path("schemaVersion").asText()).isEqualTo("1.0");
        assertThat(mapper.readTree(mapper.writeValueAsString(frozen)).at("/configuration/labeling/0/valueType").asText()).isEqualTo("BOOLEAN");
    }
    @Test void rejectsUnknownOrUnmappedEnginesBeforeLoadingTaxonomy() throws Exception {
        assertThatThrownBy(() -> service.freeze(configuration("BOOLEAN", "other", "engine"), plan()))
                .hasMessageContaining("目标不一致");
        assertThatThrownBy(() -> service.freeze(configuration("BOOLEAN", "label", "unknown"), plan()))
                .hasMessageContaining("未知");
        var extra = new TaskEnginePlanService.FrozenPlan(TaskEnginePlanService.FrozenPlan.SCHEMA,
                List.of(plan().engines().getFirst(), new TaskEnginePlanService.FrozenEngine(Operation.LABELING, "other", EngineType.REGEX, 2,
                        mapper.createObjectNode().put("targetId", "label"))));
        assertThatThrownBy(() -> service.freeze(configuration("BOOLEAN", "label", "engine"), extra)).hasMessageContaining("每个引擎");
        verifyNoInteractions(labels);
    }
    @Test void rejectsForgedValueTypesOrMissingPublishedValueDefinitions() throws Exception {
        when(labels.resolve(any())).thenReturn(definition("MONTH"));
        assertThatThrownBy(() -> service.freeze(configuration("BOOLEAN", "label", "engine"), plan()))
                .hasMessageContaining("已发布定义");
        when(labels.resolve(any())).thenThrow(new IllegalArgumentException("标签不可用"));
        assertThatThrownBy(() -> service.freeze(configuration("BOOLEAN", "label", "engine"), plan())).hasMessageContaining("标签不可用");
    }
    private TaskResultProcessingConfiguration configuration(String type, String target, String engine) throws Exception {
        return mapper.readValue("""
                {"schemaVersion":"iqc-task-result-processing-v1","labeling":[
                 {"targetId":"%s","valueCode":"flag","valueType":"%s","aggregation":"REQUIRE_AGREEMENT",
                  "engines":[{"engineInstanceId":"%s","onHit":true,"onMiss":false}]}]}
                """.formatted(target, type, engine), TaskResultProcessingConfiguration.class);
    }
    private TaskEnginePlanService.FrozenPlan plan() {
        return new TaskEnginePlanService.FrozenPlan(TaskEnginePlanService.FrozenPlan.SCHEMA,
                List.of(new TaskEnginePlanService.FrozenEngine(Operation.LABELING, "engine", EngineType.REGEX, 1,
                        mapper.createObjectNode().put("targetId", "label"))));
    }
    private LabelResolutionService.ResolvedSelection definition(String type) {
        return mapper.convertValue(Map.of("schemaVersion", "1.0", "ruleIds", List.of(), "labels", List.of(
                Map.of("id", "label", "versionNo", 2, "values", List.of(Map.of("valueCode", "flag", "valueType", type))))),
                LabelResolutionService.ResolvedSelection.class);
    }
}
