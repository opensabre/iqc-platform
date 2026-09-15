package io.github.opensabre.iqc.label;

import io.github.opensabre.iqc.governance.IqcException;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.List;

/** Streams a bounded, formula-safe XLSX view over the same label result read model used by the UI. */
@Service
@RequiredArgsConstructor
public class LabelInsightExportService {
    static final int MAX_ROWS = 50_000;
    private final LabelResultQueryService queryService;

    public byte[] exportTask(String taskId) {
        List<LabelResultQueryService.LabelResultView> values = queryService.listByTask(taskId);
        if (values.size() > MAX_ROWS) throw IqcException.invalidArgument("标签结果超过单次导出上限 50000 行");
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.setCompressTempFiles(true);
            var sheet = workbook.createSheet("标签洞察");
            write(sheet.createRow(0), List.of("任务ID", "文件", "会话ID", "标签路径", "标签名称", "标签编码", "标签版本", "标签值", "命中状态", "置信度", "证据", "生成来源", "来源规则结果ID", "AI生成"));
            int rowIndex = 1;
            for (var value : values) write(sheet.createRow(rowIndex++), List.of(taskId, safe(value.sourceFileName()), value.conversationId(), safe(value.labelPath()),
                    value.labelName(), safe(value.labelCode()), String.valueOf(value.labelVersionNo()), safe(value.valueCode()), "HIT",
                    value.confidence() == null ? "" : value.confidence().toPlainString(), safe(value.evidenceJson()), value.generationSource(),
                    safe(value.sourceRuleResultId()), String.valueOf(!"RULE".equals(value.generationSource()))));
            workbook.write(output);
            workbook.dispose();
            return output.toByteArray();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("标签结果 XLSX 生成失败", exception);
        }
    }

    private static void write(Row row, List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            Cell cell = row.createCell(index);
            cell.setCellValue(formulaSafe(values.get(index)));
        }
    }

    static String formulaSafe(String value) {
        if (value == null || value.isEmpty()) return "";
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' ? "'" + value : value;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
