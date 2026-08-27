package io.github.opensabre.iqc.governance;

import io.github.opensabre.governance.dictionary.DictionaryEnum;
import io.github.opensabre.governance.dictionary.OpenSabreDictionary;

@OpenSabreDictionary(code = "iqc_target_role", name = "IQC 适用说话人")
public enum IqcTargetRole implements DictionaryEnum {
    ALL("all", "双方"),
    AGENT("agent", "客服/销售"),
    USER("user", "客户");

    private final String value;
    private final String label;

    IqcTargetRole(String value, String label) {
        this.value = value;
        this.label = label;
    }

    @Override public String value() { return value; }
    @Override public String label() { return label; }
}
