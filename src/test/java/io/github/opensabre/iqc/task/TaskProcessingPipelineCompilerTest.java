package io.github.opensabre.iqc.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.pipeline.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class TaskProcessingPipelineCompilerTest {
    private final ObjectMapper json = new ObjectMapper();
    private TaskProcessingConfiguration.Engine engine(String id) {
        return new TaskProcessingConfiguration.Engine(id, TaskProcessingConfiguration.EngineType.REGEX, json.createObjectNode());
    }
    private PipelineDefinition.Node frozen(TaskProcessingConfiguration.Engine engine) {
        return new PipelineDefinition.Node(engine.id(), "RULE", engine.id(), "1", List.of(), Map.of(),
                engine.parameters(), null, 5000, 0, PipelineDefinition.OnError.FAIL_PIPELINE, null, null, true);
    }
    @Test void runsLaterEnginesAfterFailureAndRetainsOrderedFailureTrace() {
        var processing = new TaskProcessingConfiguration.Processing(List.of(engine("first"), engine("broken"), engine("last")));
        var invoked = new ArrayList<String>();
        var adapter = new EngineAdapter() {
            public String type() { return "RULE"; }
            public NodeResult execute(NodeContext context) {
                invoked.add(context.node().nodeId());
                if (context.node().nodeId().equals("broken")) throw new IllegalStateException("engine failed");
                return new NodeResult(NodeResult.Status.SUCCESS, List.of(), Map.of(), Map.of(), null);
            }
        };
        try (var runtime = new PipelineRuntime(List.of(adapter), 1, 8)) {
            var run = runtime.execute(TaskProcessingPipelineCompiler.compile(processing, this::frozen),
                    json.createObjectNode(), Map.of(), PipelineRuntime.Mode.PRODUCTION);
            assertThat(invoked).containsExactly("first", "broken", "last");
            assertThat(run.status()).isEqualTo(PipelineRuntime.Status.PARTIAL_SUCCESS);
            assertThat(run.trace()).extracting(t -> t.result().status())
                    .containsExactly(NodeResult.Status.SUCCESS, NodeResult.Status.ERROR, NodeResult.Status.SUCCESS);
            assertThat(run.trace().get(1).result().error()).isNotBlank();
        }
    }
    @Test void rejectsResolverChangingEngineIdentityOrType() {
        var processing = new TaskProcessingConfiguration.Processing(List.of(engine("first")));
        assertThatThrownBy(() -> TaskProcessingPipelineCompiler.compile(processing, e -> frozen(engine("other"))))
                .hasMessageContaining("identity");
        assertThatThrownBy(() -> TaskProcessingPipelineCompiler.compile(processing, e -> new PipelineDefinition.Node(
                e.id(), "LLM", e.id(), "1", List.of(), Map.of(), e.parameters(), null, 1000, 0,
                PipelineDefinition.OnError.CONTINUE, null, null, true))).hasMessageContaining("type");
    }
}
