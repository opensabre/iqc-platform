package io.github.opensabre.iqc.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.knowledge.KnowledgeBaseService;
import io.github.opensabre.iqc.privacy.SensitiveReadAccessService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Shared Agent configurations bind exact metadata without copying private corpus bodies. */
class KnowledgeAssetReferenceTest {
    private final KnowledgeBaseService knowledge=mock(KnowledgeBaseService.class);
    private final SensitiveReadAccessService sensitive=mock(SensitiveReadAccessService.class);
    private final AgentAssetReferenceValidator validator=new AgentAssetReferenceValidator(null,null,null,null,knowledge,sensitive);
    private AgentConfiguration config(){return new AgentConfiguration("3.0",null,"instructions",null,null,null,null,
            "model",List.of(),List.of(),List.of(),null,null).withKnowledgeAssets(List.of(
            new AgentConfiguration.KnowledgeReference("kb",2,null,null)));}
    @Test void bindsExactMetadataWithoutMutatingCallerAndRechecksCurrentAuthorization() throws Exception {
        when(knowledge.referenceBinding("kb",2)).thenReturn(new KnowledgeBaseService.Binding("kb",2,"version-2","a".repeat(64)));
        var original=config();var bound=validator.resolveKnowledge(original);
        assertThat(original.knowledgeAssets().getFirst().versionId()).isNull();
        assertThat(bound.knowledgeAssets().getFirst().versionId()).isEqualTo("version-2");
        var json=new ObjectMapper();
        assertThat(json.readValue(json.writeValueAsString(bound),AgentConfiguration.class)).isEqualTo(bound);
        validator.validateKnowledge(bound);
        verify(sensitive,times(2)).current(java.util.Set.of("iqc:knowledge:version:view"));
        clearInvocations(knowledge);
        when(sensitive.current(anySet())).thenThrow(new IllegalStateException("revoked"));
        assertThatThrownBy(()->validator.validateKnowledge(bound)).hasMessageContaining("revoked");
        verifyNoInteractions(knowledge);
    }
    @Test void rejectsChangedVersionIdentityAndHash(){
        for(var source:List.of(new KnowledgeBaseService.Binding("other",2,"v","a".repeat(64)),
                new KnowledgeBaseService.Binding("kb",3,"v","a".repeat(64)),
                new KnowledgeBaseService.Binding("kb",2,"v","bad"))){
            when(knowledge.referenceBinding("kb",2)).thenReturn(source);
            assertThatThrownBy(()->validator.resolveKnowledge(config())).hasMessageContaining("不一致");
        }
        when(knowledge.referenceBinding("kb",2)).thenReturn(new KnowledgeBaseService.Binding("kb",2,"v","b".repeat(64)));
        var pinned=config().withKnowledgeAssets(List.of(new AgentConfiguration.KnowledgeReference("kb",2,"v","a".repeat(64))));
        assertThatThrownBy(()->validator.validateKnowledge(pinned)).hasMessageContaining("不一致");
    }
    @Test void oldConfigurationsNeedNoKnowledgeDependencies(){
        var old=config().withKnowledgeAssets(null);
        assertThat(validator.resolveKnowledge(old)).isSameAs(old);
        validator.validateKnowledge(old);
        verifyNoInteractions(knowledge,sensitive);
    }
}
