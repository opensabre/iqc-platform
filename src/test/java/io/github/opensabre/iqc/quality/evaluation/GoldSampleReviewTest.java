package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.quality.dao.QualitySampleMapper;
import io.github.opensabre.iqc.quality.dao.QualitySampleVersionMapper;
import io.github.opensabre.iqc.quality.model.QualitySample;
import io.github.opensabre.iqc.quality.model.QualitySampleVersion;
import io.github.opensabre.iqc.shared.IqcDataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoldSampleReviewTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final QualitySampleMapper samples = mock(QualitySampleMapper.class);
    private final QualitySampleVersionMapper versions = mock(QualitySampleVersionMapper.class);
    private final IqcDataScope scope = mock(IqcDataScope.class);
    private final GoldSampleService service = new GoldSampleService(samples, versions, null, null, scope, json);
    private final QualitySample sample = new QualitySample();
    @BeforeEach
    void pendingDraft() throws Exception {
        sample.setId("sample"); sample.setConversationId("conversation"); sample.setStatus("CANDIDATE");
        sample.setGoldDraftRevision(1); sample.setGoldStatus("PENDING_REVIEW");
        sample.setCreatedBy("owner"); sample.setOwnerGroupId("group");
        var draft = new GoldDraft(GoldDraft.SCHEMA, "sample", "conversation",
                List.of(new GoldConversation.Message("message", "agent", "frozen", 1, null)), null,
                List.of(new GoldFinding("FACT", Finding.ValueType.BOOLEAN, Finding.State.KNOWN, json.valueToTree(true), List.of())),
                "annotator", "candidate", List.of(), GoldConversation.Difficulty.BASIC, null);
        sample.setGoldDraftJson(json.writeValueAsString(draft));
        when(samples.selectOne(any())).thenReturn(sample); when(scope.canView("owner", "group")).thenReturn(true);
        when(scope.owner()).thenReturn("reviewer");
    }
    @Test
    void exactAnnotationSurvivesApprovalDatasetFreezeAndHistoricalRoundTrip() throws Exception {
        annotation(json.nullNode()); // Establish the frozen candidate lineage through the existing fixture.
        String decimal = "0.12345678901234567890123456789";
        String requestJson = """
                {"expectedRevision":1,"findings":[
                  {"code":"NUMBER_FACT","valueType":"NUMBER","state":"KNOWN","value":%s,"evidenceMessageIds":["message"]},
                  {"code":"OBJECT_FACT","valueType":"OBJECT","state":"KNOWN","value":{"nested":[%s,9007199254740993]},"evidenceMessageIds":[]}],
                 "businessContext":{"amount":%s},"tags":[],"difficulty":"BASIC",
                 "decision":{"score":99.1234567890123456789,"scoreStatus":"FINAL","labels":{"VALUE":{"nested":%s}},"risk":"LOW"}}
                """.formatted(decimal,decimal,decimal,decimal);
        var request = json.readValue(requestJson, GoldSampleService.Annotation.class);
        assertThat(request.findings().getFirst().value().decimalValue()).isEqualByComparingTo(decimal);
        assertThat(request.findings().get(1).value().at("/nested/1").bigIntegerValue().toString()).isEqualTo("9007199254740993");
        assertThat(request.businessContext().get("amount").decimalValue()).isEqualByComparingTo(decimal);
        assertThat(request.decision().labels().get("VALUE").get("nested").decimalValue()).isEqualByComparingTo(decimal);
        when(scope.owner()).thenReturn("annotator");
        service.annotate("sample",request);
        String savedDraft=sample.getGoldDraftJson();
        when(scope.owner()).thenReturn("reviewer");
        var approved=service.review("sample",2,true,"exact values checked");
        when(versions.selectOne(any())).thenReturn(approved);
        var gold=service.read(approved.getSnapshotJson(),GoldConversation.class);
        assertThat(gold.findings().getFirst().value().decimalValue()).isEqualByComparingTo(decimal);
        assertThat(gold.businessContext().at("/annotation/amount").decimalValue()).isEqualByComparingTo(decimal);
        assertThat(gold.decision().labels().get("VALUE").get("nested").decimalValue()).isEqualByComparingTo(decimal);
        assertThat(json.writeValueAsString(gold)).isEqualTo(approved.getSnapshotJson());
        assertThat(sample.getGoldDraftJson()).isEqualTo(savedDraft);
        var datasetMapper=mock(io.github.opensabre.iqc.quality.dao.EvaluationDatasetMapper.class);
        var datasetVersions=mock(io.github.opensabre.iqc.quality.dao.EvaluationDatasetVersionMapper.class);
        var dataset=new io.github.opensabre.iqc.quality.model.EvaluationDataset();
        dataset.setId("dataset");dataset.setName("Exact gold");dataset.setStatus("ACTIVE");
        dataset.setCreatedBy("owner");dataset.setOwnerGroupId("group");dataset.setDraftRevision(1);
        dataset.setDraftConfigJson(json.writeValueAsString(new DatasetDraft(DatasetDraft.SCHEMA,DatasetDraft.Type.REGRESSION,null,
                List.of(new DatasetDraft.Member("sample",1)))));
        when(datasetMapper.selectOne(any())).thenReturn(dataset);
        var datasets=new EvaluationDatasetService(datasetMapper,datasetVersions,service,scope,json);
        var frozen=datasets.freeze("dataset",1);
        var snapshot=datasets.read(frozen.getSnapshotJson(),DatasetSnapshot.class);
        assertThat(snapshot.members().getFirst().gold().findings().getFirst().value().decimalValue()).isEqualByComparingTo(decimal);
        assertThat(io.github.opensabre.iqc.pipelineasset.PipelineAssetService.hash(json.writeValueAsString(snapshot.members().getFirst().gold())))
                .isEqualTo(approved.getContentHash());
        assertThat(json.writeValueAsString(snapshot)).isEqualTo(frozen.getSnapshotJson());
        assertThat(io.github.opensabre.iqc.pipelineasset.PipelineAssetService.hash(frozen.getSnapshotJson())).isEqualTo(frozen.getContentHash());
    }

    @Test
    void frozenCandidateMetadataKeepsItsExactNumbersDuringAnnotation() throws Exception {
        var request=annotation(json.nullNode());
        String decimal="0.12345678901234567890123456789";
        String input=sample.getContentSnapshot();
        input=input.substring(0,input.length()-1)+",\"businessContext\":{\"amount\":"+decimal+",\"count\":9007199254740993}}";
        sample.setContentSnapshot(input);
        var lineage=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(sample.getExpectedJson());
        lineage.put("inputHash",io.github.opensabre.iqc.pipelineasset.PipelineAssetService.hash(input));
        sample.setExpectedJson(lineage.toString());
        service.annotate("sample",request);
        var draft=service.read(sample.getGoldDraftJson(),GoldDraft.class);
        assertThat(draft.businessContext().at("/conversation/businessContext/amount").decimalValue()).isEqualByComparingTo(decimal);
        assertThat(draft.businessContext().at("/conversation/businessContext/count").bigIntegerValue().toString()).isEqualTo("9007199254740993");
        assertThat(sample.getContentSnapshot()).isEqualTo(input);
    }

    @Test
    void independentApprovalCreatesFrozenVersionAndIdenticalRetryReturnsIt() {
        var version = service.review("sample", 1, true, "checked");
        assertThat(version.getDecision()).isEqualTo("APPROVED");
        assertThat(version.getVersionNo()).isEqualTo(1); assertThat(sample.getGoldStatus()).isEqualTo("APPROVED");
        var gold = service.read(version.getSnapshotJson(), GoldConversation.class);
        assertThat(gold.annotatorId()).isEqualTo("annotator"); assertThat(gold.reviewerId()).isEqualTo("reviewer");
        when(versions.selectOne(any())).thenReturn(version);
        assertThat(service.review("sample", 1, true, "checked")).isSameAs(version);
        assertThat(service.approvedVersionForFreeze("sample", 1)).isSameAs(version);
        verify(versions, times(1)).insert(any(QualitySampleVersion.class));
    }
    @Test
    void rejectionCannotBeChangedIntoApprovalOrAdmittedToDataset() {
        var version = service.review("sample", 1, false, "fix annotation");
        when(versions.selectOne(any())).thenReturn(version);
        assertThat(service.review("sample", 1, false, "fix annotation")).isSameAs(version);
        assertThatThrownBy(() -> service.review("sample", 1, true, "checked")).hasMessageContaining("重新标注");
        assertThatThrownBy(() -> service.approvedVersion("sample", 1)).hasMessageContaining("已审核金标");
        assertThat(service.version("sample", 1)).isSameAs(version);
    }
    @Test
    void rejectsSelfReviewUnidentifiedActorAndMissingReason() {
        when(scope.owner()).thenReturn("annotator");
        assertThatThrownBy(() -> service.review("sample", 1, true, "checked")).hasMessageContaining("必须不同");
        when(scope.owner()).thenReturn("system");
        assertThatThrownBy(() -> service.review("sample", 1, true, "checked")).hasMessageContaining("登录用户");
        when(scope.owner()).thenReturn("reviewer");
        assertThatThrownBy(() -> service.review("sample", 1, true, " ")).hasMessageContaining("原因");
        verify(versions, never()).insert(any(QualitySampleVersion.class));
    }
    @Test
    void staleRevisionAndInvisibleSampleCannotBeReviewed() {
        assertThatThrownBy(() -> service.review("sample", 0, true, "checked")).hasMessageContaining("修订已变化");
        when(scope.canView("owner", "group")).thenReturn(false);
        assertThatThrownBy(() -> service.review("sample", 1, true, "checked")).hasMessageContaining("无权访问");
        verifyNoInteractions(versions);
    }
    @Test
    void disabledSampleAndCorruptedApprovalCannotEnterNewDataset() {
        var version = service.review("sample", 1, true, "checked");
        when(versions.selectOne(any())).thenReturn(version); sample.setStatus("DISABLED");
        assertThatThrownBy(() -> service.approvedVersion("sample", 1)).hasMessageContaining("停用样本");
        assertThat(service.version("sample", 1)).isSameAs(version);
        sample.setStatus("CANDIDATE"); version.setContentHash("changed");
        assertThatThrownBy(() -> service.approvedVersion("sample", 1)).hasMessageContaining("来源或摘要");
    }
    private GoldSampleService.Annotation annotation(com.fasterxml.jackson.databind.JsonNode context) throws Exception {
        var input = json.createObjectNode().put("schemaVersion", "iqc-conversation-candidate-input-v1").put("conversationId", "conversation");
        input.putArray("segments").addObject().put("id", "message").put("speakerRole", "agent").put("content", "frozen").put("sequenceNo", 1);
        sample.setSourceResultId("result"); sample.setContentSnapshot(input.toString());
        sample.setExpectedJson(json.createObjectNode().put("schemaVersion", "iqc-reviewed-business-candidate-v1")
                .put("sourceResultId", "result").put("inputHash", io.github.opensabre.iqc.pipelineasset.PipelineAssetService.hash(input.toString())).toString());
        var original = service.read(sample.getGoldDraftJson(), GoldDraft.class);
        return new GoldSampleService.Annotation(1, original.findings(), context, List.of(), original.difficulty(), null);
    }
    @Test
    void explicitJsonNullContextIsEquivalentToOmittedOptionalContext() throws Exception {
        var request = annotation(json.nullNode());
        service.annotate("sample", request);
        var draft = service.read(sample.getGoldDraftJson(), GoldDraft.class);
        assertThat(draft.businessContext().has("annotation")).isFalse();
        assertThat(draft.messages().getFirst().content()).isEqualTo("frozen");
        assertThat(sample.getGoldStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(sample.getGoldDraftRevision()).isEqualTo(2);
        verifyNoInteractions(versions);
    }
    @Test
    void frozenCandidateAnnotationPreservesLineageAndRejectsChangedTranscript() throws Exception {
        var request = annotation(json.createObjectNode().put("campaign", "sale"));
        service.annotate("sample", request);
        var draft = service.read(sample.getGoldDraftJson(), GoldDraft.class);
        assertThat(draft.businessContext().at("/annotation/campaign").asText()).isEqualTo("sale");
        assertThat(draft.businessContext().at("/candidateLineage/sourceResultId").asText()).isEqualTo("result");
        sample.setGoldDraftRevision(1); sample.setContentSnapshot(sample.getContentSnapshot().replace("frozen", "changed"));
        assertThatThrownBy(() -> service.annotate("sample", request)).hasMessageContaining("来源摘要无效");
        var malformed = annotation(json.valueToTree("wrong"));
        assertThatThrownBy(() -> service.annotate("sample", malformed)).hasMessageContaining("必须是对象");
    }

    @Test void alignedCandidateAnnotationPreservesFrozenCoordinates() throws Exception {
        var request=annotation(json.nullNode());
        var input=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(sample.getContentSnapshot());
        input.put("schemaVersion","iqc-conversation-candidate-input-v2");
        var segment=(com.fasterxml.jackson.databind.node.ObjectNode)input.path("segments").get(0);
        segment.put("startMs",86400001L).put("endMs",86400500L).put("confidence",new java.math.BigDecimal("0.75"));
        sample.setContentSnapshot(input.toString());
        var lineage=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(sample.getExpectedJson());
        lineage.put("inputHash",io.github.opensabre.iqc.pipelineasset.PipelineAssetService.hash(input.toString()));
        sample.setExpectedJson(lineage.toString());
        service.annotate("sample",request);
        var message=service.read(sample.getGoldDraftJson(),GoldDraft.class).messages().getFirst();
        assertThat(message.startMs()).isEqualTo(86400001L);
        assertThat(message.endMs()).isEqualTo(86400500L);
        assertThat(message.confidence()).isEqualByComparingTo("0.75");
    }

    @Test void oldCandidateProtocolCannotSmuggleAlignmentEvenWithMatchingHash() throws Exception {
        var request=annotation(json.nullNode());
        var input=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(sample.getContentSnapshot());
        ((com.fasterxml.jackson.databind.node.ObjectNode)input.path("segments").get(0)).put("startMs",0).put("endMs",1);
        sample.setContentSnapshot(input.toString());
        var lineage=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(sample.getExpectedJson());
        lineage.put("inputHash",io.github.opensabre.iqc.pipelineasset.PipelineAssetService.hash(input.toString()));
        sample.setExpectedJson(lineage.toString());
        assertThatThrownBy(()->service.annotate("sample",request)).hasMessageContaining("旧候选协议");
        assertThat(sample.getGoldDraftRevision()).isEqualTo(1);
        verifyNoInteractions(versions);
    }
    @Test void legacyGoldMessageJsonPreservesNullRelativeTimeAndOmitsNewFields() throws Exception {
        var mapper=new ObjectMapper();
        var legacy=mapper.readTree("{\"id\":\"m\",\"role\":\"user\",\"content\":\"text\",\"sequence\":1,\"relativeTime\":null}");
        var message=new GoldConversation.Message("m","user","text",1,null);
        assertThat(mapper.readTree(mapper.writeValueAsString(message))).isEqualTo(legacy);
        assertThat(mapper.treeToValue(legacy,GoldConversation.Message.class)).isEqualTo(message);
    }


    @Test void unknownCandidateProtocolCannotSelectALegacyHashFallback() throws Exception {
        var request=annotation(json.nullNode());
        sample.setContentSnapshot(sample.getContentSnapshot().replace("iqc-conversation-candidate-input-v1","iqc-conversation-candidate-input-v99"));
        assertThatThrownBy(()->service.annotate("sample",request)).hasMessageContaining("来源摘要无效");
        assertThatThrownBy(()->io.github.opensabre.iqc.quality.BusinessReviewSampleService.candidateInputHash(
                json.readTree(sample.getContentSnapshot()))).hasMessageContaining("不支持");
    }
}
