package io.github.opensabre.iqc.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class TaskProcessingConfigurationTest {
    private final ObjectMapper json = new ObjectMapper();
    private TaskProcessingConfiguration.Engine engine(String id, TaskProcessingConfiguration.EngineType type, String prompt) {
        return new TaskProcessingConfiguration.Engine(id, type, json.createObjectNode().put("prompt", prompt));
    }
    private TaskProcessingConfiguration.Processing processing(TaskProcessingConfiguration.Engine... engines) {
        return new TaskProcessingConfiguration.Processing(Arrays.asList(engines));
    }

    @Test void supportsInspectionLabelingAndBothWithoutSharingParameters() throws Exception {
        var inspection = processing(engine("model", TaskProcessingConfiguration.EngineType.LLM, "检查服务规范"));
        var labeling = processing(engine("model", TaskProcessingConfiguration.EngineType.LLM, "识别业务标签"));
        for (var config : List.of(
                new TaskProcessingConfiguration(TaskProcessingConfiguration.SCHEMA, inspection, null),
                new TaskProcessingConfiguration(TaskProcessingConfiguration.SCHEMA, null, labeling),
                new TaskProcessingConfiguration(TaskProcessingConfiguration.SCHEMA, inspection, labeling))) {
            assertThat(json.readValue(json.writeValueAsString(config), TaskProcessingConfiguration.class)).isEqualTo(config);
        }
        assertThat(inspection.engines().getFirst().parameters().path("prompt").asText()).isEqualTo("检查服务规范");
        assertThat(labeling.engines().getFirst().parameters().path("prompt").asText()).isEqualTo("识别业务标签");
    }

    @Test void preservesDifferentOrdersAcrossSerializationAndDefendsAgainstMutation() throws Exception {
        var params = json.createObjectNode().put("expression", "hello");
        var regex = new TaskProcessingConfiguration.Engine("regex", TaskProcessingConfiguration.EngineType.REGEX, params);
        var llm = engine("llm", TaskProcessingConfiguration.EngineType.LLM, "检查");
        var list = new ArrayList<>(List.of(regex, llm));
        var config = new TaskProcessingConfiguration(TaskProcessingConfiguration.SCHEMA,
                new TaskProcessingConfiguration.Processing(list), processing(llm, regex));
        list.clear(); params.put("expression", "changed");
        ((ObjectNode) regex.parameters()).put("expression", "also changed");
        var restored = json.readValue(json.writeValueAsString(config), TaskProcessingConfiguration.class);
        assertThat(restored.inspection().engines()).extracting(TaskProcessingConfiguration.Engine::id).containsExactly("regex", "llm");
        assertThat(restored.labeling().engines()).extracting(TaskProcessingConfiguration.Engine::id).containsExactly("llm", "regex");
        assertThat(restored.inspection().engines().getFirst().parameters().path("expression").asText()).isEqualTo("hello");
    }

    @Test void rejectsMissingBranchesEmptyListsDuplicateIdsAndUnknownVersions() {
        assertThatThrownBy(() -> new TaskProcessingConfiguration(TaskProcessingConfiguration.SCHEMA, null, null)).hasMessageContaining("至少");
        assertThatThrownBy(() -> processing()).hasMessageContaining("1 到 32");
        var model = engine("same", TaskProcessingConfiguration.EngineType.LLM, "test");
        assertThatThrownBy(() -> processing(model, model)).hasMessageContaining("重复");
        assertThatThrownBy(() -> processing((TaskProcessingConfiguration.Engine) null)).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> new TaskProcessingConfiguration("future", processing(model), null)).hasMessageContaining("版本");
    }

    @Test void rejectsMalformedEngineParametersAndDeferredSmallModels() {
        assertThatThrownBy(() -> new TaskProcessingConfiguration.Engine("x", null, json.createObjectNode())).hasMessageContaining("类型");
        assertThatThrownBy(() -> new TaskProcessingConfiguration.Engine("x", TaskProcessingConfiguration.EngineType.REGEX, json.createArrayNode())).hasMessageContaining("对象");
        assertThatThrownBy(() -> json.readValue("{\"id\":\"x\",\"type\":\"SMALL_MODEL\",\"parameters\":{}}", TaskProcessingConfiguration.Engine.class)).hasMessageContaining("SMALL_MODEL");
    }
}
