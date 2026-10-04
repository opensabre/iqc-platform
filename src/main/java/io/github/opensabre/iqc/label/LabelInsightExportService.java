package io.github.opensabre.iqc.label;

import io.github.opensabre.iqc.governance.IqcException;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/** Streams a bounded, formula-safe XLSX view over the same label result read model used by the UI. */
@Service
@RequiredArgsConstructor
public class LabelInsightExportService {
    static final int MAX_ROWS = 50_000;
    private static final int MAX_CELL_LENGTH = 32_767;
    private static final int MAX_SHEET_ROWS = 1_048_576;
    private static final int DETAIL_CHUNK_BYTES = 24_000;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final List<String> HEADERS = List.of("任务ID", "文件", "会话ID", "标签路径", "标签名称", "标签编码", "标签版本", "标签值编码", "机器结果状态", "置信度", "机器证据", "生成来源", "来源规则结果ID（兼容锚点）", "AI生成", "机器值与候选JSON",
            "最新复核状态", "最新复核轮次", "生效人工复核ID", "生效人工轮次", "人工有效状态", "人工有效值JSON", "人工证据消息ID JSON");
    private final LabelResultQueryService queryService;

    public byte[] exportTask(String taskId) {
        List<LabelResultQueryService.LabelResultView> values = queryService.listByTask(taskId);
        if (values.size() > MAX_ROWS) throw IqcException.invalidArgument("标签结果超过单次导出上限 50000 行");
        SXSSFWorkbook workbook = new SXSSFWorkbook(200);
        try (workbook; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.setCompressTempFiles(true);
            var sheet = workbook.createSheet("标签洞察");
            var details = workbook.createSheet("长文本明细");
            write(sheet.createRow(0), HEADERS);
            write(details.createRow(0), List.of("主表行号", "主表列序号", "字段", "分片序号", "总片数", "Base64 UTF-8 内容"));
            int rowIndex = 1;
            int[] detailRowIndex = {1};
            for (var value : values) {
                Row row = sheet.createRow(rowIndex++);
                var machine = List.of(taskId, safe(value.sourceFileName()), value.conversationId(), safe(value.labelPath()),
                        value.labelName(), safe(value.labelCode()), String.valueOf(value.labelVersionNo()), safe(value.valueCode()), value.status(),
                        value.confidence() == null ? "" : value.confidence().toPlainString(), safe(value.evidenceJson()), value.generationSource(),
                        safe(value.sourceRuleResultId()), String.valueOf(!"RULE".equals(value.generationSource())), safe(value.valueJson()));
                var overlay = value.reviewOverlay();
                var human = overlay == null ? List.of("", "", "", "", "", "", "") : List.of(
                        safe(overlay.latestStatus()), String.valueOf(overlay.latestRevision()), safe(overlay.effectiveReviewId()),
                        overlay.effectiveRevision() == null ? "" : overlay.effectiveRevision().toString(), safe(overlay.effectiveStatus()),
                        overlay.effectiveValue() == null ? "" : overlay.effectiveValue().toString(),
                        JSON.valueToTree(overlay.evidenceMessageIds()).toString());
                writeResult(row, java.util.stream.Stream.concat(machine.stream(), human.stream()).toList(), details, detailRowIndex);
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("标签结果 XLSX 生成失败", exception);
        } finally {
            workbook.dispose();
        }
    }

    private static void write(Row row, List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            Cell cell = row.createCell(index);
            cell.setCellValue(formulaSafe(values.get(index)));
        }
    }

    /** Excel cells cannot hold complete large candidate JSON; detail chunks preserve its exact UTF-8 bytes. */
    private static void writeResult(Row row, List<String> values, org.apache.poi.ss.usermodel.Sheet details, int[] detailRowIndex) {
        for (int index = 0; index < values.size(); index++) {
            String value = safe(values.get(index));
            String safeValue = formulaSafe(value);
            if (safeValue.length() <= MAX_CELL_LENGTH) {
                row.createCell(index).setCellValue(safeValue);
                continue;
            }
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            int chunks = (bytes.length + DETAIL_CHUNK_BYTES - 1) / DETAIL_CHUNK_BYTES;
            if ((long) detailRowIndex[0] + chunks > MAX_SHEET_ROWS)
                throw IqcException.invalidArgument("标签结果长文本超过 XLSX 明细容量，请缩小导出范围");
            int excelRow = row.getRowNum() + 1;
            row.createCell(index).setCellValue("完整内容见长文本明细：主表行 " + excelRow + "，列 " + (index + 1) + "，共 " + chunks + " 片");
            for (int part = 0; part < chunks; part++) {
                int start = part * DETAIL_CHUNK_BYTES;
                byte[] chunk = Arrays.copyOfRange(bytes, start, Math.min(start + DETAIL_CHUNK_BYTES, bytes.length));
                write(details.createRow(detailRowIndex[0]++), List.of(String.valueOf(excelRow), String.valueOf(index + 1),
                        HEADERS.get(index), String.valueOf(part + 1), String.valueOf(chunks), Base64.getEncoder().encodeToString(chunk)));
            }
        }
    }

    static String formulaSafe(String value) {
        if (value == null || value.isEmpty()) return "";
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' ? "'" + value : value;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
