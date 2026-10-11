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
    private io.github.opensabre.iqc.task.model.InspectionTask frozenTask(List<ConversationMessage> messages) {
        var task=new io.github.opensabre.iqc.task.model.InspectionTask();task.setId("task");task.setTaskType("PIPELINE");
        task.setStatus("SUCCEEDED");task.setConversationIdsJson("[\"conversation\"]");task.setTotalMessages(messages.size());
        var values=messages.stream().map(m->new io.github.opensabre.iqc.conversation.ProductionInputSnapshotService.Message(
                m.getId(),m.getSequenceNo(),m.getSpeakerRole(),m.getContent(),m.getRelativeTime())).toList();
        var input=new io.github.opensabre.iqc.conversation.ProductionInputSnapshotService.Input("conversation","fingerprint",
                json.createObjectNode().put("businessType","support"),values);
        var body=json.valueToTree(List.of(input));var root=json.createObjectNode();root.putObject("executionStrategy").put("mode","PIPELINE");
        root.putObject("productionInputs").put("schemaVersion","iqc-production-inputs-v1")
                .put("hashAlgorithm","selector-decimal-sha256-v1").put("contentHash",PipelineAssetService.hash(body.toString())).set("conversations",body);
        task.setRuleSnapshotJson(root.toString());return task;
    }
    private String frozenTrace(io.github.opensabre.iqc.task.model.InspectionTask task) throws Exception {
        var prepared=new io.github.opensabre.iqc.conversation.ProductionInputSnapshotService(null,null,null,json).prepare(task,json.readTree(task.getRuleSnapshotJson()));
        var evaluator=new io.github.opensabre.iqc.pipelinebridge.PipelineConversationEvaluator(null,json,null);
        var input=evaluator.executionInput(prepared.messages().get("conversation"),prepared.businessContext("conversation"));
        return json.createObjectNode().put("schemaVersion","iqc-pipeline-execution-v1").put("inputHash",PipelineAssetService.hash(input.toString())).toString();
    }
    @Test void verifiesPipelineExecutionWithFrozenBusinessContext() throws Exception {
        var messages=List.of(message("m1",1,"坐席"));var task=frozenTask(messages);String trace=frozenTrace(task);
        messages.getFirst().setRawLine("archive metadata");messages.getFirst().setLineNumber(12);messages.getFirst().setCreatedBy("importer");
        var verified=ReviewSampleInputValidator.validateInput(trace,messages,task,"conversation",json);
        assertThat(verified.executionInputHash()).isEqualTo(jsonReadHash(trace));
        assertThat(verified.businessContext().path("businessType").asText()).isEqualTo("support");
        assertThat(messages.getFirst().getSpeakerRole()).isEqualTo("坐席");
    }
    @Test void frozenExecutionStillRejectsEditedTranscript() throws Exception {
        var messages=List.of(message("m1",1,"agent"));var task=frozenTask(messages);String trace=frozenTrace(task);
        messages.getFirst().setContent("edited");
        assertThatThrownBy(()->ReviewSampleInputValidator.validateInput(trace,messages,task,"conversation",json)).hasMessageContaining("原执行输入一致");
    }
    @Test void frozenContextCannotBeChangedEvenWithUpdatedEnvelopeHash() throws Exception {
        var messages=List.of(message("m1",1,"agent"));var task=frozenTask(messages);String trace=frozenTrace(task);
        var root=json.readTree(task.getRuleSnapshotJson());var body=root.path("productionInputs").path("conversations");
        ((com.fasterxml.jackson.databind.node.ObjectNode)body.get(0).path("businessContext")).put("businessType","changed");
        ((com.fasterxml.jackson.databind.node.ObjectNode)root.path("productionInputs")).put("contentHash",PipelineAssetService.hash(body.toString()));
        task.setRuleSnapshotJson(root.toString());
        assertThatThrownBy(()->ReviewSampleInputValidator.validateInput(trace,messages,task,"conversation",json)).hasMessageContaining("原执行输入一致");
    }
    @Test void frozenInputRejectsForeignConversationAndBrokenEnvelope() throws Exception {
        var messages=List.of(message("m1",1,"agent"));var task=frozenTask(messages);String trace=frozenTrace(task);
        assertThatThrownBy(()->ReviewSampleInputValidator.validateInput(trace,messages,task,"foreign",json)).hasMessageContaining("原执行输入一致");
        task.setRuleSnapshotJson(task.getRuleSnapshotJson().replace("support","tampered"));
        assertThatThrownBy(()->ReviewSampleInputValidator.validateInput(trace,messages,task,"conversation",json)).hasMessageContaining("原执行输入一致");
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
