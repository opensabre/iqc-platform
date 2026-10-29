package io.github.opensabre.iqc.fusion;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.iqc.finding.Finding;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Trusted deployment SPI: deterministic local computation only; no model, I/O, clocks or side effects. */
public interface FusionExtension {
    record Definition(String strategyId,int version,String name,Map<String,String> defaultParameters){
        public Definition {
            FusionExtensionBinding.identity(strategyId);
            if(version<1||name==null||name.isBlank()||name.length()>128||name.codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Invalid fusion extension definition");
            defaultParameters=FusionExtensionBinding.parameters(defaultParameters);
        }
    }
    record Outcome(List<String> selectedIds,Finding.State state,JsonNode value,String explanation){
        public Outcome {
            selectedIds=List.copyOf(selectedIds);Objects.requireNonNull(state);
            value=value==null||value.isNull()?null:value.deepCopy();
            if(explanation==null||explanation.isBlank()||explanation.length()>4096)
                throw new IllegalArgumentException("Custom fusion requires a bounded explanation");
        }
        @Override public JsonNode value(){return value==null?null:value.deepCopy();}
    }
    Definition definition();
    void validateParameters(Map<String,String> parameters);
    Outcome evaluate(List<Finding> rawFindings,Map<String,String> parameters);
}
