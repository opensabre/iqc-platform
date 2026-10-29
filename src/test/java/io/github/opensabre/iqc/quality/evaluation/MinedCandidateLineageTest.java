package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.quality.BusinessReviewSampleService;
import io.github.opensabre.iqc.quality.model.QualitySample;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** Frozen mining provenance must remain unlabelled and fail closed on malformed stored data. */
class MinedCandidateLineageTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private QualitySample candidate() throws Exception {
        var input=json.createObjectNode().put("schemaVersion",BusinessReviewSampleService.EXACT_INPUT_SCHEMA)
                .put("conversationId","conversation");
        input.putArray("segments").addObject().put("id","message").put("speakerRole","agent")
                .put("content","frozen conversation").put("sequenceNo",1);
        var sample=new QualitySample();sample.setId("sample");sample.setStatus("CANDIDATE");
        sample.setSampleType("UNCLASSIFIED");sample.setSourceResultId("result");sample.setConversationId("conversation");
        sample.setCreatedBy("owner");sample.setOwnerGroupId("group");sample.setContentSnapshot(input.toString());
        sample.setGoldDraftRevision(0);
        sample.setExpectedJson(json.writeValueAsString(new MinedCandidateLineage(MinedCandidateLineage.SCHEMA,
                "result","task","execution","conversation","owner","group","a".repeat(64),
                BusinessReviewSampleService.candidateInputHash(input),"b".repeat(64),"c".repeat(64),
                "LOW_CONFIDENCE",1,List.of("LOW_CONFIDENCE"),"d".repeat(64))));
        return sample;
    }
    @Test void validSourceAllowsOwnerOnlyNarrowingButNotAnotherGroup() throws Exception {
        var sample=candidate();
        assertThat(MinedCandidateLineage.validate(sample,json).taskId()).isEqualTo("task");
        sample.setOwnerGroupId(null);
        assertThat(MinedCandidateLineage.validate(sample,json).ownerGroupId()).isEqualTo("group");
        sample.setOwnerGroupId("other");reject(sample);
    }
    @Test void rejectsChangedSourceIdentityOwnershipTypeAndInput() throws Exception {
        for(String field:List.of("sourceResultId","conversationId","owner","inputHash")) {
            var sample=candidate();var lineage=(ObjectNode)json.readTree(sample.getExpectedJson());
            lineage.put(field,field.equals("inputHash")?"e".repeat(64):"other");
            sample.setExpectedJson(lineage.toString());reject(sample);
        }
        var sample=candidate();sample.setSampleType("FALSE_POSITIVE");reject(sample);
        sample=candidate();sample.setMessageId("message");reject(sample);
        sample=candidate();sample.setContentSnapshot(sample.getContentSnapshot().replace("frozen conversation","changed"));reject(sample);
        sample=candidate();sample.setContentSnapshot(sample.getContentSnapshot().replace(BusinessReviewSampleService.EXACT_INPUT_SCHEMA,"iqc-conversation-candidate-input-v2"));reject(sample);
    }
    @Test void rejectsDuplicateKeysTrailingJsonAndSmuggledExpectedValues() throws Exception {
        var sample=candidate();String original=sample.getExpectedJson();
        for(String invalid:List.of(original+" {}", original.replaceFirst("\\{","{\"taskId\":\"other\","),
                original.substring(0,original.length()-1)+",\"expected\":true}",
                original.replace("\"taskId\":\"task\"","\"taskId\":123"),
                original.replace("\"miningVersion\":1","\"miningVersion\":1.5"),
                original.replace("[\"LOW_CONFIDENCE\"]","[\"LOW_CONFIDENCE\",\"LOW_CONFIDENCE\"]"),
                original.replace("[\"LOW_CONFIDENCE\"]","[123]"))) {
            assertThat(invalid).isNotEqualTo(original);sample.setExpectedJson(invalid);reject(sample);
        }
    }
    @Test void annotationUsesFrozenMessagesAndRequiresIndependentHumanApproval() throws Exception {
        var sample=candidate();String snapshot=sample.getContentSnapshot(),lineage=sample.getExpectedJson();
        var samples=org.mockito.Mockito.mock(io.github.opensabre.iqc.quality.dao.QualitySampleMapper.class);
        var versions=org.mockito.Mockito.mock(io.github.opensabre.iqc.quality.dao.QualitySampleVersionMapper.class);
        var conversations=org.mockito.Mockito.mock(io.github.opensabre.iqc.conversation.dao.ConversationMapper.class);
        var messages=org.mockito.Mockito.mock(io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper.class);
        var scope=org.mockito.Mockito.mock(io.github.opensabre.iqc.shared.IqcDataScope.class);
        org.mockito.Mockito.when(samples.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(sample);
        org.mockito.Mockito.when(scope.canView("owner","group")).thenReturn(true);
        org.mockito.Mockito.when(scope.owner()).thenReturn("annotator");
        var service=new GoldSampleService(samples,versions,conversations,messages,scope,json);
        service.annotate("sample",new GoldSampleService.Annotation(0,List.of(new GoldFinding("FACT",io.github.opensabre.iqc.finding.Finding.ValueType.BOOLEAN,io.github.opensabre.iqc.finding.Finding.State.KNOWN,json.valueToTree(true),List.of("message"))),null,List.of(),GoldConversation.Difficulty.BASIC,null));
        var draft=service.read(sample.getGoldDraftJson(),GoldDraft.class);
        assertThat(draft.messages().getFirst().content()).isEqualTo("frozen conversation");
        assertThat(draft.businessContext().at("/candidateLineage/miningStrategy").asText()).isEqualTo("LOW_CONFIDENCE");
        assertThat(sample.getGoldStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(sample.getContentSnapshot()).isEqualTo(snapshot);assertThat(sample.getExpectedJson()).isEqualTo(lineage);
        org.mockito.Mockito.verifyNoInteractions(conversations,messages,versions);
        assertThatThrownBy(()->service.review("sample",1,true,"checked")).hasMessageContaining("必须不同");
        org.mockito.Mockito.when(scope.owner()).thenReturn("reviewer");
        assertThat(service.review("sample",1,true,"independent annotation checked").getDecision()).isEqualTo("APPROVED");
    }
    private void reject(QualitySample sample) {
        assertThatThrownBy(()->MinedCandidateLineage.validate(sample,json)).hasMessageContaining("自动候选");
    }
}
