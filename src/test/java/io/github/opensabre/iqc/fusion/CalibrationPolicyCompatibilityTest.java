package io.github.opensabre.iqc.fusion;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.quality.evaluation.EmpiricalConfidenceCalibration.Reference;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Adding opaque references must not rewrite historical fusion JSON or its content identity. */
class CalibrationPolicyCompatibilityTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void oldConstructorsAndAbsentNullOrEmptyReferencesKeepHistoricalJsonAndHash() throws Exception {
        String legacy="{\"id\":\"fusion\",\"version\":1,\"method\":\"PRIORITY\",\"nodePriority\":{},\"overrideNodeByCode\":{},\"confidenceCalibrationId\":null,\"calibratedNodeIds\":[]}";
        var seven=new FusionPolicy("fusion",1,FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of());
        var eight=new FusionPolicy("fusion",1,FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of(),Map.of());
        var nine=new FusionPolicy("fusion",1,FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of(),Map.of(),null);
        for(var policy:List.of(seven,eight,nine)) {
            String encoded=json.writeValueAsString(policy);
            assertThat(encoded).isEqualTo(legacy);
            assertThat(SelectorAssetService.hash(encoded)).isEqualTo(SelectorAssetService.hash(legacy));
        }
        for(String suffix:List.of("",",\"calibrationReferences\":null",",\"calibrationReferences\":[]")) {
            var restored=json.readValue(legacy.substring(0,legacy.length()-1)+suffix+"}",FusionPolicy.class);
            assertThat(restored).isEqualTo(seven);
            assertThat(json.writeValueAsString(restored)).isEqualTo(legacy);
        }
    }
    @Test void exactReferencesAreSortedImmutableAndBoundToDeclaredNodes() throws Exception {
        var a=new Reference("task","execution","a","a".repeat(64));
        var b=new Reference("task","execution","b","b".repeat(64));
        var input=new ArrayList<>(List.of(b,a));
        var policy=new FusionPolicy("fusion",1,FusionPolicy.Method.CONFIDENCE,Map.of(),Map.of(),"calibration",Set.of("a","b"),null,null,input);
        input.clear();
        assertThat(policy.calibrationReferences()).containsExactly(a,b);
        assertThatThrownBy(()->policy.calibrationReferences().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(json.readValue(json.writeValueAsString(policy),FusionPolicy.class)).isEqualTo(policy);
        assertThatThrownBy(()->new FusionPolicy("fusion",1,FusionPolicy.Method.CONFIDENCE,Map.of(),Map.of(),"calibration",Set.of("a"),null,null,List.of(a,b)))
                .hasMessageContaining("cover all");
        assertThatThrownBy(()->new FusionPolicy("fusion",1,FusionPolicy.Method.CONFIDENCE,Map.of(),Map.of(),"calibration",Set.of("a"),null,null,List.of(a,a)))
                .hasMessageContaining("duplicate");
        var many=new ArrayList<Reference>();for(int index=0;index<201;index++)many.add(new Reference("task","execution","node"+index,"a".repeat(64)));
        assertThatThrownBy(()->new FusionPolicy("fusion",1,FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),"calibration",Set.of(),null,null,many))
                .hasMessageContaining("200");
    }
}
