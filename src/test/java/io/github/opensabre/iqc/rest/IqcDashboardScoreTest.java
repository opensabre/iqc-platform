package io.github.opensabre.iqc.rest;

import io.github.opensabre.iqc.result.model.InspectionResult;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class IqcDashboardScoreTest {
    @Test
    void unscoredObservationsNeverEnterLegacyAverageAsZero() {
        var observation = new InspectionResult();
        var legacy = new InspectionResult(); legacy.setScore(80);
        assertThat(IqcDashboardController.legacyAverageScore(List.of(observation))).isNull();
        assertThat(IqcDashboardController.legacyAverageScore(List.of(legacy, observation))).isEqualByComparingTo("80");
    }
}
