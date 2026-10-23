package io.github.opensabre.iqc.schedule;

import io.github.opensabre.iqc.selector.SelectorAssetService;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class PeriodicDefinitionTest {
    private static Instant at(String value) { return Instant.parse(value); }
    private PeriodicDefinition definition(String cron, String zone, String from, String until, PeriodicDefinition.MisfirePolicy policy) {
        return new PeriodicDefinition(PeriodicDefinition.SCHEMA, cron, zone, at(from), until == null ? null : at(until), policy, 10, 2,
                new PeriodicDefinition.PipelineReference("pipeline", 1), new SelectorAssetService.Reference("selector", 1));
    }
    private PeriodicDefinition minute(PeriodicDefinition.MisfirePolicy policy) {
        return definition("0 * * * * *", "UTC", "2026-10-06T00:00:00Z", null, policy);
    }
    @Test
    void firstTickIncludesEffectiveStartAndExcludesEffectiveEnd() {
        var plan = definition("0 * * * * *", "UTC", "2026-10-06T00:00:00Z", "2026-10-06T00:02:00Z", PeriodicDefinition.MisfirePolicy.SKIP);
        assertThat(plan.first()).isEqualTo(at("2026-10-06T00:00:00Z"));
        assertThat(plan.preview(at("2026-10-05T00:00:00Z"), 50)).containsExactly(at("2026-10-06T00:00:00Z"), at("2026-10-06T00:01:00Z"));
        assertThat(plan.next(at("2026-10-06T00:01:00Z"))).isNull();
    }
    @Test
    void graceBoundaryRunsThirtySecondLateTickButSkipsAtThirtyOne() {
        var plan = minute(PeriodicDefinition.MisfirePolicy.SKIP); var due = plan.first();
        assertThat(plan.tick(due, due.plusSeconds(30)).skip()).isFalse();
        var tick = plan.tick(due, due.plusSeconds(31));
        assertThat(tick.skip()).isTrue(); assertThat(tick.nextAt()).isEqualTo(due.plusSeconds(60));
        assertThat(tick.missedFrom()).isEqualTo(due); assertThat(tick.missedUntil()).isEqualTo(due.plusNanos(1));
    }
    @Test
    void fireLatestUsesOriginalLatestPlannedTickRatherThanObservationTime() {
        var plan = minute(PeriodicDefinition.MisfirePolicy.FIRE_LATEST); var due = plan.first();
        var tick = plan.tick(due, due.plusSeconds(615));
        assertThat(tick.plannedAt()).isEqualTo(due.plusSeconds(600));
        assertThat(tick.nextAt()).isEqualTo(due.plusSeconds(660));
        assertThat(tick.missedFrom()).isEqualTo(due); assertThat(tick.missedUntil()).isEqualTo(due.plusSeconds(600));
        assertThat(tick.skip()).isFalse();
    }
    @Test
    void catchUpAdvancesExactlyOneHistoricalTickPerMaterialization() {
        var plan = minute(PeriodicDefinition.MisfirePolicy.CATCH_UP); var due = plan.first();
        var tick = plan.tick(due, due.plusSeconds(615));
        assertThat(tick.plannedAt()).isEqualTo(due); assertThat(tick.nextAt()).isEqualTo(due.plusSeconds(60));
        assertThat(tick.skip()).isFalse(); assertThat(tick.missedFrom()).isNull();
    }
    @Test
    void expiredScheduleHasNoFutureTickAndLatestRemainsInsideRange() {
        var plan = definition("0 * * * * *", "UTC", "2026-10-06T00:00:00Z", "2026-10-06T00:05:00Z", PeriodicDefinition.MisfirePolicy.FIRE_LATEST);
        var tick = plan.tick(plan.first(), at("2026-10-06T01:00:00Z"));
        assertThat(tick.plannedAt()).isEqualTo(at("2026-10-06T00:04:00Z")); assertThat(tick.nextAt()).isNull();
    }
    @Test
    void explicitZoneAndSpringForwardSkipNonexistentLocalTime() {
        var shanghai = definition("0 0 9 * * *", "Asia/Shanghai", "2026-10-06T00:00:00Z", null, PeriodicDefinition.MisfirePolicy.SKIP);
        assertThat(shanghai.first()).isEqualTo(at("2026-10-06T01:00:00Z"));
        var gap = definition("0 30 2 * * *", "America/New_York", "2026-03-08T00:00:00Z", null, PeriodicDefinition.MisfirePolicy.SKIP);
        assertThat(gap.first()).isEqualTo(at("2026-03-09T06:30:00Z"));
    }
    @Test
    void autumnRepeatedLocalTimeRetainsTwoDistinctUtcTicks() {
        var plan = definition("0 30 1 * * *", "America/New_York", "2026-11-01T00:00:00Z", null, PeriodicDefinition.MisfirePolicy.SKIP);
        assertThat(plan.preview(at("2026-10-31T23:59:59Z"), 2)).containsExactly(at("2026-11-01T05:30:00Z"), at("2026-11-01T06:30:00Z"));
    }
    @Test
    void invalidCursorPreviewCronZoneAndRangeReject() {
        var plan = minute(PeriodicDefinition.MisfirePolicy.SKIP); var due = plan.first();
        assertThatThrownBy(() -> plan.tick(due, due.minusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plan.preview(due, 51)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> definition("* * * * *", "UTC", "2026-10-06T00:00:00Z", null, PeriodicDefinition.MisfirePolicy.SKIP)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> definition("0 * * * * *", "invalid", "2026-10-06T00:00:00Z", null, PeriodicDefinition.MisfirePolicy.SKIP)).isInstanceOf(java.time.DateTimeException.class);
        assertThatThrownBy(() -> definition("0 * * * * *", "UTC", "2026-10-06T00:00:00Z", "2026-10-06T00:00:00Z", PeriodicDefinition.MisfirePolicy.SKIP)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rolloutProtocolIsExplicitAndRequiresTheExistingRolloutGrant() throws Exception {
        var fixed=minute(PeriodicDefinition.MisfirePolicy.FIRE_LATEST);
        var gray=new PeriodicDefinition(PeriodicDefinition.ROLLOUT_SCHEMA,fixed.cron(),fixed.zoneId(),fixed.effectiveFrom(),null,fixed.misfirePolicy(),10,2,null,fixed.selector(),null,new PeriodicDefinition.RolloutReference("pipeline"));
        assertThat(gray.usesRollout()).isTrue();assertThat(gray.targetPipelineId()).isEqualTo("pipeline");
        assertThat(gray.tick(gray.first(),gray.first().plusSeconds(615))).isEqualTo(fixed.tick(fixed.first(),fixed.first().plusSeconds(615)));
        assertThat(PeriodicScheduleService.grants(gray)).containsExactlyInAnyOrder("iqc:pipeline:task:create","iqc:task:execute","iqc:schedule:publish","iqc:pipeline:rollout:task:create");
        assertThat(PeriodicScheduleService.grants(fixed)).doesNotContain("iqc:pipeline:rollout:task:create");
        var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        assertThat(json.readTree(json.writeValueAsString(gray)).has("pipeline")).isFalse();
        assertThat(json.readValue(json.writeValueAsString(gray),PeriodicDefinition.class)).isEqualTo(gray);
    }
    @Test void fixedHistoricalConstructorsRetainJsonAndDoNotActivateRollout() throws Exception {
        var fixed=minute(PeriodicDefinition.MisfirePolicy.SKIP);
        var compatible=new PeriodicDefinition(fixed.schemaVersion(),fixed.cron(),fixed.zoneId(),fixed.effectiveFrom(),null,fixed.misfirePolicy(),10,2,fixed.pipeline(),fixed.selector(),null);
        var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        String original=json.writeValueAsString(fixed);assertThat(json.writeValueAsString(compatible)).isEqualTo(original);
        assertThat(json.readTree(original).has("rollout")).isFalse();
        assertThat(json.readValue(original,PeriodicDefinition.class)).isEqualTo(fixed);
        assertThat(fixed.usesRollout()).isFalse();assertThat(fixed.targetPipelineId()).isEqualTo("pipeline");
    }
    @Test void schemaAndTargetCannotBeMixedOrSilentlyConverted() {
        var fixed=minute(PeriodicDefinition.MisfirePolicy.SKIP);
        for(String schema:new String[]{PeriodicDefinition.SCHEMA,PeriodicDefinition.ROLLOUT_SCHEMA}) {
            assertThatThrownBy(()->new PeriodicDefinition(schema,fixed.cron(),fixed.zoneId(),fixed.effectiveFrom(),null,fixed.misfirePolicy(),10,2,fixed.pipeline(),fixed.selector(),null,new PeriodicDefinition.RolloutReference("pipeline"))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->new PeriodicDefinition(schema,fixed.cron(),fixed.zoneId(),fixed.effectiveFrom(),null,fixed.misfirePolicy(),10,2,null,fixed.selector(),null,null)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(()->new PeriodicDefinition(PeriodicDefinition.ROLLOUT_SCHEMA,fixed.cron(),fixed.zoneId(),fixed.effectiveFrom(),null,fixed.misfirePolicy(),10,2,null,fixed.selector(),null,new PeriodicDefinition.RolloutReference("bad/id"))).isInstanceOf(IllegalArgumentException.class);
    }

}
