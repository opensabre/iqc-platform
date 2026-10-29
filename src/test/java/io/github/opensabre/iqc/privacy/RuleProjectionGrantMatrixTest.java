package io.github.opensabre.iqc.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.rule.model.QualityRule;
import io.github.opensabre.iqc.rule.model.QualityRuleVersion;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Independent projection matrix after combining privacy and exact protocol branches. */
class RuleProjectionGrantMatrixTest {
    private final ResultReadProjection projection=new ResultReadProjection(new ObjectMapper());
    @Test void ruleAndHistoricalBodiesRequireTheirGrantsWithoutMutatingSources() throws Exception {
        var json=new ObjectMapper();
        for(String type:java.util.List.of("REGEX","DLS","LLM"," LLM ","llm")) {
            var rule=new QualityRule();rule.setId("rule");rule.setRuleType(type);rule.setExpression(" original expression ");rule.setDescription("original description");
            var version=new QualityRuleVersion();version.setRuleType(type);version.setExpression(rule.getExpression());version.setDescription(rule.getDescription());
            String before=json.writeValueAsString(rule),oldBefore=json.writeValueAsString(version);
            for(int mask=0;mask<8;mask++) {
                var access=new SensitiveReadAccessService.Access("iqc-sensitive-read-access-v1",(mask&1)!=0,(mask&2)!=0,(mask&4)!=0,Instant.now());
                boolean visible=(mask&3)==3&&(!type.trim().equalsIgnoreCase("LLM")||(mask&4)!=0);
                var read=projection.rule(rule,access,true);var old=projection.ruleVersion(version,access,true);
                assertThat(read.getExpression()).as("type %s grants %s",type,mask).isEqualTo(visible?rule.getExpression():null);
                assertThat(old.getExpression()).isEqualTo(visible?version.getExpression():null);
                assertThat(read.getDescription()).isEqualTo(visible?rule.getDescription():null);
                assertThat(old.getDescription()).isEqualTo(visible?version.getDescription():null);
                assertThat(projection.rule(rule,access,false).getExpression()).isNull();
                assertThat(projection.ruleVersion(version,access,false).getExpression()).isNull();
                assertThat(json.writeValueAsString(rule)).isEqualTo(before);
                assertThat(json.writeValueAsString(version)).isEqualTo(oldBefore);
            }
        }
    }
}
