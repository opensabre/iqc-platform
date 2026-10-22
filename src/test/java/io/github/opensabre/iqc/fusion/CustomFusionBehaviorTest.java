package io.github.opensabre.iqc.fusion;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.finding.Finding;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Installed provider behavior through the shared fusion entry, not a provider mock. */
class CustomFusionBehaviorTest {
    private FusionPolicy policy(int minimum) {
        var descriptor=FusionExtensionRegistry.system().descriptors().stream().filter(d->d.strategyId().equals("UNANIMOUS")).findFirst().orElseThrow();
        return new FusionPolicy("custom",1,FusionPolicy.Method.CUSTOM,Map.of(),Map.of(),null,Set.of(),null,
                new FusionExtensionBinding(descriptor.strategyId(),descriptor.version(),descriptor.implementationHash(),Map.of("minimumSources",Integer.toString(minimum))));
    }
    @Test void explicitFalseConsensusPreservesOriginalFactsAndEvidence() throws Exception {
        var json=new ObjectMapper();var raw=List.of(WeightedFusionBehaviorTest.known("a",false),WeightedFusionBehaviorTest.known("b",false));
        String before=json.writeValueAsString(raw);var result=FindingFusion.fuse(raw,policy(2)).getFirst();
        assertThat(result.state()).isEqualTo(Finding.State.KNOWN);assertThat(result.value().booleanValue()).isFalse();
        assertThat(result.selectedIds()).containsExactly("a","b");assertThat(result.rawFindings()).containsExactlyElementsOf(raw);
        assertThat(result.evidence()).extracting(Finding.Evidence::id).containsExactly("e-a","e-b");
        assertThat(result.finalConfidence()).isNull();assertThat(json.writeValueAsString(raw)).isEqualTo(before);
    }
    @Test void disagreementAndInsufficientSourcesCannotBecomeKnown() {
        assertThat(FindingFusion.fuse(List.of(WeightedFusionBehaviorTest.known("a",false),WeightedFusionBehaviorTest.known("b",true)),policy(2)).getFirst().state())
                .isEqualTo(Finding.State.CONFLICT);
        assertThat(FindingFusion.fuse(List.of(WeightedFusionBehaviorTest.known("a",false)),policy(2)).getFirst().state()).isEqualTo(Finding.State.UNKNOWN);
        var skipped=WeightedFusionBehaviorTest.observation("b","b",Finding.State.NOT_EVALUATED,null,null);
        assertThat(FindingFusion.fuse(List.of(skipped),policy(1)).getFirst().state()).isEqualTo(Finding.State.NOT_EVALUATED);
    }
    @Test void installedIdentityAndDuplicateRegistrationAreEnforced() {
        var original=policy(1).custom();
        assertThatThrownBy(()->FusionExtensionRegistry.system().require(new FusionExtensionBinding(original.strategyId(),original.version(),"0".repeat(64),original.parameters())))
                .hasMessageContaining("unavailable");
        assertThatThrownBy(()->new FusionExtensionRegistry(List.of(new UnanimousFusionExtension(),new UnanimousFusionExtension())))
                .hasMessageContaining("Duplicate");
    }
}
