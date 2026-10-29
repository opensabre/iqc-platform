package io.github.opensabre.iqc.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;

/** Deliberately limited schema dialect for NodeResult.variables; unsupported keywords fail publication. */
public final class ValueSchema {
    private static final Set<String> KEYS=Set.of("type","properties","required","enum","items","additionalProperties");
    private static final Set<String> TYPES=Set.of("object","array","string","number","integer","boolean","null");
    private ValueSchema() { }
    /** Checks the shared restricted dialect before freezing an execution contract. */
    public static void validateSchema(JsonNode schema) { if(schema!=null) validate(schema,0); }
    /** Validates a detector's structured input or output using the same dialect as node variables. */
    public static void checkData(JsonNode schema,JsonNode value) {
        validateSchema(schema);
        if(schema!=null) {
            if(value==null)throw new IllegalArgumentException("Structured value required");
            checkValue(schema,value,"data");
        }
    }
    private static void validate(JsonNode schema,int depth) {
        if(depth>16 || !schema.isObject()) throw new IllegalArgumentException("Invalid output schema");
        schema.fieldNames().forEachRemaining(key->{if(!KEYS.contains(key)) throw new IllegalArgumentException("Unsupported output schema keyword: "+key);});
        if(!schema.path("type").isTextual() || !TYPES.contains(schema.path("type").asText())) throw new IllegalArgumentException("Output schema requires supported type");
        if(schema.has("properties")) {
            if(!schema.path("type").asText().equals("object") || !schema.get("properties").isObject()) throw new IllegalArgumentException("Invalid properties");
            schema.get("properties").forEach(child->validate(child,depth+1));
        }
        if(schema.has("required")) {
            if(!schema.path("type").asText().equals("object") || !schema.get("required").isArray()) throw new IllegalArgumentException("Invalid required");
            for(var field:schema.get("required")) if(!field.isTextual()) throw new IllegalArgumentException("Invalid required field");
        }
        if(schema.has("additionalProperties") && (!schema.path("type").asText().equals("object") || !schema.get("additionalProperties").isBoolean())) throw new IllegalArgumentException("additionalProperties must be boolean");
        if(schema.has("enum") && (!schema.get("enum").isArray() || schema.get("enum").isEmpty())) throw new IllegalArgumentException("Invalid enum");
        if(schema.has("items")) {
            if(!schema.path("type").asText().equals("array")) throw new IllegalArgumentException("items requires array");
            validate(schema.get("items"),depth+1);
        }
    }
    static void check(JsonNode schema,Map<String,JsonNode> variables) {
        if(schema!=null) checkValue(schema,new ObjectMapper().valueToTree(variables),"variables");
    }
    private static void checkValue(JsonNode schema,JsonNode value,String path) {
        boolean valid=switch(schema.get("type").asText()) {
            case "object" -> value.isObject(); case "array" -> value.isArray(); case "string" -> value.isTextual();
            case "number" -> value.isNumber(); case "integer" -> value.isIntegralNumber(); case "boolean" -> value.isBoolean(); case "null" -> value.isNull(); default -> false;
        };
        if(!valid) throw new IllegalArgumentException("Output type mismatch: "+path);
        if(schema.has("enum")) {
            boolean matched=false; for(var candidate:schema.get("enum")) matched|=candidate.equals(value);
            if(!matched) throw new IllegalArgumentException("Output enum mismatch: "+path);
        }
        if(value.isObject()) {
            if(schema.has("required")) for(var required:schema.get("required")) if(!value.has(required.asText())) throw new IllegalArgumentException("Missing output: "+path+"/"+required.asText());
            var properties=schema.path("properties");
            value.fields().forEachRemaining(entry->{
                if(properties.has(entry.getKey())) checkValue(properties.get(entry.getKey()),entry.getValue(),path+"/"+entry.getKey());
                else if(schema.has("additionalProperties") && !schema.get("additionalProperties").asBoolean()) throw new IllegalArgumentException("Unexpected output field: "+entry.getKey());
            });
        }
        if(value.isArray() && schema.has("items")) for(var element:value) checkValue(schema.get("items"),element,path+"/[]");
    }
}
