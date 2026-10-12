package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import io.github.opensabre.iqc.quality.model.EvaluationDatasetVersion;
import io.github.opensabre.iqc.task.model.InspectionTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PipelineEvaluationAdmissionTest {
    private final ObjectMapper json = new ObjectMapper();
    private final EvaluationDatasetService datasets = mock(EvaluationDatasetService.class);
    private final PipelineEvaluationService service = new PipelineEvaluationService(datasets, null, null,
            null, null, null, null, null, json, null);
    private final InspectionTask task = new InspectionTask();
    private ObjectNode snapshot;

    @BeforeEach
    void frozenInputs() throws Exception {
        var gold = new GoldConversation(GoldConversation.SCHEMA, "sample", "conversation",
                List.of(new GoldConversation.Message("message", "客户", "frozen text", 1, null,86400001L,86400500L,new java.math.BigDecimal("0.75"))), null,
                List.of(new GoldFinding("FACT", Finding.ValueType.BOOLEAN, Finding.State.KNOWN,
                        json.valueToTree(true), List.of())), "annotator", "reviewer", "candidate", List.of(),
                GoldConversation.Difficulty.BASIC, null);
        var dataset = new DatasetSnapshot(DatasetSnapshot.SCHEMA, "dataset", "Gold", DatasetDraft.Type.REGRESSION, null,
                List.of(new DatasetSnapshot.Member("sample", 1, 1, "a".repeat(64), gold)));
        var version = new EvaluationDatasetVersion(); version.setSnapshotJson("frozen-dataset"); version.setContentHash("dataset-hash");
        when(datasets.versionForExecution("dataset", 1, "dataset-hash", false)).thenReturn(version);
        when(datasets.read("frozen-dataset", DatasetSnapshot.class)).thenReturn(dataset);
        snapshot = (ObjectNode) json.readTree("""
                {"executionStrategy":{"schemaVersion":"iqc-pipeline-task-v1","mode":"PIPELINE"},
                 "evaluationSnapshot":{"schemaVersion":"iqc-pipeline-evaluation-task-v1","datasetId":"dataset",
                  "versionNo":1,"contentHash":"dataset-hash","sampleCount":1},
                 "pipelineSnapshot":{"release":{"schemaVersion":"iqc-pipeline-release-v1",
                  "pipeline":{"schemaVersion":"iqc-pipeline-v1","nodes":[{"nodeId":"node","type":"RULE","version":"1",
                   "config":{"findingDefinition":{"schemaVersion":"iqc-finding-definition-v1","code":"FACT",
                    "name":"Fact","category":"risk","valueType":"BOOLEAN","defaultSeverity":"LOW"}}}]},
                  "fusion":{"id":"fusion","version":1,"method":"PRIORITY"},
                  "items":[{"itemCode":"item","findingCode":"FACT","hitMeaning":"VIOLATION"}],
                  "scoring":{"version":"iqc-score-v2","mode":"DEDUCTION","baseScore":100,"passingScore":80,
                   "items":[{"itemCode":"item","points":20,"veto":false}]}}}}
                """);
        digest(); task.setTaskType("PIPELINE_EVALUATION"); task.setConversationIdsJson("[\"conversation\"]"); task.setTotalMessages(1);
    }
    private void digest() {
        ((ObjectNode) snapshot.path("pipelineSnapshot")).put("contentHash", PipelineAssetService.hash(snapshot.at("/pipelineSnapshot/release").toString()));
    }
    private PipelineEvaluationService.Prepared prepare() { return service.prepare(task, snapshot, false); }
    @Test
    void usesFrozenDatasetAndCanonicalRolesWithoutLoadingLiveConversations() {
        var prepared = prepare();
        assertThat(prepared.datasetHash()).isEqualTo("dataset-hash");
        assertThat(prepared.messages().get("conversation").getFirst().getContent()).isEqualTo("frozen text");
        assertThat(prepared.messages().get("conversation").getFirst().getSpeakerRole()).isEqualTo("user");
        assertThat(prepared.messages().get("conversation").getFirst().getStartMs()).isEqualTo(86400001L);
        assertThat(prepared.messages().get("conversation").getFirst().getEndMs()).isEqualTo(86400500L);
        assertThat(prepared.messages().get("conversation").getFirst().getConfidence()).isEqualByComparingTo("0.75");
        assertThat(prepared.dataset().members().getFirst().gold().messages().getFirst().role()).isEqualTo("客户");
        assertThatThrownBy(() -> prepared.messages().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test
    void rejectsWrongConversationManifestMessageCountAndFrozenSampleCount() {
        task.setConversationIdsJson("[\"other\"]");
        assertThatThrownBy(this::prepare).hasMessageContaining("清单或消息数");
        task.setConversationIdsJson("[\"conversation\"]"); task.setTotalMessages(2);
        assertThatThrownBy(this::prepare).hasMessageContaining("清单或消息数");
        task.setTotalMessages(1); ((ObjectNode) snapshot.path("evaluationSnapshot")).put("sampleCount", 2);
        assertThatThrownBy(this::prepare).hasMessageContaining("样本数");
    }
    @Test
    void rejectsChangedReleaseDigestAndIncompatibleFindingType() {
        ((ObjectNode) snapshot.path("pipelineSnapshot")).put("contentHash", "changed");
        assertThatThrownBy(this::prepare).hasMessageContaining("流水线摘要");
        ((ObjectNode) snapshot.at("/pipelineSnapshot/release/pipeline/nodes/0/config/findingDefinition")).put("valueType", "STRING"); digest();
        assertThatThrownBy(this::prepare).hasMessageContaining("金标类型");
    }
    @Test
    void rejectsProductionModeOrMixedSchemeSnapshot() {
        task.setTaskType("PIPELINE");
        assertThatThrownBy(this::prepare).hasMessageContaining("任务类型");
        task.setTaskType("PIPELINE_EVALUATION"); snapshot.putObject("schemeSnapshot");
        assertThatThrownBy(this::prepare).hasMessageContaining("任务类型");
    }
}
