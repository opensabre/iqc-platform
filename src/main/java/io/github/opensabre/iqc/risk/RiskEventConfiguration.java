package io.github.opensabre.iqc.risk;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** Business metadata only. Severity, evidence and source identity come from verified production. */
public final class RiskEventConfiguration {
    private RiskEventConfiguration() { }
    public record Metadata(String category,String title,String description) {
        public Metadata {
            if(category==null || !category.matches("[A-Za-z0-9][A-Za-z0-9:_./@+-]{0,63}"))
                throw new IllegalArgumentException("Invalid risk category");
            title=text(title,128,"title");
            description=text(description,1000,"description");
        }
    }
    public static Metadata parse(JsonNode configuration) {
        if(configuration==null || !configuration.isObject() || configuration.size()!=3)
            throw new IllegalArgumentException("CREATE_RISK_EVENT requires category, title and description only");
        var fields=configuration.fieldNames();
        while(fields.hasNext())if(!Set.of("category","title","description").contains(fields.next()))
            throw new IllegalArgumentException("Unknown CREATE_RISK_EVENT configuration field");
        for(String name:Set.of("category","title","description"))
            if(!configuration.path(name).isTextual())throw new IllegalArgumentException("Risk metadata must be text");
        return new Metadata(configuration.path("category").textValue(),configuration.path("title").textValue(),
                configuration.path("description").textValue());
    }
    private static String text(String value,int maximum,String name) {
        if(value==null || value.isBlank() || value.length()>maximum
                || value.codePoints().anyMatch(c->Character.isISOControl(c) && c!='\n' && c!='\t'))
            throw new IllegalArgumentException("Invalid bounded risk "+name);
        return value.trim();
    }
}
