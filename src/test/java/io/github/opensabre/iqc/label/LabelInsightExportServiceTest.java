package io.github.opensabre.iqc.label;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LabelInsightExportServiceTest {
    @Test
    void prefixesSpreadsheetFormulaTriggers() {
        assertThat(LabelInsightExportService.formulaSafe("=cmd()")) .isEqualTo("'=cmd()");
        assertThat(LabelInsightExportService.formulaSafe("+1")) .isEqualTo("'+1");
        assertThat(LabelInsightExportService.formulaSafe("-1")) .isEqualTo("'-1");
        assertThat(LabelInsightExportService.formulaSafe("@name")) .isEqualTo("'@name");
        assertThat(LabelInsightExportService.formulaSafe("normal")) .isEqualTo("normal");
    }

    @Test
    void rejectsExportsAboveTheSynchronousRowLimit() {
        LabelResultQueryService query = mock(LabelResultQueryService.class);
        when(query.listByTask("task-1")).thenReturn(java.util.Collections.nCopies(LabelInsightExportService.MAX_ROWS + 1, null));
        assertThatThrownBy(() -> new LabelInsightExportService(query).exportTask("task-1"))
                .hasMessageContaining("50000");
    }
}
