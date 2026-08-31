package io.github.opensabre.iqc.governance;

import io.github.opensabre.governance.dictionary.DictionaryEnum;
import io.github.opensabre.governance.dictionary.OpenSabreDictionary;

/**
 * Agent 配置及版本审批状态字典。
 */
@OpenSabreDictionary(code = "iqc_agent_status", name = "IQC Agent 状态")
public enum IqcAgentStatus implements DictionaryEnum {
    DRAFT("DRAFT", "草稿", "default"),
    PENDING_APPROVAL("PENDING_APPROVAL", "待审批", "processing"),
    PUBLISHED("PUBLISHED", "已发布", "success"),
    REJECTED("REJECTED", "已驳回", "error"),
    DISABLED("DISABLED", "已停用", "default");

    private final String value;
    private final String label;
    private final String tagType;

    IqcAgentStatus(String value, String label, String tagType) {
        this.value = value;
        this.label = label;
        this.tagType = tagType;
    }

    @Override public String value() { return value; }
    @Override public String label() { return label; }
    @Override public String tagType() { return tagType; }
}
