package io.github.opensabre.iqc.rule.dls;

import io.github.opensabre.iqc.governance.IqcException;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Reads the constrained OOXML table used by legacy DLS workbooks without executing workbook content. */
@Component
public class DlsExcelParser {
    private static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_UNCOMPRESSED_BYTES = 30 * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 2_000;
    private static final int MAX_ROWS_PER_SHEET = 1_000;
    private static final Pattern CELL_COLUMN = Pattern.compile("([A-Z]+)");

    public List<SheetDraft> parse(byte[] content, String fileName, boolean excludeTests) {
        if (content == null || content.length == 0) throw IqcException.invalidArgument("DLS 文件不能为空");
        if (content.length > MAX_FILE_BYTES) throw IqcException.invalidArgument("DLS 文件不能超过 10MB");
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx"))
            throw IqcException.invalidArgument("仅支持 .xlsx DLS 文件");
        Map<String, byte[]> parts = unzip(content);
        if (parts.containsKey("xl/vbaProject.bin")) throw IqcException.invalidArgument("DLS 文件不能包含宏");
        Document workbook = xml(required(parts, "xl/workbook.xml"));
        Document relationships = xml(required(parts, "xl/_rels/workbook.xml.rels"));
        Map<String, String> targets = relationshipTargets(relationships);
        List<String> sharedStrings = parts.containsKey("xl/sharedStrings.xml")
                ? sharedStrings(xml(parts.get("xl/sharedStrings.xml"))) : List.of();
        List<SheetDraft> result = new ArrayList<>();
        NodeList sheets = workbook.getElementsByTagNameNS("*", "sheet");
        for (int index = 0; index < sheets.getLength(); index++) {
            Element sheet = (Element) sheets.item(index);
            String name = sheet.getAttribute("name");
            String relationshipId = sheet.getAttributeNS(
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
            String target = targets.get(relationshipId);
            if (target == null) throw IqcException.invalidArgument("工作表关系缺失: " + name);
            String part = target.startsWith("/") ? target.substring(1) : normalizeWorksheetTarget(target);
            byte[] sheetXml = parts.get(part);
            if (sheetXml == null) throw IqcException.invalidArgument("工作表内容缺失: " + name);
            SheetDraft draft = parseSheet(xml(sheetXml), fileName, name, sharedStrings, excludeTests);
            if (draft != null) result.add(draft);
        }
        if (result.isEmpty()) throw IqcException.invalidArgument("DLS 文件没有可导入的工作表");
        return result;
    }

    private SheetDraft parseSheet(Document sheet, String fileName, String sheetName,
                                  List<String> sharedStrings, boolean excludeTests) {
        NodeList rows = sheet.getElementsByTagNameNS("*", "row");
        if (rows.getLength() == 0) return null;
        if (rows.getLength() > MAX_ROWS_PER_SHEET) throw IqcException.invalidArgument("工作表行数超过 1000: " + sheetName);
        List<DlsRuleDocument.Definition> definitions = new ArrayList<>();
        String entryName = null;
        String entryExpression = null;
        List<String> warnings = new ArrayList<>();
        Map<String, Integer> names = new LinkedHashMap<>();
        for (int index = 0; index < rows.getLength(); index++) {
            Element row = (Element) rows.item(index);
            Map<Integer, String> values = rowValues(row, sharedStrings);
            String name = clean(values.get(1));
            String expression = clean(values.get(2));
            String role = normalizeRole(clean(values.get(3)));
            if (name == null && expression == null) continue;
            if (name == null || expression == null)
                throw IqcException.invalidArgument("工作表 " + sheetName + " 第 " + row.getAttribute("r") + " 行名称或表达式为空");
            if (excludeTests && isTestDefinition(name)) {
                warnings.add("已排除测试定义: " + name);
                continue;
            }
            Integer previous = names.putIfAbsent(name, index + 1);
            if (previous != null) throw IqcException.invalidArgument("工作表 " + sheetName + " 存在重复定义: " + name);
            if (name.startsWith("slot_")) {
                definitions.add(new DlsRuleDocument.Definition(name, "SLOT", expression, role));
            } else if (name.startsWith("rule_")) {
                definitions.add(new DlsRuleDocument.Definition(name, "RULE", expression, role));
            } else {
                if (entryName != null) throw IqcException.invalidArgument("工作表 " + sheetName + " 包含多个最终规则");
                entryName = name;
                entryExpression = expression;
            }
        }
        if (entryName == null) throw IqcException.invalidArgument("工作表 " + sheetName + " 缺少最终规则");
        if (excludeTests) entryExpression = removeTestReferences(entryExpression);
        DlsRuleDocument document = new DlsRuleDocument("1.0",
                new DlsRuleDocument.Source(fileName, sheetName), List.copyOf(definitions), entryName, entryExpression);
        return new SheetDraft(sheetName, document, List.copyOf(warnings));
    }

    private Map<Integer, String> rowValues(Element row, List<String> sharedStrings) {
        Map<Integer, String> values = new HashMap<>();
        NodeList cells = row.getElementsByTagNameNS("*", "c");
        for (int index = 0; index < cells.getLength(); index++) {
            Element cell = (Element) cells.item(index);
            Matcher matcher = CELL_COLUMN.matcher(cell.getAttribute("r"));
            if (!matcher.find()) continue;
            int column = columnNumber(matcher.group(1));
            String type = cell.getAttribute("t");
            String value;
            if ("inlineStr".equals(type)) value = descendantText(cell, "t");
            else {
                String raw = descendantText(cell, "v");
                if (raw == null) value = null;
                else if ("s".equals(type)) {
                    int sharedIndex = Integer.parseInt(raw);
                    if (sharedIndex < 0 || sharedIndex >= sharedStrings.size())
                        throw IqcException.invalidArgument("共享字符串索引越界");
                    value = sharedStrings.get(sharedIndex);
                } else value = raw;
            }
            values.put(column, value);
        }
        return values;
    }

    private Map<String, byte[]> unzip(byte[] content) {
        Map<String, byte[]> parts = new HashMap<>();
        int total = 0;
        int entryCount = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (++entryCount > MAX_ZIP_ENTRIES) throw IqcException.invalidArgument("DLS 文件条目数量超过 2000");
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../")) throw IqcException.invalidArgument("DLS 文件包含非法路径");
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    total += read;
                    if (total > MAX_UNCOMPRESSED_BYTES) throw IqcException.invalidArgument("DLS 文件解压内容超过 30MB");
                }
                if (parts.putIfAbsent(name, output.toByteArray()) != null)
                    throw IqcException.invalidArgument("DLS 文件包含重复条目: " + name);
            }
            return parts;
        } catch (IqcException exception) {
            throw exception;
        } catch (Exception exception) {
            throw IqcException.invalidArgument("DLS 文件不是有效的 XLSX", exception);
        }
    }

    private Document xml(byte[] content) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(content));
        } catch (Exception exception) {
            throw IqcException.invalidArgument("DLS 文件包含无效 XML", exception);
        }
    }

    private Map<String, String> relationshipTargets(Document relationships) {
        Map<String, String> result = new HashMap<>();
        NodeList nodes = relationships.getElementsByTagNameNS("*", "Relationship");
        for (int index = 0; index < nodes.getLength(); index++) {
            Element element = (Element) nodes.item(index);
            result.put(element.getAttribute("Id"), element.getAttribute("Target"));
        }
        return result;
    }

    private List<String> sharedStrings(Document document) {
        List<String> result = new ArrayList<>();
        NodeList items = document.getElementsByTagNameNS("*", "si");
        for (int index = 0; index < items.getLength(); index++) result.add(allDescendantText(items.item(index), "t"));
        return result;
    }

    private String descendantText(Element element, String localName) {
        NodeList nodes = element.getElementsByTagNameNS("*", localName);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }

    private String allDescendantText(Node node, String localName) {
        StringBuilder result = new StringBuilder();
        if (node instanceof Element element) {
            NodeList nodes = element.getElementsByTagNameNS("*", localName);
            for (int index = 0; index < nodes.getLength(); index++) result.append(nodes.item(index).getTextContent());
        }
        return result.toString();
    }

    private byte[] required(Map<String, byte[]> parts, String name) {
        byte[] result = parts.get(name);
        if (result == null) throw IqcException.invalidArgument("DLS 文件缺少 " + name);
        return result;
    }

    private String normalizeWorksheetTarget(String target) {
        String normalized = target.startsWith("xl/") ? target : "xl/" + target;
        while (normalized.contains("/../")) normalized = normalized.replaceFirst("[^/]+/\\.\\./", "");
        return normalized;
    }

    private int columnNumber(String letters) {
        int value = 0;
        for (char letter : letters.toCharArray()) value = value * 26 + letter - 'A' + 1;
        return value;
    }

    private String clean(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private String normalizeRole(String role) {
        if (role == null) return "ALL";
        return switch (role.toLowerCase(Locale.ROOT)) {
            case "坐席", "客服", "agent" -> "agent";
            case "客户", "用户", "customer", "user" -> "user";
            case "全文", "双方", "all" -> "all";
            default -> role.toLowerCase(Locale.ROOT);
        };
    }

    private boolean isTestDefinition(String name) {
        return name.startsWith("slot_测试") || name.startsWith("rule_测试");
    }

    private String removeTestReferences(String expression) {
        return Pattern.compile("(?:^|;)\\s*\\[?rule_测试[^;\\]]*]?\\s*(?=;|$)")
                .matcher(expression).replaceAll("").replaceAll("^;+|;+$", "");
    }

    public record SheetDraft(String sheetName, DlsRuleDocument document, List<String> warnings) { }
}
