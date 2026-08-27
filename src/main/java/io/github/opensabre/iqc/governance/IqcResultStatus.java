package io.github.opensabre.iqc.governance;

import io.github.opensabre.governance.dictionary.DictionaryEnum;
import io.github.opensabre.governance.dictionary.OpenSabreDictionary;

@OpenSabreDictionary(code = "iqc_result_status", name = "IQC 质检结果状态")
public enum IqcResultStatus implements DictionaryEnum {
    HIT("HIT", "命中", "error"),
    NOT_HIT("NOT_HIT", "未命中", "success"),
    PARTIAL_ERROR("PARTIAL_ERROR", "部分错误", "warning"),
    ERROR("ERROR", "错误", "error");

    private final String value;
    private final String label;
    private final String tagType;

    IqcResultStatus(String value, String label, String tagType) {
        this.value = value;
        this.label = label;
        this.tagType = tagType;
    }

    @Override public String value() { return value; }
    @Override public String label() { return label; }
    @Override public String tagType() { return tagType; }
}
