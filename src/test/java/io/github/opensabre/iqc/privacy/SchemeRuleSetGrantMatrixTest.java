package io.github.opensabre.iqc.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.rule.model.*;
import io.github.opensabre.iqc.scheme.model.InspectionScheme;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Projection contracts; live authorization and browser behavior are separate checks. */
class SchemeRuleSetGrantMatrixTest {
    @Test void publishedProjectionKeepsVersionReferencesWhileWithholdingNestedDetectorBodies() throws Exception {
        var json=new ObjectMapper();var projection=new ResultReadProjection(json);
        var definition=new io.github.opensabre.iqc.scheme.SchemeDefinition(io.github.opensabre.iqc.scheme.SchemeDefinition.SCHEMA,
                java.util.List.of(new io.github.opensabre.iqc.scheme.SchemeDefinition.Item("greeting","Greeting",
                        new io.github.opensabre.iqc.scheme.SchemeDefinition.RuleReference("rule",1),io.github.opensabre.iqc.scheme.SchemeDefinition.HitMeaning.COMPLIANCE)),null,
                new io.github.opensabre.iqc.scoring.InspectionScoring.Policy("iqc-score-v2",io.github.opensabre.iqc.scoring.InspectionScoring.Mode.DEDUCTION,
                        100,60,java.util.List.of(new io.github.opensabre.iqc.scoring.InspectionScoring.Item("greeting",10,false))));
        var rule=json.createObjectNode().put("id","rule").put("versionNo",1).put("name","Rule").put("expression","PRIVATE_RULE");
        var dependencies=new io.github.opensabre.iqc.scheme.SchemeDependencyResolver.Dependencies(java.util.List.of(rule),json.createObjectNode().put("prompt","PRIVATE_AGENT"),"RULE_ONLY");
        var snapshot=new io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot("Scheme","SCHEME","PRIVATE_DESCRIPTION","Scene",definition,dependencies,null);
        var source=new io.github.opensabre.iqc.scheme.InspectionSchemeService.PublishedTemplate("scheme",1,"original-hash",snapshot);
        String before=json.writeValueAsString(snapshot);
        for(int mask=0;mask<8;mask++) {
            var access=new SensitiveReadAccessService.Access("test",(mask&1)!=0,(mask&2)!=0,(mask&4)!=0,Instant.now());
            var read=projection.publishedScheme(source,access);var body=json.valueToTree(read.snapshot());
            assertThat(read.contentHash()).isEqualTo("original-hash");
            assertThat(body.path("definition").path("items").get(0).path("rule").path("id").asText()).isEqualTo("rule");
            assertThat(body.path("dependencies").path("rules").get(0).path("versionNo").asInt()).isEqualTo(1);
            if(mask==7)assertThat(json.writeValueAsString(read.snapshot())).isEqualTo(before);
            else assertThat(json.writeValueAsString(read.snapshot())).doesNotContain("PRIVATE_RULE","PRIVATE_AGENT","PRIVATE_DESCRIPTION");
            assertThat(json.writeValueAsString(snapshot)).isEqualTo(before);
        }
    }
    @Test void grantsControlBodiesAndCommandsWithoutChangingStoredSources() throws Exception {
        var json=new ObjectMapper();var projection=new ResultReadProjection(json);
        var set=new QualityRuleSet();set.setId("set");set.setDescription("private description");
        var version=new QualityRuleSetVersion();version.setDescription(set.getDescription());
        var scheme=new InspectionScheme();scheme.setId("scheme");scheme.setDescription("private scheme");scheme.setDraftConfigJson("{invalid");
        String before=json.writeValueAsString(set),oldBefore=json.writeValueAsString(version),schemeBefore=json.writeValueAsString(scheme);
        for(int mask=0;mask<8;mask++) {
            var access=new SensitiveReadAccessService.Access("test",(mask&1)!=0,(mask&2)!=0,(mask&4)!=0,Instant.now());
            assertThat(projection.ruleSet(set,access,true).getDescription()).isEqualTo((mask&3)==3?set.getDescription():null);
            assertThat(projection.ruleSetVersion(version,access,true).getDescription()).isEqualTo((mask&3)==3?version.getDescription():null);
            assertThat(projection.ruleSet(set,access,false).getDescription()).isNull();
            assertThat(projection.ruleSetVersion(version,access,false).getDescription()).isNull();
            var shown=projection.schemeDraft(scheme,access,true);
            assertThat(shown.getDraftConfigJson()).isEqualTo(mask==7?scheme.getDraftConfigJson():null);
            assertThat(shown.getDescription()).isEqualTo(mask==7?scheme.getDescription():null);
            assertThat(shown.getSchemePresentation()).containsEntry("state","UNAVAILABLE");
            var receipt=projection.schemeDraft(scheme,access,false);
            assertThat(receipt.getDraftConfigJson()).isNull();assertThat(receipt.getDescription()).isNull();assertThat(receipt.getSchemePresentation()).isNull();
            assertThat(json.writeValueAsString(set)).isEqualTo(before);assertThat(json.writeValueAsString(version)).isEqualTo(oldBefore);
            assertThat(json.writeValueAsString(scheme)).isEqualTo(schemeBefore);
        }
    }
}
