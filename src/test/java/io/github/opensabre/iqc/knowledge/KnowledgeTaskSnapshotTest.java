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
        var corpus=new KnowledgeCorpus(KnowledgeCorpus.SCHEMA,List.of(new KnowledgeCorpus.Document("doc","Title","private reference","source")));
        var published=new KnowledgeBaseService.Published("iqc-knowledge-release-v1","kb",2,3,"Knowledge",corpus,"source-owner","group","publisher");
        String hash=SelectorAssetService.hash(json.writeValueAsString(published));
        var prepared=new KnowledgeBaseService.Prepared("version",published,hash,sourceGroup);
        var config=new AgentConfiguration("3.0",null,"instructions",null,null,null,null,"model",List.of(),List.of(),List.of(),null,null)
                .withKnowledgeAssets(List.of(new AgentConfiguration.KnowledgeReference("kb",2,"version",hash)));
        var task=new InspectionTask();task.setCreatedBy("owner");task.setOwnerGroupId("group");task.setRuleSnapshotJson("{}");
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
    @Test void rejectsInjectedEnvelopeForLegacyTaskAndDuplicateOrTrailingJson(){
        var task=new InspectionTask();task.setRuleSnapshotJson("{}");
        assertThat(KnowledgeTaskSnapshotService.verifiedSources(task,json)).isEmpty();
        for(String invalid:List.of("{\"knowledgeInputs\":{}}","{\"a\":1,\"a\":2}","{} {}")){
            task.setRuleSnapshotJson(invalid);assertThatThrownBy(()->KnowledgeTaskSnapshotService.verifiedSources(task,json)).isInstanceOf(RuntimeException.class);
        }
        verifyNoInteractions(knowledge,access,scope);
    }
}
