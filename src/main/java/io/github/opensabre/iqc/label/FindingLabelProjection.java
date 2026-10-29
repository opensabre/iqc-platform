package io.github.opensabre.iqc.label;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.fusion.FindingFusion;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Pure projection into the existing label value vocabulary; never creates fake rule results. */
public final class FindingLabelProjection {
    public static final String SCHEMA="iqc-label-finding-projection-v1";
    private FindingLabelProjection(){ }
    /** A binding is a frozen policy declaration, not evidence that an engine identified a participant. */
    public record Binding(String labelId,int labelVersion,String valueCode,String valueType,String targetRole,String findingCode) {
        public Binding {
            reference(labelId,64);code(valueCode,64);code(findingCode,128);
            if(labelVersion<1 || !Set.of("FIXED","BOOLEAN","PERCENTAGE","DURATION_MONTHS","MONTH","DATE").contains(Objects.toString(valueType,""))
                    || !Set.of("user","customer","agent","all").contains(Objects.toString(targetRole,"")))
                throw new IllegalArgumentException("Invalid finding label binding");
        }
    }
    public enum Status {KNOWN,UNKNOWN,CONFLICT,ERROR,NOT_EVALUATED}
    public record Projection(String schemaVersion,Binding binding,Status status,JsonNode value,String reasonCode,
                             String fusionPolicyId,Integer fusionPolicyVersion,List<String> selectedFindingIds,
                             List<Finding> rawFindings,List<Finding.Evidence> evidence) {
        public Projection {
            if(!SCHEMA.equals(schemaVersion))throw new IllegalArgumentException("Invalid label projection schema");
            Objects.requireNonNull(binding);Objects.requireNonNull(status);reference(reasonCode,64);
            selectedFindingIds=List.copyOf(Objects.requireNonNull(selectedFindingIds));
            rawFindings=List.copyOf(Objects.requireNonNull(rawFindings));evidence=List.copyOf(Objects.requireNonNull(evidence));
            if(status==Status.KNOWN && !LabelFactEvaluator.validValue(binding.valueType(),value)
                    || status!=Status.KNOWN && value!=null && !value.isNull())
                throw new IllegalArgumentException("Label projection state and value disagree");
            var ids=new java.util.HashSet<String>();
            for(var finding:rawFindings)if(!binding.findingCode().equals(finding.code()) || !ids.add(finding.id()))
                throw new IllegalArgumentException("Label projection raw finding identity mismatch");
            if(selectedFindingIds.size()!=new java.util.HashSet<>(selectedFindingIds).size() || !ids.containsAll(selectedFindingIds))
                throw new IllegalArgumentException("Label projection selected findings mismatch");
            if(status==Status.KNOWN) {
                if(selectedFindingIds.isEmpty())throw new IllegalArgumentException("Known label requires selected findings");
                for(var id:selectedFindingIds) {
                    Finding selected=null;
                    for(var finding:rawFindings)if(id.equals(finding.id())){selected=finding;break;}
                    if(selected==null || selected.state()!=Finding.State.KNOWN || !sameValue(selected.value(),value))
                        throw new IllegalArgumentException("Known label value must match selected known findings");
                }
            }
            if(rawFindings.isEmpty()) {
                if(fusionPolicyId!=null || fusionPolicyVersion!=null || !selectedFindingIds.isEmpty() || !evidence.isEmpty()
                        || status!=Status.NOT_EVALUATED)throw new IllegalArgumentException("Missing label source must stay not evaluated");
            } else if(fusionPolicyId==null || fusionPolicyId.isBlank() || fusionPolicyVersion==null || fusionPolicyVersion<1)
                throw new IllegalArgumentException("Label projection requires real fusion policy identity");
            var allEvidence=new java.util.HashSet<Finding.Evidence>();rawFindings.forEach(finding->allEvidence.addAll(finding.evidence()));
            if(!allEvidence.containsAll(evidence) || evidence.size()!=new java.util.HashSet<>(evidence).size())
                throw new IllegalArgumentException("Label projection evidence must come from raw findings");
            value=value==null || value.isNull()?null:value.deepCopy();
        }
        @Override public JsonNode value(){return value==null?null:value.deepCopy();}
    }
    /** Stored projections already use Selector exact hashes, independently of the source Release version. */
    public static Projection read(com.fasterxml.jackson.databind.ObjectMapper json,String body)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        return json.readerFor(Projection.class)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(body);
    }

    public static Projection project(Binding binding,FindingFusion.Result source) {
        Objects.requireNonNull(binding);
        if(source==null)return new Projection(SCHEMA,binding,Status.NOT_EVALUATED,null,"FINDING_MISSING",null,null,List.of(),List.of(),List.of());
        if(!binding.findingCode().equals(source.code()))throw new IllegalArgumentException("Label binding and finding code mismatch");
        Status status=Status.valueOf(source.state().name());
        JsonNode value=null;String reason="FINDING_"+source.state().name();
        if(status==Status.KNOWN) {
            if(LabelFactEvaluator.validValue(binding.valueType(),source.value())){value=source.value();reason="FINDING_VALUE_PROJECTED";}
            else {status=Status.ERROR;reason="LABEL_VALUE_TYPE_MISMATCH";}
        }
        return new Projection(SCHEMA,binding,status,value,reason,source.policyId(),source.policyVersion(),
                source.selectedIds(),source.rawFindings(),source.evidence());
    }
    private static boolean sameValue(JsonNode first,JsonNode second) {
        return first!=null && second!=null && (first.isNumber() && second.isNumber()
                ?first.decimalValue().compareTo(second.decimalValue())==0:first.equals(second));
    }
    private static void code(String value,int max) {
        if(value==null || value.isBlank() || value.length()>max || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid finding label code");
    }
    private static void reference(String value,int max) {
        if(value==null || value.length()>max || !value.matches("[A-Za-z0-9][A-Za-z0-9:_./@+-]*"))
            throw new IllegalArgumentException("Invalid finding label reference");
    }
}
