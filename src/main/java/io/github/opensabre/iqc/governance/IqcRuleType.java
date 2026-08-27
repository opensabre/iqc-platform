package io.github.opensabre.iqc.governance;

import io.github.opensabre.governance.dictionary.DictionaryEnum;
import io.github.opensabre.governance.dictionary.OpenSabreDictionary;

@OpenSabreDictionary(code = "iqc_rule_type", name = "IQC 规则类型")
public enum IqcRuleType implements DictionaryEnum {
    KEYWORD("KEYWORD", "关键词"),
    CONTAINS("CONTAINS", "包含任一内容"),
    FORBIDDEN_CONTAINS("FORBIDDEN_CONTAINS", "禁止包含"),
    REQUIRED_CONTAINS("REQUIRED_CONTAINS", "必须包含"),
    REGEX("REGEX", "正则表达式"),
    FORBIDDEN_REGEX("FORBIDDEN_REGEX", "禁止匹配正则"),
    REQUIRED_REGEX("REQUIRED_REGEX", "必须匹配正则"),
    EQUALS("EQUALS", "完全等于"),
    NOT_EQUALS("NOT_EQUALS", "不等于"),
    STARTS_WITH("STARTS_WITH", "开头匹配"),
    ENDS_WITH("ENDS_WITH", "结尾匹配"),
    STRUCTURED("STRUCTURED", "结构化条件"),
    COMPOSITE("COMPOSITE", "组合规则"),
    LLM("LLM", "LLM 语义判断");

    private final String value;
    private final String label;

    IqcRuleType(String value, String label) {
        this.value = value;
        this.label = label;
    }

    @Override public String value() { return value; }
    @Override public String label() { return label; }
}
