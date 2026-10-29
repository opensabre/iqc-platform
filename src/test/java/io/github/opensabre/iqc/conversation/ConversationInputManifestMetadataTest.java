package io.github.opensabre.iqc.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Checks the actual import normalization boundary for already processed conversation metadata. */
class ConversationInputManifestMetadataTest {
    private final ObjectMapper json = new ObjectMapper();

    private ConversationMetadata metadata(Map<String, JsonNode> fields) {
        return new ConversationMetadata(null,null,null,null,null,null,null,null,null,null,null,
                List.of(),null,null,null,fields);
    }

    private Map<String, JsonNode> manifest() {
        return new HashMap<>(Map.of(InputCountManifest.VERSION_KEY,json.valueToTree(InputCountManifest.VERSION),
                InputCountManifest.COUNT_KEY,json.valueToTree(3),InputCountManifest.REFERENCE_KEY,json.valueToTree("export-42")));
    }

    @Test void missingAndEmptyExtensionsPreserveLegacyFingerprintAndJson() throws Exception {
        var legacy=new ConversationMetadata(null,null,null,null,null,null,null,null,null,null,null,List.of());
        assertThat(metadata(null).fingerprintPart()).isEqualTo("||||||");
        assertThat(metadata(Map.of()).fingerprintPart()).isEqualTo(legacy.fingerprintPart());
        assertThat(json.writeValueAsString(metadata(Map.of()).normalized())).isEqualTo(json.writeValueAsString(legacy.normalized()));
        var countOnly=metadata(Map.of(InputCountManifest.COUNT_KEY,json.valueToTree(3))).normalized();
        assertThat(countOnly.businessFields()).containsKey(InputCountManifest.COUNT_KEY);
        assertThat(InputCountManifest.observe(json.valueToTree(countOnly.businessFields()),1).state())
                .isEqualTo(InputCountManifest.State.UNMEASURED);
    }

    @Test void validBoundsAreAcceptedWithoutInferringObservedCount() {
        for (int count:List.of(1,Integer.MAX_VALUE)) {
            var fields=manifest();fields.put(InputCountManifest.COUNT_KEY,json.valueToTree(count));
            var normalized=metadata(fields).normalized();
            assertThat(normalized.businessFields().get(InputCountManifest.COUNT_KEY).intValue()).isEqualTo(count);
            assertThat(metadata(fields).fingerprintPart()).contains("selector-metadata-v1:");
        }
    }

    @Test void explicitInvalidCountsAndVersionsAreRejectedAtNormalization() {
        for (JsonNode count:List.of(json.nullNode(),json.valueToTree("3"),json.valueToTree(1.5),
                json.valueToTree(0),json.valueToTree(-1),json.valueToTree(2147483648L))) {
            var fields=manifest();fields.put(InputCountManifest.COUNT_KEY,count);
            assertThatThrownBy(()->metadata(fields).normalized()).hasMessageContaining("清单声明无效");
        }
        for (JsonNode version:List.of(json.nullNode(),json.valueToTree("unknown"),json.valueToTree(1))) {
            var fields=manifest();fields.put(InputCountManifest.VERSION_KEY,version);
            assertThatThrownBy(()->metadata(fields).normalized()).hasMessageContaining("清单声明无效");
        }
    }

    @Test void referenceMustBeExplicitBoundedAndWithoutWhitespaceOrControlCharacters() {
        for (String reference:List.of(""," "," leading","trailing ","embedded\ncontrol","x".repeat(129))) {
            var fields=manifest();fields.put(InputCountManifest.REFERENCE_KEY,json.valueToTree(reference));
            assertThatThrownBy(()->metadata(fields).normalized()).hasMessageContaining("清单声明无效");
        }
        var fields=manifest();fields.remove(InputCountManifest.REFERENCE_KEY);
        assertThatThrownBy(()->metadata(fields).normalized()).hasMessageContaining("清单声明无效");
        fields.put(InputCountManifest.REFERENCE_KEY,json.valueToTree("x".repeat(128)));
        assertThat(metadata(fields).normalized().businessFields()).hasSize(3);
    }

    @Test void manifestSharesExistingFieldBudgetAndNormalizationCopiesCallerMap() {
        var fields=manifest();for(int i=0;i<61;i++)fields.put("field"+i,json.valueToTree(i));
        var normalized=metadata(fields).normalized();assertThat(normalized.businessFields()).hasSize(64);
        fields.put(InputCountManifest.COUNT_KEY,json.valueToTree(99));
        assertThat(normalized.businessFields().get(InputCountManifest.COUNT_KEY).intValue()).isEqualTo(3);
        assertThatThrownBy(()->normalized.businessFields().clear()).isInstanceOf(UnsupportedOperationException.class);
        fields.put("overflow",json.valueToTree(true));
        assertThatThrownBy(()->metadata(fields).normalized()).hasMessageContaining("64");
    }
}
