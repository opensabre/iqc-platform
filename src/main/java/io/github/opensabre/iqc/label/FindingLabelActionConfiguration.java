package io.github.opensabre.iqc.label;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** References a frozen policy binding; clients cannot supply label values, roles or evidence. */
public final class FindingLabelActionConfiguration {
    private FindingLabelActionConfiguration(){ }
    public record Target(String labelId,String valueCode) { }
    public static Target parse(JsonNode configuration) {
        if(configuration==null || !configuration.isObject() || configuration.size()!=2
                || !configuration.path("labelId").isTextual() || !configuration.path("valueCode").isTextual())
            throw new IllegalArgumentException("UPDATE_LABEL requires labelId and valueCode only");
        var fields=configuration.fieldNames();
        while(fields.hasNext())if(!Set.of("labelId","valueCode").contains(fields.next()))
            throw new IllegalArgumentException("Unknown UPDATE_LABEL configuration field");
        String labelId=configuration.path("labelId").asText(),valueCode=configuration.path("valueCode").asText();
        if(!labelId.matches("[A-Za-z0-9][A-Za-z0-9:_./@+-]{0,63}") || valueCode.isBlank() || valueCode.length()>64
                || valueCode.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid label action reference");
        return new Target(labelId,valueCode);
    }
}
