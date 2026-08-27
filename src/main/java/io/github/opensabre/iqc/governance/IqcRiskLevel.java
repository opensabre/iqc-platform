package io.github.opensabre.iqc.governance;

import io.github.opensabre.governance.dictionary.DictionaryEnum;
import io.github.opensabre.governance.dictionary.OpenSabreDictionary;

@OpenSabreDictionary(code = "iqc_risk_level", name = "IQC 风险等级")
public enum IqcRiskLevel implements DictionaryEnum {
    LOW("LOW", "低风险", "success"),
    MEDIUM("MEDIUM", "中风险", "warning"),
    HIGH("HIGH", "高风险", "error");

    private final String value;
    private final String label;
    private final String tagType;

    IqcRiskLevel(String value, String label, String tagType) {
        this.value = value;
        this.label = label;
        this.tagType = tagType;
    }

    @Override public String value() { return value; }
    @Override public String label() { return label; }
    @Override public String tagType() { return tagType; }
}
