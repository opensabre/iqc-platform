package io.github.opensabre.iqc.label;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Frozen catalog metadata is embedded in the existing Pipeline release, never a parallel label version store. */
public record FindingLabelPolicy(String schemaVersion,List<Binding> bindings,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode directory) {
    public static final String SCHEMA="iqc-finding-label-policy-v1";
    public record Binding(String labelId,int labelVersion,String valueCode,String findingCode) {
        public Binding {
            if(labelId==null || !labelId.matches("[A-Za-z0-9][A-Za-z0-9:_./@+-]{0,63}") || labelVersion<1
                    || valueCode==null || valueCode.isBlank() || valueCode.length()>64 || valueCode.codePoints().anyMatch(Character::isISOControl)
                    || findingCode==null || !findingCode.matches("[A-Z][A-Z0-9_]{1,63}"))
                throw new IllegalArgumentException("Invalid finding label policy binding");
        }
    }
    public FindingLabelPolicy {
        if(!SCHEMA.equals(schemaVersion))throw new IllegalArgumentException("Invalid finding label policy schema");
        bindings=List.copyOf(Objects.requireNonNull(bindings));
        if(bindings.isEmpty() || bindings.size()>100)throw new IllegalArgumentException("Label policy requires 1 to 100 bindings");
        var keys=new java.util.HashSet<List<Object>>();var versions=new java.util.HashMap<String,Integer>();
        for(var binding:bindings) {
            if(!keys.add(List.of(binding.labelId(),binding.valueCode())))throw new IllegalArgumentException("Duplicate label value binding");
            var previous=versions.putIfAbsent(binding.labelId(),binding.labelVersion());
            if(previous!=null && previous!=binding.labelVersion())throw new IllegalArgumentException("Mixed label versions");
        }
        if(directory!=null && (!directory.isObject() || directory.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1024*1024))
            throw new IllegalArgumentException("Invalid or oversized frozen label directory");
        directory=directory==null?null:directory.deepCopy();
    }
    @Override public JsonNode directory(){return directory==null?null:directory.deepCopy();}
    public void validateReferences(Set<String> findingCodes) {
        for(var binding:bindings)if(!findingCodes.contains(binding.findingCode()))
            throw new IllegalArgumentException("Label policy references undeclared finding");
    }
    public FindingLabelProjection.Binding projectionBinding(Binding binding) {
        if(!bindings.contains(binding) || directory==null)throw new IllegalArgumentException("Label binding has no frozen directory");
        JsonNode label=null;
        for(var entry:directory.path("labels"))if(binding.labelId().equals(entry.path("id").asText())
                && entry.path("versionNo").isIntegralNumber() && entry.path("versionNo").canConvertToInt() && entry.path("versionNo").asInt()==binding.labelVersion()) {
            if(label!=null)throw new IllegalArgumentException("Duplicate frozen label");label=entry;
        }
        if(label==null)throw new IllegalArgumentException("Label binding is absent from frozen directory");
        JsonNode value=null;
        for(var entry:label.path("values"))if(binding.valueCode().equals(entry.path("valueCode").asText())) {
            if(value!=null)throw new IllegalArgumentException("Duplicate frozen label value");value=entry;
        }
        if(value==null)throw new IllegalArgumentException("Label value is absent from frozen directory");
        return new FindingLabelProjection.Binding(binding.labelId(),binding.labelVersion(),binding.valueCode(),
                value.path("valueType").asText(),label.path("targetRole").asText(),binding.findingCode());
    }
    public void validateFindingTypes(java.util.Map<String,io.github.opensabre.iqc.finding.Finding.ValueType> types) {
        for(var binding:bindings) {
            var actual=types.get(binding.findingCode());var expected=projectionBinding(binding).valueType();
            boolean compatible=actual!=null && switch(expected) {
                case "FIXED" -> actual!=io.github.opensabre.iqc.finding.Finding.ValueType.OBJECT;
                case "BOOLEAN" -> actual==io.github.opensabre.iqc.finding.Finding.ValueType.BOOLEAN;
                case "PERCENTAGE","DURATION_MONTHS","MONTH" -> actual==io.github.opensabre.iqc.finding.Finding.ValueType.NUMBER;
                case "DATE" -> actual==io.github.opensabre.iqc.finding.Finding.ValueType.STRING || actual==io.github.opensabre.iqc.finding.Finding.ValueType.ENUM;
                default -> false;
            };
            if(!compatible)throw new IllegalArgumentException("Finding type does not match frozen label value type");
        }
    }
    public void validateFrozen() {
        if(directory==null || !"2.0".equals(directory.path("schemaVersion").asText()) || !directory.path("labels").isArray())
            throw new IllegalArgumentException("Missing frozen label directory");
        var expected=new java.util.HashSet<String>();bindings.forEach(binding->expected.add(binding.labelId()));
        var actual=new java.util.HashSet<String>();
        for(var label:directory.path("labels")) {
            if(!label.isObject() || !actual.add(label.path("id").asText()) || !label.path("name").isTextual()
                    || label.path("name").asText().isBlank() || !label.path("values").isArray())
                throw new IllegalArgumentException("Invalid frozen label identity");
        }
        if(!actual.equals(expected))throw new IllegalArgumentException("Frozen label directory coverage mismatch");
        bindings.forEach(this::projectionBinding);
    }
}
