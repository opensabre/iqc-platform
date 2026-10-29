package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.InputCountManifest;
import io.github.opensabre.iqc.finding.Finding;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class InputCompletenessMetricsTest {
    final ObjectMapper json=new ObjectMapper();
    com.fasterxml.jackson.databind.node.ObjectNode fields(int count){
        return json.createObjectNode().put(InputCountManifest.VERSION_KEY,InputCountManifest.VERSION)
                .put(InputCountManifest.COUNT_KEY,count).put(InputCountManifest.REFERENCE_KEY,"upstream-batch");
    }
    GoldConversation gold(com.fasterxml.jackson.databind.JsonNode fields,int observed){
        var context=json.createObjectNode();context.putObject("conversation").putObject("businessContext").set("businessFields",fields);
        var messages=java.util.stream.IntStream.range(0,observed).mapToObj(i->new GoldConversation.Message("m"+i,"agent","processed",i+1,null)).toList();
        var findings=List.of(new GoldFinding("CHECK",Finding.ValueType.BOOLEAN,Finding.State.KNOWN,json.valueToTree(true),List.of()));
        return new GoldConversation(GoldConversation.SCHEMA,"sample","conversation",messages,context,findings,"annotator","reviewer","source",List.of(),GoldConversation.Difficulty.BASIC,null);
    }
    @Test void mixedCountsNeverUseExcessMessagesToCancelMissingOnes(){
        var metrics=new InputCompletenessMetrics();
        metrics.add(gold(fields(2),1));metrics.add(gold(fields(1),2));metrics.add(gold(fields(1),1));
        var result=metrics.summary();
        assertThat(result.measuredSamples()).isEqualTo(3);assertThat(result.deficientSamples()).isEqualTo(1);
        assertThat(result.excessSamples()).isEqualTo(1);assertThat(result.completeSamples()).isEqualTo(1);
        assertThat(result.expectedMessages()).isEqualTo(4);assertThat(result.observedMessages()).isEqualTo(4);
        assertThat(result.missingMessages()).isEqualTo(1);assertThat(result.extraMessages()).isEqualTo(1);
        assertThat(result.declaredMessageCoverage()).isEqualByComparingTo("0.75");
        assertThat(result.completeConversationRate()).isEqualByComparingTo("0.333333");
    }
    @Test void absentOrInvalidDeclarationsRemainInSampleDenominatorWithoutInventedCounts(){
        var metrics=new InputCompletenessMetrics();
        metrics.add(gold(json.createObjectNode().put(InputCountManifest.COUNT_KEY,2),1));
        metrics.add(gold(fields(0),1));metrics.add(gold(fields(1),1));
        var result=metrics.summary();
        assertThat(result.expectedSamples()).isEqualTo(3);assertThat(result.measuredSamples()).isEqualTo(1);
        assertThat(result.unmeasuredSamples()).isEqualTo(2);assertThat(result.invalidManifestSamples()).isEqualTo(1);
        assertThat(result.measurementCoverage()).isEqualByComparingTo("0.333333");
        assertThat(result.expectedMessages()).isEqualTo(1);assertThat(result.observedMessages()).isEqualTo(1);
    }
    @Test void emptyMeasurementDoesNotProducePerfectCompleteness(){
        var metrics=new InputCompletenessMetrics();assertThat(metrics.summary().measurementCoverage()).isNull();
        metrics.add(gold(json.createObjectNode(),1));
        assertThat(metrics.summary().measurementCoverage()).isEqualByComparingTo("0");
        assertThat(metrics.summary().completeConversationRate()).isNull();assertThat(metrics.summary().declaredMessageCoverage()).isNull();
    }
    @Test void manifestValidationAndFreezeUseOnlyExplicitVersionedFields(){
        var fields=fields(3);fields.put("privateText","not copied");var frozen=InputCountManifest.frozenFields(fields);
        assertThat(frozen.size()).isEqualTo(3);assertThat(frozen.has("privateText")).isFalse();
        fields.put(InputCountManifest.COUNT_KEY,4);assertThat(frozen.path(InputCountManifest.COUNT_KEY).intValue()).isEqualTo(3);
        for(var invalid:List.of(fields(0),fields(-1),fields(1).put(InputCountManifest.REFERENCE_KEY," bad "),
                fields(1).put(InputCountManifest.COUNT_KEY,1.5),fields(1).put(InputCountManifest.VERSION_KEY,"unknown"))){
            assertThat(InputCountManifest.observe(invalid,1).state()).isEqualTo(InputCountManifest.State.INVALID);
            var map=new java.util.HashMap<String,com.fasterxml.jackson.databind.JsonNode>();invalid.fields().forEachRemaining(e->map.put(e.getKey(),e.getValue()));
            assertThatThrownBy(()->InputCountManifest.validate(map)).hasMessageContaining("清单声明无效");
        }
    }
}
