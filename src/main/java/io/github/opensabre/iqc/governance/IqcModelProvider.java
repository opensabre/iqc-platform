package io.github.opensabre.iqc.governance;

import io.github.opensabre.governance.dictionary.DictionaryEnum;
import io.github.opensabre.governance.dictionary.OpenSabreDictionary;

/** Model providers available to IQC Agent configurations. */
@OpenSabreDictionary(code = "iqc_model_provider", name = "IQC 模型供应商")
public enum IqcModelProvider implements DictionaryEnum {
    SPRING_AI("SPRING_AI", "Spring AI / OpenAI 兼容"),
    OPENAI("OPENAI", "OpenAI"),
    AZURE_OPENAI("AZURE_OPENAI", "Azure OpenAI"),
    ANTHROPIC("ANTHROPIC", "Anthropic"),
    DASHSCOPE("DASHSCOPE", "阿里云百炼"),
    OLLAMA("OLLAMA", "Ollama 本地模型");

    private final String value;
    private final String label;
    IqcModelProvider(String value, String label) { this.value = value; this.label = label; }
    @Override public String value() { return value; }
    @Override public String label() { return label; }
}
