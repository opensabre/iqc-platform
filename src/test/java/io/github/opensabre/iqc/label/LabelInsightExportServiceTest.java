package io.github.opensabre.iqc.label;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LabelInsightExportServiceTest {
    @Test
    void exportsCoverageStatesAndFalseWithoutDroppingCandidates() throws Exception {
        var query = mock(LabelResultQueryService.class);
        var states = java.util.List.of("KNOWN", "UNKNOWN", "CONFLICT", "ERROR");
        when(query.listByTask("task-1")).thenReturn(states.stream().map(state ->
                new LabelResultQueryService.LabelResultView(state, "c1", "file", "label", "有房", "house", "画像 / 有房", 1,
                        "owns", "{\"status\":\"" + state + "\",\"value\":false,\"candidates\":[]}",
                        null, "RULE", "anchor", "[]", state)).toList());
        var bytes = new LabelInsightExportService(query).exportTask("task-1");
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(4);
            for (int index = 0; index < states.size(); index++) {
                var row = sheet.getRow(index + 1);
                assertThat(row.getCell(8).getStringCellValue()).isEqualTo(states.get(index));
                assertThat(row.getCell(14).getStringCellValue()).contains("\"value\":false", "candidates");
            }
        }
    }

    @Test
    void exportsMachineAndEffectiveHumanValuesInSeparateColumns() throws Exception {
        var query = mock(LabelResultQueryService.class);
        var overlay = new LabelResultReviewService.ReviewOverlay(2, "PENDING", "review-1", 1,
                "KNOWN", com.fasterxml.jackson.databind.node.BooleanNode.FALSE, java.util.List.of("m1"));
        when(query.listByTask("task-review")).thenReturn(java.util.List.of(
                new LabelResultQueryService.LabelResultView("label-result", "c1", "file", "label", "有房", "house", "画像 / 有房", 2,
                        "owned", "{\"status\":\"UNKNOWN\"}", null, "RULE", "anchor", "[]", "UNKNOWN", overlay)));
        var bytes = new LabelInsightExportService(query).exportTask("task-review");
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheet("标签洞察");
            var header = sheet.getRow(0); var row = sheet.getRow(1);
            assertThat(header.getCell(8).getStringCellValue()).isEqualTo("机器结果状态");
            assertThat(row.getCell(8).getStringCellValue()).isEqualTo("UNKNOWN");
            assertThat(row.getCell(14).getStringCellValue()).contains("UNKNOWN");
            assertThat(row.getCell(15).getStringCellValue()).isEqualTo("PENDING");
            assertThat(row.getCell(17).getStringCellValue()).isEqualTo("review-1");
            assertThat(row.getCell(19).getStringCellValue()).isEqualTo("KNOWN");
            assertThat(row.getCell(20).getStringCellValue()).isEqualTo("false");
            assertThat(row.getCell(21).getStringCellValue()).isEqualTo("[\"m1\"]");
        }
    }

    @Test
    void exportsLargeEvidenceAndCandidateJsonWithoutTruncation() throws Exception {
        var query = mock(LabelResultQueryService.class);
        String evidence = "=" + randomText(45_000);
        String candidates = "{\"candidates\":[{\"text\":\"" + randomText(48_000) + "\"}]}";
        when(query.listByTask("task-large")).thenReturn(java.util.List.of(
                new LabelResultQueryService.LabelResultView("lr1", "c1", "file", "label", "画像", "profile", "画像", 1,
                        "value", candidates, null, "RULE", "anchor", evidence, "KNOWN")));

        var bytes = new LabelInsightExportService(query).exportTask("task-large");
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
            var main = workbook.getSheet("标签洞察");
            var details = workbook.getSheet("长文本明细");
            assertThat(main.getRow(1).getCell(10).getStringCellValue()).contains("长文本明细", "列 11");
            assertThat(main.getRow(1).getCell(14).getStringCellValue()).contains("长文本明细", "列 15");
            assertThat(reassemble(details, 11)).isEqualTo(evidence);
            assertThat(reassemble(details, 15)).isEqualTo(candidates);
        }
    }

    private String reassemble(org.apache.poi.ss.usermodel.Sheet details, int column) {
        var bytes = new java.io.ByteArrayOutputStream();
        int parts = 0;
        for (int index = 1; index <= details.getLastRowNum(); index++) {
            var row = details.getRow(index);
            if (Integer.parseInt(row.getCell(1).getStringCellValue()) != column) continue;
            assertThat(Integer.parseInt(row.getCell(3).getStringCellValue())).isEqualTo(++parts);
            bytes.writeBytes(java.util.Base64.getDecoder().decode(row.getCell(5).getStringCellValue()));
        }
        assertThat(parts).isGreaterThan(1);
        return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String randomText(int length) {
        var random = new java.util.Random(42);
        var value = new StringBuilder(length);
        for (int index = 0; index < length; index++)
            value.append(index % 2 == 0 ? (char) ('a' + random.nextInt(26)) : (char) (0x4E00 + random.nextInt(20_000)));
        return value.toString();
    }

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
