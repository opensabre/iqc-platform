package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import io.github.opensabre.iqc.quality.dao.EvaluationDatasetMapper;
import io.github.opensabre.iqc.quality.dao.EvaluationDatasetVersionMapper;
import io.github.opensabre.iqc.quality.model.EvaluationDataset;
import io.github.opensabre.iqc.quality.model.EvaluationDatasetVersion;
import io.github.opensabre.iqc.shared.IqcDataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvaluationDatasetVersionTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final EvaluationDatasetMapper datasets = mock(EvaluationDatasetMapper.class);
    private final EvaluationDatasetVersionMapper versions = mock(EvaluationDatasetVersionMapper.class);
    private final GoldSampleService goldSamples = mock(GoldSampleService.class);
    private final IqcDataScope scope = mock(IqcDataScope.class);
    private final EvaluationDatasetService service = new EvaluationDatasetService(datasets, versions, goldSamples, scope, json);
    private final EvaluationDataset dataset = new EvaluationDataset();
    private final EvaluationDatasetVersion version = new EvaluationDatasetVersion();

    @BeforeEach
    void frozenReviewedMember() throws Exception {
        var gold = new GoldConversation(GoldConversation.SCHEMA, "sample", "conversation",
                List.of(new GoldConversation.Message("message", "agent", "original", 1, null)), null,
                List.of(new GoldFinding("FACT", Finding.ValueType.BOOLEAN, Finding.State.KNOWN, json.valueToTree(true), List.of())),
                "annotator", "reviewer", "candidate", List.of(), GoldConversation.Difficulty.BASIC, null);
        var snapshot = new DatasetSnapshot(DatasetSnapshot.SCHEMA, "dataset", "Gold", DatasetDraft.Type.REGRESSION, null,
                List.of(new DatasetSnapshot.Member("sample", 1, 1, PipelineAssetService.hash(json.writeValueAsString(gold)), gold)));
        version.setDatasetId("dataset"); version.setVersionNo(1); version.setSampleCount(1);
        version.setSnapshotJson(json.writeValueAsString(snapshot));
        json.readValue(version.getSnapshotJson(), DatasetSnapshot.class); // Expose fixture parsing failures before service wrapping.
        rehash();
        dataset.setId("dataset"); dataset.setStatus("ACTIVE"); dataset.setCreatedBy("owner"); dataset.setOwnerGroupId("group");
        when(datasets.selectOne(any())).thenReturn(dataset); when(datasets.selectById("dataset")).thenReturn(dataset);
        when(versions.selectOne(any())).thenReturn(version); when(scope.canView("owner", "group")).thenReturn(true);
    }
    private void rehash() { version.setContentHash(PipelineAssetService.hash(version.getSnapshotJson())); }
    @Test
    void historicalReadUsesFrozenReviewedContentDespiteDisabledDataset() {
        dataset.setStatus("DISABLED");
        assertThat(service.version("dataset", 1)).isSameAs(version);
        assertThat(service.read(version.getSnapshotJson(), DatasetSnapshot.class).members().getFirst().gold().messages().getFirst().content()).isEqualTo("original");
        verifyNoInteractions(goldSamples);
    }
    @Test
    void apiReadRequiresDatasetVisibilityAndPositiveVersion() {
        when(scope.canView("owner", "group")).thenReturn(false);
        assertThatThrownBy(() -> service.version("dataset", 1)).hasMessageContaining("无权访问");
        verifyNoInteractions(versions);
        when(scope.canView("owner", "group")).thenReturn(true);
        assertThatThrownBy(() -> service.version("dataset", 0)).hasMessageContaining("正整数");
    }
    @Test
    void activeWorkerAdmissionRejectsDisabledDatasetButHistoricalTaskReadDoesNot() {
        var hash = version.getContentHash(); dataset.setStatus("DISABLED");
        assertThatThrownBy(() -> service.versionForExecution("dataset", 1, hash, true)).hasMessageContaining("已停用");
        assertThat(service.versionForExecution("dataset", 1, hash, false)).isSameAs(version);
        verifyNoInteractions(scope, goldSamples); // Trusted task boundary has its own authorization.
    }
    @Test
    void rejectsExpectedDigestMismatchAndMissingVersion() {
        assertThatThrownBy(() -> service.versionForExecution("dataset", 1, "changed", false)).hasMessageContaining("摘要不一致");
        when(versions.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.version("dataset", 1)).hasMessageContaining("版本不存在");
    }
    @Test
    void rejectsTamperedSnapshotAndMemberEvenIfOuterDigestIsRecomputed() throws Exception {
        var original = version.getSnapshotJson();
        var snapshot = (ObjectNode) json.readTree(original); snapshot.put("name", "changed");
        version.setSnapshotJson(snapshot.toString());
        assertThatThrownBy(() -> service.version("dataset", 1)).hasMessageContaining("版本摘要");
        snapshot = (ObjectNode) json.readTree(original);
        ((ObjectNode) snapshot.at("/members/0/gold/messages/0")).put("content", "tampered");
        version.setSnapshotJson(snapshot.toString()); rehash();
        assertThatThrownBy(() -> service.version("dataset", 1)).hasMessageContaining("成员金标摘要");
    }
    @Test
    void rejectsWrongDatasetIdentityOrSampleCount() {
        version.setDatasetId("foreign");
        assertThatThrownBy(() -> service.version("dataset", 1)).hasMessageContaining("身份或样本数");
        version.setDatasetId("dataset"); version.setSampleCount(2);
        assertThatThrownBy(() -> service.version("dataset", 1)).hasMessageContaining("身份或样本数");
    }
    @Test
    void optionalContextRoundTripsThroughDraftAndApprovalWhileNonObjectsStillReject() throws Exception {
        var gold = service.read(version.getSnapshotJson(), DatasetSnapshot.class).members().getFirst().gold();
        var draft = new GoldDraft(GoldDraft.SCHEMA, gold.sourceSampleId(), gold.conversationId(), gold.messages(),
                json.nullNode(), gold.findings(), gold.annotatorId(), gold.source(), gold.tags(), gold.difficulty(), null);
        var restored = json.readValue(json.writeValueAsString(draft), GoldDraft.class);
        assertThat(restored.businessContext()).isNull();
        assertThat(json.readValue(json.writeValueAsString(restored.approve("reviewer")), GoldConversation.class).businessContext()).isNull();
        assertThatThrownBy(() -> new GoldDraft(GoldDraft.SCHEMA, gold.sourceSampleId(), gold.conversationId(), gold.messages(),
                json.valueToTree("invalid"), gold.findings(), gold.annotatorId(), gold.source(), gold.tags(), gold.difficulty(), null))
                .hasMessageContaining("context must be an object");
    }

}
