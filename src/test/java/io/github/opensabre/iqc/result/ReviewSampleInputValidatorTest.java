package io.github.opensabre.iqc.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ReviewSampleInputValidatorTest {
    private final ObjectMapper json = new ObjectMapper();
    private ConversationMessage message(String id, int sequence, String role) {
        var message = new ConversationMessage(); message.setId(id); message.setConversationId("conversation");
        message.setSequenceNo(sequence); message.setSpeakerRole(role); message.setContent("原始消息 " + sequence);
        return message;
    }
    private String trace(List<ConversationMessage> messages, String schema) {
        String hash;
        if (schema.equals("iqc-route-execution-trace-v1")) hash = RouteExecutionTrace.inputHash(messages);
        else {
            var input = json.createObjectNode(); input.set("messages",json.valueToTree(messages));
            hash = PipelineAssetService.hash(input.toString());
        }
        return json.createObjectNode().put("schemaVersion",schema).put("inputHash",hash).toString();
    }
    @ParameterizedTest
    @ValueSource(strings={"iqc-route-execution-trace-v1","iqc-pipeline-execution-v1"})
    void verifiesProductionNormalizedRolesWithoutMutatingArchivedInput(String schema) {
        var stored = List.of(message("m1",1,"坐席"),message("m2",2,"客户"));
        var executed = List.of(message("m1",1,"agent"),message("m2",2,"user"));
        var trace = trace(executed,schema);
        assertThat(ReviewSampleInputValidator.validate(trace,stored,json)).isEqualTo(jsonReadHash(trace));
        assertThat(stored.getFirst().getSpeakerRole()).isEqualTo("坐席");
        assertThat(stored.getLast().getSpeakerRole()).isEqualTo("客户");
        stored.getFirst().setContent("已编辑");
        assertThatThrownBy(() -> ReviewSampleInputValidator.validate(trace,stored,json)).hasMessageContaining("原执行输入一致");
    }
    private String jsonReadHash(String trace) {
        try { return json.readTree(trace).path("inputHash").asText(); } catch(Exception error) { throw new AssertionError(error); }
    }
    @ParameterizedTest
    @ValueSource(strings={"iqc-route-execution-trace-v1","iqc-pipeline-execution-v1"})
    void rejectsMissingAndReorderedMessages(String schema) {
        var messages=List.of(message("m1",1,"agent"),message("m2",2,"user"));
        var trace=trace(messages,schema);
        assertThatThrownBy(() -> ReviewSampleInputValidator.validate(trace,List.of(messages.getFirst()),json)).hasMessageContaining("原执行输入一致");
        assertThatThrownBy(() -> ReviewSampleInputValidator.validate(trace,List.of(messages.getLast(),messages.getFirst()),json)).hasMessageContaining("原执行输入一致");
    }
    @Test
    void neverGuessesUnknownSpeakerAsCustomer() {
        var executed=List.of(message("m1",1,"user"));
        var archived=List.of(message("m1",1,"unknown"));
        assertThatThrownBy(() -> ReviewSampleInputValidator.validate(trace(executed,"iqc-pipeline-execution-v1"),archived,json))
                .hasMessageContaining("原执行输入一致");
    }
    @Test
    void rejectsMissingMalformedAndUnsupportedTrace() {
        var messages=List.of(message("m1",1,"agent"));
        for(String trace: new String[]{null,"{","{}","{\"schemaVersion\":\"future\",\"inputHash\":\"anything\"}"})
            assertThatThrownBy(() -> ReviewSampleInputValidator.validate(trace,messages,json)).hasMessageContaining("原执行输入一致");
    }
}
