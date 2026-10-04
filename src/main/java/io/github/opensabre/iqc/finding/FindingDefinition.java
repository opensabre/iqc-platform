package io.github.opensabre.iqc.finding;

import java.util.List;
import java.util.Objects;

/** Versioned semantic contract shared by independently configured engines. */
public record FindingDefinition(String schemaVersion, String code, String name, String category,
                                String description, Finding.ValueType valueType, List<String> allowedValues,
                                Finding.Severity defaultSeverity, boolean evidenceRequired,
                                List<Finding.EvidenceType> evidenceTypes) {
    public static final String SCHEMA = "iqc-finding-definition-v1";
    public FindingDefinition {
        if (!SCHEMA.equals(schemaVersion)) throw new IllegalArgumentException("unsupported finding definition schema");
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{1,63}")) throw new IllegalArgumentException("invalid finding code");
        if (name == null || name.isBlank() || name.length() > 100) throw new IllegalArgumentException("finding name is required, maximum 100 characters");
        if (category == null || category.isBlank() || category.length() > 100) throw new IllegalArgumentException("finding category is required");
        if (description != null && description.length() > 2000) throw new IllegalArgumentException("finding description is too long");
        Objects.requireNonNull(valueType, "valueType"); Objects.requireNonNull(defaultSeverity, "defaultSeverity");
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
        evidenceTypes = evidenceTypes == null ? List.of() : List.copyOf(evidenceTypes);
        if (allowedValues.size() > 100 || allowedValues.stream().anyMatch(value -> value.isBlank() || value.length() > 256)
                || allowedValues.stream().distinct().count() != allowedValues.size()) throw new IllegalArgumentException("invalid enum values");
        if ((valueType == Finding.ValueType.ENUM) != !allowedValues.isEmpty()) throw new IllegalArgumentException("enum findings require exclusive allowedValues");
        if (evidenceTypes.stream().distinct().count() != evidenceTypes.size()) throw new IllegalArgumentException("duplicate evidence type");
        if ((defaultSeverity == Finding.Severity.HIGH || defaultSeverity == Finding.Severity.CRITICAL) && !evidenceRequired)
            throw new IllegalArgumentException("high-risk findings require supporting evidence");
        if (evidenceRequired && evidenceTypes.isEmpty()) throw new IllegalArgumentException("evidence requirement needs allowed types");
    }
    public void validate(Finding finding) {
        if (!code.equals(finding.code()) || valueType != finding.valueType() || !category.equals(finding.category()))
            throw new IllegalArgumentException("finding differs from its frozen semantic definition");
        if (finding.state() == Finding.State.KNOWN && valueType == Finding.ValueType.ENUM
                && !allowedValues.contains(finding.value().textValue())) throw new IllegalArgumentException("unknown enum value");
        if (finding.state() == Finding.State.KNOWN && (evidenceRequired || finding.severity() == Finding.Severity.HIGH
                || finding.severity() == Finding.Severity.CRITICAL) && finding.evidence().isEmpty())
            throw new IllegalArgumentException("known finding requires evidence");
        if (!evidenceTypes.isEmpty() && finding.evidence().stream().anyMatch(evidence -> !evidenceTypes.contains(evidence.type())))
            throw new IllegalArgumentException("unsupported evidence type");
    }
}
