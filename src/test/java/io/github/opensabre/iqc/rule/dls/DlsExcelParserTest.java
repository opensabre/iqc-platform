package io.github.opensabre.iqc.rule.dls;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DlsExcelParserTest {
    @Test
    void parsesWorkbookRoleAndRemovesTestDefinitions() throws Exception {
        var result = new DlsExcelParser().parse(workbook(), "辱骂model.xlsx", true);

        assertThat(result).hasSize(1);
        DlsRuleDocument document = result.get(0).document();
        assertThat(document.source().sheetName()).isEqualTo("辱骂");
        assertThat(document.definitions()).extracting(DlsRuleDocument.Definition::name)
                .containsExactly("slot_辱骂", "rule_辱骂客户");
        assertThat(document.definitions().get(1).targetRole()).isEqualTo("agent");
        assertThat(document.entryExpression()).isEqualTo("[rule_辱骂客户]");
        assertThat(result.get(0).warnings()).hasSize(2);
    }

    private byte[] workbook() throws Exception {
        Map<String, String> parts = Map.of(
                "xl/workbook.xml", """
                        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                          <sheets><sheet name="辱骂" sheetId="1" r:id="rId1"/></sheets></workbook>""",
                "xl/_rels/workbook.xml.rels", """
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                          <Relationship Id="rId1" Target="worksheets/sheet1.xml" Type="worksheet"/></Relationships>""",
                "xl/sharedStrings.xml", """
                        <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                          <si><t>slot_辱骂</t></si><si><t>笨蛋</t></si>
                          <si><t>rule_辱骂客户</t></si><si><t>[slot_辱骂]</t></si><si><t>坐席</t></si>
                          <si><t>slot_测试词</t></si><si><t>测试</t></si>
                          <si><t>rule_测试</t></si><si><t>[slot_测试词]</t></si>
                          <si><t>辱骂客户</t></si><si><t>[rule_辱骂客户];[rule_测试]</t></si>
                        </sst>""",
                "xl/worksheets/sheet1.xml", """
                        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                          <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
                          <row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2" t="s"><v>3</v></c><c r="C2" t="s"><v>4</v></c></row>
                          <row r="3"><c r="A3" t="s"><v>5</v></c><c r="B3" t="s"><v>6</v></c></row>
                          <row r="4"><c r="A4" t="s"><v>7</v></c><c r="B4" t="s"><v>8</v></c><c r="C4" t="s"><v>4</v></c></row>
                          <row r="5"><c r="A5" t="s"><v>9</v></c><c r="B5" t="s"><v>10</v></c></row>
                        </sheetData></worksheet>"""
        );
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
