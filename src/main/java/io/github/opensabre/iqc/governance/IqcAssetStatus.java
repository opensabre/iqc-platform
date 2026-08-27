package io.github.opensabre.iqc.governance;

import io.github.opensabre.governance.dictionary.DictionaryEnum;
import io.github.opensabre.governance.dictionary.OpenSabreDictionary;

/** Lifecycle states shared by reusable Agent assets. */
@OpenSabreDictionary(code = "iqc_agent_asset_status", name = "IQC Agent 资产状态")
public enum IqcAssetStatus implements DictionaryEnum {
    ENABLED("ENABLED", "启用", "success"), DISABLED("DISABLED", "停用", "default");
    private final String value; private final String label; private final String tagType;
    IqcAssetStatus(String value, String label, String tagType) { this.value = value; this.label = label; this.tagType = tagType; }
    @Override public String value() { return value; }
    @Override public String label() { return label; }
    @Override public String tagType() { return tagType; }
}
