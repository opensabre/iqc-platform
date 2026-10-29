package io.github.opensabre.iqc.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.agent.AgentConfiguration;
import io.github.opensabre.iqc.privacy.SensitiveReadAccessService;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.model.InspectionTask;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Original task knowledge evidence remains bounded, private and independently verifiable. */
class KnowledgeTaskSnapshotTest {
    private final ObjectMapper json=new ObjectMapper();
    private final KnowledgeBaseService knowledge=mock(KnowledgeBaseService.class);
    private final SensitiveReadAccessService access=mock(SensitiveReadAccessService.class);
    private final IqcDataScope scope=mock(IqcDataScope.class);
    private final KnowledgeTaskSnapshotService service=new KnowledgeTaskSnapshotService(knowledge,access,scope,json);
    private InspectionTask frozen(String sourceGroup) throws Exception {
        return frozen(sourceGroup,new KnowledgeCorpus(KnowledgeCorpus.SCHEMA,List.of(new KnowledgeCorpus.Document("doc","Title","private reference","source"))));
    }
    private InspectionTask frozen(String sourceGroup,KnowledgeCorpus corpus) throws Exception {
        var published=new KnowledgeBaseService.Published("iqc-knowledge-release-v1","kb",2,3,"Knowledge",corpus,"source-owner","group","publisher");
        String hash=SelectorAssetService.hash(json.writeValueAsString(published));
        var prepared=new KnowledgeBaseService.Prepared("version",published,hash,sourceGroup);
        var config=new AgentConfiguration("3.0",null,"instructions",null,null,null,null,"model",List.of(),List.of(),List.of(),null,null)
                .withKnowledgeAssets(List.of(new AgentConfiguration.KnowledgeReference("kb",2,"version",hash)));
        var task=new InspectionTask();task.setId("task");task.setCreatedBy("owner");task.setOwnerGroupId("group");task.setRuleSnapshotJson("{}");
        task.setAgentSnapshotJson(json.createObjectNode().put("configJson",json.writeValueAsString(config)).toString());
        when(scope.owner()).thenReturn("owner");when(knowledge.publishedForTask(anyList())).thenReturn(List.of(prepared));
        service.freeze(task);return task;
    }
    @Test void freezesAndVerifiesOriginalEvidenceWithoutResolvingCurrentSources() throws Exception {
        var task=frozen("group");clearInvocations(knowledge,access,scope);
        var verified=KnowledgeTaskSnapshotService.verifiedSources(task,json);
        assertThat(verified).hasSize(1);assertThat(verified.getFirst().snapshot().corpus().documents().getFirst().text()).isEqualTo("private reference");
        assertThatThrownBy(()->verified.clear()).isInstanceOf(UnsupportedOperationException.class);
        verifyNoInteractions(knowledge,access,scope);
        task.setOwnerGroupId(null);assertThat(KnowledgeTaskSnapshotService.verifiedSources(task,json)).hasSize(1);
        task.setOwnerGroupId("other");assertThatThrownBy(()->KnowledgeTaskSnapshotService.verifiedSources(task,json)).hasMessageContaining("归属");
    }
    @Test void narrowsPrivateSourceAndRejectsRehashedMissingCoverageAndWrongOwner() throws Exception {
        var task=frozen(null);assertThat(task.getOwnerGroupId()).isNull();
        assertThat(KnowledgeTaskSnapshotService.verifiedSources(task,json)).hasSize(1);
        var original=task.getRuleSnapshotJson();var root=(ObjectNode)json.readTree(original);
        var envelope=(ObjectNode)root.path("knowledgeInputs");var body=(ObjectNode)envelope.path("body");
        body.set("sources",json.createArrayNode());envelope.put("contentHash",SelectorAssetService.hash(body.toString()));
        task.setRuleSnapshotJson(root.toString());assertThatThrownBy(()->KnowledgeTaskSnapshotService.verifiedSources(task,json)).hasMessageContaining("引用覆盖");
        task.setRuleSnapshotJson(original);task.setCreatedBy("outsider");
        assertThatThrownBy(()->KnowledgeTaskSnapshotService.verifiedSources(task,json)).hasMessageContaining("归属");
    }
    @Test void modelMaterialRequiresTaskIdentityAndExactNodeReferencesAndStaysOutOfMetadata() throws Exception {
        var task=frozen("group");
        var capability=KnowledgeTaskSnapshotService.executionContext(task,json);
        var config=json.readTree(json.readTree(task.getAgentSnapshotJson()).path("configJson").asText());
        var context=new io.github.opensabre.iqc.result.llm.ModelCallContext("task","execution","node",0,"owner","group","conversation",capability);
        var material=io.github.opensabre.iqc.result.llm.LlmQualityProvider.knowledgeMaterial(config,context);
        assertThat(material).contains("private reference","version","source","不得将其作为会话证据");
        assertThat(json.writeValueAsString(context)).doesNotContain("knowledgeContext","private reference","documents");
        assertThatThrownBy(()->capability.material(config,"other","owner","group")).hasMessageContaining("不属于");
        assertThatThrownBy(()->capability.material(config,"task","outsider","group")).hasMessageContaining("不属于");
        assertThatThrownBy(()->capability.material(config,"task","owner",null)).hasMessageContaining("不属于");
        var changed=config.deepCopy();((ObjectNode)changed.path("knowledgeAssets").get(0)).put("versionId","other-version");
        assertThatThrownBy(()->capability.material(changed,"task","owner","group")).hasMessageContaining("不属于原任务");
        assertThatThrownBy(()->io.github.opensabre.iqc.result.llm.LlmQualityProvider.knowledgeMaterial(config,null))
                .hasMessageContaining("KNOWLEDGE_CONTEXT_NOT_FROZEN");
    }
    @Test void contextLimitCountsUtf8BytesAndRejectsOversizedReferenceMaterial() throws Exception {
        for(String character:List.of("a","中")){
            var documents=new java.util.ArrayList<KnowledgeCorpus.Document>();
            for(int i=0;i<6;i++)documents.add(new KnowledgeCorpus.Document("doc"+i,"Title",character.repeat(16000),"source"));
            var task=frozen("group",new KnowledgeCorpus(KnowledgeCorpus.SCHEMA,documents));
            var capability=KnowledgeTaskSnapshotService.executionContext(task,json);
            var config=json.readTree(json.readTree(task.getAgentSnapshotJson()).path("configJson").asText());
            if(character.equals("a")){
                String material=capability.material(config,"task","owner","group");
                assertThat(material.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(256*1024);
                assertThat(material).contains("doc0","doc5");
            }else{
                assertThatThrownBy(()->capability.material(config,"task","owner","group"))
                        .isInstanceOf(io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException.class)
                        .hasMessageContaining("KNOWLEDGE_CONTEXT_TOO_LARGE");
            }
        }
    }
    @Test void legacyModelContextNeedsNoKnowledgeCapability() throws Exception {
        var task=new InspectionTask();task.setRuleSnapshotJson("{}");
        assertThat(KnowledgeTaskSnapshotService.executionContext(task,json)).isNull();
        assertThat(io.github.opensabre.iqc.result.llm.LlmQualityProvider.knowledgeMaterial(json.createObjectNode(),null)).isEmpty();
    }
    @Test void rejectsInjectedEnvelopeForLegacyTaskAndDuplicateOrTrailingJson(){
        var task=new InspectionTask();task.setRuleSnapshotJson("{}");
        assertThat(KnowledgeTaskSnapshotService.verifiedSources(task,json)).isEmpty();
        for(String invalid:List.of("{\"knowledgeInputs\":{}}","{\"a\":1,\"a\":2}","{} {}")){
            task.setRuleSnapshotJson(invalid);assertThatThrownBy(()->KnowledgeTaskSnapshotService.verifiedSources(task,json)).isInstanceOf(RuntimeException.class);
        }
        verifyNoInteractions(knowledge,access,scope);
    }
}
