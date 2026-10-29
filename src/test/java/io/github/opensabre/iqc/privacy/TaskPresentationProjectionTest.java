package io.github.opensabre.iqc.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.model.Conversation;
import io.github.opensabre.iqc.result.BatchResultQueryService.ConversationResultDetail;
import io.github.opensabre.iqc.task.model.InspectionTask;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.*;

/** Task display remains useful when prompt snapshots cannot be disclosed. */
class TaskPresentationProjectionTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ResultReadProjection projection = new ResultReadProjection(json);

    @ParameterizedTest
    @MethodSource("snapshots")
    void bothDetailResponsesExposeOnlyDisplayFactsWithoutReleasingSnapshots(
            String snapshot, String expectedKind, Boolean expectedLabelOnly) {
        var task = new InspectionTask();
        task.setId("private-task-id");
        task.setRuleSnapshotJson(snapshot);
        task.setAgentSnapshotJson("{\"prompt\":\"private-agent-prompt\"}");
        var access = new SensitiveReadAccessService.Access("test", false, false, false, Instant.now());
        var single = projection.detail(Map.of("task", task), access);
        var conversation = projection.conversationDetail(new ConversationResultDetail(
                new Conversation(), task, null, List.of(), List.of()), access);

        for (var response : List.of(single, conversation)) {
            var presentation = json.valueToTree(response.get("taskPresentation"));
            assertEquals(3, presentation.size());
            assertEquals("iqc-task-presentation-v1", presentation.path("schemaVersion").asText());
            assertEquals(expectedKind, presentation.path("kind").asText());
            if (expectedLabelOnly == null) assertTrue(presentation.path("labelOnly").isNull());
            else assertEquals(expectedLabelOnly, presentation.path("labelOnly").booleanValue());
            assertFalse(presentation.toString().contains("private-"));
            var projectedTask = (InspectionTask) response.get("task");
            assertNull(projectedTask.getRuleSnapshotJson());
            assertNull(projectedTask.getAgentSnapshotJson());
        }
        assertEquals(snapshot, task.getRuleSnapshotJson());
        assertEquals("{\"prompt\":\"private-agent-prompt\"}", task.getAgentSnapshotJson());
    }

    private static Stream<Arguments> snapshots() {
        return Stream.of(
                Arguments.of("[{\"expression\":\"private-rule-text\"}]", "LEGACY", false),
                Arguments.of("{\"rules\":[]}", "LEGACY", false),
                Arguments.of("{\"pipelineSnapshot\":{\"prompt\":\"private-pipeline-prompt\"}}", "PIPELINE", false),
                Arguments.of("{\"schemeSnapshot\":{\"release\":{\"definition\":{\"items\":[],\"labels\":[{}]}}}}", "SCHEME", true),
                Arguments.of("{\"schemeSnapshot\":{\"release\":{\"definition\":{\"items\":[{}]}}}}", "SCHEME", false),
                Arguments.of("{\"schemeSnapshot\":{\"release\":{\"definition\":{\"items\":[{}],\"labels\":[{}]}}}}", "SCHEME", false),
                Arguments.of("{\"schemeSnapshot\":{\"release\":{\"definition\":{\"items\":[],\"labels\":[]}}}}", "SCHEME", null),
                Arguments.of("{\"schemeSnapshot\":{},\"pipelineSnapshot\":{}}", "UNKNOWN", null),
                Arguments.of("{\"rules\":42}", "UNKNOWN", null),
                Arguments.of("{private-invalid-json", "UNKNOWN", null),
                Arguments.of("null", "UNKNOWN", null),
                Arguments.of(null, "UNKNOWN", null));
    }
}
