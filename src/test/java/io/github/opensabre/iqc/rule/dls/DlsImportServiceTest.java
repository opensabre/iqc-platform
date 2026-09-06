package io.github.opensabre.iqc.rule.dls;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.rule.QualityRuleService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DlsImportServiceTest {
    @Test
    void previewReportsValidAndInvalidSheetsWithoutWritingRules() {
        DlsExcelParser parser = mock(DlsExcelParser.class);
        QualityRuleService rules = mock(QualityRuleService.class);
        DlsRuleDocument valid = document("有效", "[rule_ok]", "[slot_ok]");
        DlsRuleDocument invalid = document("无效", "[rule_bad]", "[slot_missing]");
        when(parser.parse(new byte[]{1}, "rules.xlsx", true)).thenReturn(List.of(
                new DlsExcelParser.SheetDraft("有效", valid, List.of()),
                new DlsExcelParser.SheetDraft("无效", invalid, List.of())));
        DlsImportService service = new DlsImportService(parser, rules, new ObjectMapper());

        DlsImportService.ImportResult result = service.importWorkbook(new byte[]{1}, "rules.xlsx", true, true);

        assertThat(result.validCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.items()).extracting(DlsImportService.ImportItem::status).containsExactly("VALID", "FAILED");
        verify(rules, never()).create(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), org.mockito.ArgumentMatchers.anyInt(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    private DlsRuleDocument document(String sheet, String entry, String ruleExpression) {
        return new DlsRuleDocument("1.0", new DlsRuleDocument.Source("rules.xlsx", sheet), List.of(
                new DlsRuleDocument.Definition("slot_ok", "SLOT", "命中", "all"),
                new DlsRuleDocument.Definition(sheet.equals("有效") ? "rule_ok" : "rule_bad", "RULE", ruleExpression, "agent")
        ), sheet, entry);
    }
}
