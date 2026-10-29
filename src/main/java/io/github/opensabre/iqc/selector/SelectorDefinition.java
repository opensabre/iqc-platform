package io.github.opensabre.iqc.selector;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/** Data selection is independent of detectors; missing upstream facts are not inferred. */
public record SelectorDefinition(String schemaVersion, Condition condition, Condition excludeCondition,
        List<String> includeIds,List<String> excludeIds,TimeWindow timeWindow,Sampling sampling,int maximumCount) {
    public static final String SCHEMA="iqc-selector-v1";
    public enum Junction { AND,OR }
    public enum Operator { EQ,NE,GT,GE,LT,LE,IN,NOT_IN,EXISTS,CONTAINS }
    public record Condition(Junction junction,List<Condition> children,String field,Operator operator,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.ExactValueDeserializer.class)
            @tools.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.HttpExactValueDeserializer.class)
            @tools.jackson.databind.annotation.JsonSerialize(using=io.github.opensabre.iqc.quality.evaluation.BenchmarkService.HttpTreeSerializer.class)
            JsonNode value) {
        public Condition { children=children==null?List.of():List.copyOf(children); value=value==null?null:value.deepCopy(); }
        @Override public JsonNode value(){return value==null?null:value.deepCopy();}
    }
    public enum TimeField { CREATED,STARTED }
    public enum WindowMode { ABSOLUTE,LAST_HOURS,PREVIOUS_DAY,PREVIOUS_WEEK }
    public record TimeWindow(TimeField field,WindowMode mode,LocalDateTime from,LocalDateTime to,Integer hours,String zoneId) {
        public TimeWindow {
            Objects.requireNonNull(field);Objects.requireNonNull(mode);
            zoneId=zoneId==null?"Asia/Shanghai":zoneId; ZoneId.of(zoneId);
            if(mode==WindowMode.ABSOLUTE) {
                if(from==null || to==null || !from.isBefore(to) || hours!=null) throw new IllegalArgumentException("absolute window needs from < to");
            } else if(from!=null || to!=null) throw new IllegalArgumentException("relative window cannot carry absolute boundaries");
            if(mode==WindowMode.LAST_HOURS) {
                if(hours==null || hours<1 || hours>8760) throw new IllegalArgumentException("hours must be 1..8760");
            } else if(hours!=null) throw new IllegalArgumentException("hours only applies to LAST_HOURS");
        }
    }
    public enum SamplingMode { ALL,FIXED_COUNT,RATIO,RANDOM,EMPLOYEE_UNIFORM,TEAM_STRATIFIED,RISK_PRIORITY }
    public record Sampling(SamplingMode mode,Integer count,BigDecimal ratio,String seed) {
        public Sampling {
            Objects.requireNonNull(mode);
            if(seed==null || seed.isBlank() || seed.length()>128) throw new IllegalArgumentException("sampling needs a stable seed up to 128 characters");
            if(mode==SamplingMode.RATIO) {
                if(ratio==null || ratio.signum()<=0 || ratio.compareTo(BigDecimal.ONE)>0 || count!=null) throw new IllegalArgumentException("ratio must be >0 and <=1");
            } else if(ratio!=null) throw new IllegalArgumentException("ratio only applies to RATIO");
            if(Set.of(SamplingMode.FIXED_COUNT,SamplingMode.RANDOM,SamplingMode.EMPLOYEE_UNIFORM,SamplingMode.TEAM_STRATIFIED,SamplingMode.RISK_PRIORITY).contains(mode)) {
                if(count==null || count<1 || count>1000) throw new IllegalArgumentException("sampling count must be 1..1000");
            } else if(count!=null) throw new IllegalArgumentException("count does not apply to this mode");
        }
    }
    public SelectorDefinition {
        if(!SCHEMA.equals(schemaVersion)) throw new IllegalArgumentException("unsupported selector schema");
        includeIds=ids(includeIds);excludeIds=ids(excludeIds);
        if(maximumCount<1 || maximumCount>1000) throw new IllegalArgumentException("maximumCount must be 1..1000");
        if(sampling==null) sampling=new Sampling(SamplingMode.ALL,null,null,"DEFAULT");
        if(sampling.count()!=null && sampling.count()>maximumCount) throw new IllegalArgumentException("sample count exceeds maximumCount");
    }
    private static List<String> ids(List<String> input) {
        if(input==null) return List.of();
        if(input.size()>1000 || input.stream().anyMatch(id->id==null || !id.matches("[A-Za-z0-9:_-]{1,64}")))
            throw new IllegalArgumentException("invalid selector include/exclude IDs");
        return input.stream().distinct().sorted().toList();
    }
}
