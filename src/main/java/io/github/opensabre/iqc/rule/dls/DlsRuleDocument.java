package io.github.opensabre.iqc.rule.dls;

import java.util.List;

/** Versioned, self-contained DLS source stored in a quality-rule expression and task snapshot. */
public record DlsRuleDocument(
        String languageVersion,
        Source source,
        List<Definition> definitions,
        String entryName,
        String entryExpression) {

    public record Source(String fileName, String sheetName) { }

    public record Definition(String name, String kind, String expression, String targetRole) { }
}
