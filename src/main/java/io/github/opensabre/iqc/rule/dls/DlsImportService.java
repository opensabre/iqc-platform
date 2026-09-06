package io.github.opensabre.iqc.rule.dls;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.rule.QualityRuleService;
import io.github.opensabre.iqc.rule.model.QualityRule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Previews and imports validated XLSX DLS sheets as versioned quality-rule drafts. */
@Service
@RequiredArgsConstructor
public class DlsImportService {
    private final DlsExcelParser excelParser;
    private final QualityRuleService ruleService;
    private final ObjectMapper objectMapper;

    @Transactional
    public ImportResult importWorkbook(byte[] content, String fileName, boolean excludeTests, boolean preview) {
        List<DlsExcelParser.SheetDraft> sheets = excelParser.parse(content, fileName, excludeTests);
        List<ImportItem> items = new ArrayList<>();
        for (DlsExcelParser.SheetDraft sheet : sheets) {
            String code = stableCode(sheet.document());
            try {
                String expression = write(sheet.document());
                DlsEngine.Compiled compiled = DlsEngine.compile(sheet.document());
                QualityRule existing = ruleService.findByCode(code);
                if (existing != null) {
                    items.add(new ImportItem(sheet.sheetName(), sheet.document().entryName(), code, "SKIPPED", existing.getId(),
                            sheet.document().definitions().size(), compiled.rulePatterns().size(), sheet.warnings(), "相同来源规则已存在"));
                } else if (preview) {
                    items.add(new ImportItem(sheet.sheetName(), sheet.document().entryName(), code, "VALID", null,
                            sheet.document().definitions().size(), compiled.rulePatterns().size(), sheet.warnings(), "预检通过"));
                } else {
                    QualityRule rule = ruleService.create(sheet.document().entryName(), code, "CUSTOM", "DLS", "all", expression,
                            "从 " + fileName + " / " + sheet.sheetName() + " 导入", 10, "HIGH", false);
                    items.add(new ImportItem(sheet.sheetName(), sheet.document().entryName(), code, "CREATED", rule.getId(),
                            sheet.document().definitions().size(), compiled.rulePatterns().size(), sheet.warnings(), "已创建草稿"));
                }
            } catch (IllegalArgumentException exception) {
                items.add(new ImportItem(sheet.sheetName(), sheet.document().entryName(), code, "FAILED", null,
                        sheet.document().definitions().size(), 0, sheet.warnings(), exception.getMessage()));
            }
        }
        long created = items.stream().filter(item -> "CREATED".equals(item.status())).count();
        long valid = items.stream().filter(item -> "VALID".equals(item.status())).count();
        long skipped = items.stream().filter(item -> "SKIPPED".equals(item.status())).count();
        long failed = items.stream().filter(item -> "FAILED".equals(item.status())).count();
        return new ImportResult(fileName, preview, sheets.size(), created, valid, skipped, failed, List.copyOf(items));
    }

    private String write(DlsRuleDocument document) {
        try {
            return objectMapper.writeValueAsString(document);
        } catch (JsonProcessingException exception) {
            throw IqcException.invalidArgument("DLS 文档序列化失败", exception);
        }
    }

    private String stableCode(DlsRuleDocument document) {
        StringBuilder source = new StringBuilder(document.entryName()).append('\n').append(document.entryExpression());
        document.definitions().forEach(definition -> source.append('\n').append(definition.name()).append('\t')
                .append(definition.kind()).append('\t').append(definition.targetRole()).append('\t').append(definition.expression()));
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.toString().getBytes(StandardCharsets.UTF_8))).substring(0, 16);
            return "dls_" + asciiSlug(document.entryName()) + "_" + digest;
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成 DLS 规则编码", exception);
        }
    }

    private String asciiSlug(String value) {
        String slug = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return slug.isBlank() ? "sheet" : slug.substring(0, Math.min(32, slug.length()));
    }

    public record ImportResult(String fileName, boolean preview, int sheetCount, long createdCount,
                               long validCount, long skippedCount, long failedCount, List<ImportItem> items) { }
    public record ImportItem(String sheetName, String ruleName, String ruleCode, String status, String ruleId,
                             int definitionCount, int executableRuleCount, List<String> warnings, String message) { }
}
