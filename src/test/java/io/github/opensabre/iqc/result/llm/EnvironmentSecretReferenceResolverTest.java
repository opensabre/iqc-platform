package io.github.opensabre.iqc.result.llm;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvironmentSecretReferenceResolverTest {

    @Test
    void resolvesEnvironmentReferenceWithoutExposingValue() {
        var resolver = new EnvironmentSecretReferenceResolver(
            new MockEnvironment().withProperty("IQC_MODEL_API_KEY", "top-secret"));

        assertThat(resolver.resolve("env:IQC_MODEL_API_KEY")).isEqualTo("top-secret");
    }

    @Test
    void rejectsUnsupportedReferenceFormat() {
        var resolver = new EnvironmentSecretReferenceResolver(new MockEnvironment());

        assertThatThrownBy(() -> resolver.resolve("secrets/iqc/model-key"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("env:")
            .hasMessageNotContaining("model-key");
    }
}
