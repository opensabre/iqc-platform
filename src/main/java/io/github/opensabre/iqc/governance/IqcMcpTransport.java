package io.github.opensabre.iqc.governance;

import io.github.opensabre.governance.dictionary.DictionaryEnum;
import io.github.opensabre.governance.dictionary.OpenSabreDictionary;

/** MCP transports supported by IQC Agent configurations. */
@OpenSabreDictionary(code = "iqc_mcp_transport", name = "IQC MCP 传输类型")
public enum IqcMcpTransport implements DictionaryEnum {
    STREAMABLE_HTTP("STREAMABLE_HTTP", "Streamable HTTP"),
    SSE("SSE", "SSE");

    private final String value;
    private final String label;
    IqcMcpTransport(String value, String label) { this.value = value; this.label = label; }
    @Override public String value() { return value; }
    @Override public String label() { return label; }
}
