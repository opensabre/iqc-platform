package io.github.opensabre.iqc.schedule;

import io.github.opensabre.iqc.selector.SelectorAssetService;
import org.springframework.scheduling.support.CronExpression;
import java.time.*;
import java.util.*;

public record PeriodicDefinition(String schemaVersion,String cron,String zoneId,Instant effectiveFrom,Instant effectiveUntil,
        MisfirePolicy misfirePolicy,int maximumCatchUp,int concurrency,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) PipelineReference pipeline,SelectorAssetService.Reference selector,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        io.github.opensabre.iqc.task.retry.AutomaticRetryPolicy retryPolicy,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) RolloutReference rollout) {
    public static final String SCHEMA="iqc-periodic-definition-v1";
    public static final String ROLLOUT_SCHEMA="iqc-periodic-rollout-definition-v1";
    public record RolloutReference(String pipelineId) { }
    public boolean usesRollout(){return ROLLOUT_SCHEMA.equals(schemaVersion);}
    public String targetPipelineId(){return usesRollout()?rollout.pipelineId():pipeline.pipelineId();}
    public enum MisfirePolicy { SKIP,FIRE_LATEST,CATCH_UP }
    public record PipelineReference(String pipelineId,int versionNo) { }
    public record Tick(Instant plannedAt,Instant nextAt,boolean skip,Instant missedFrom,Instant missedUntil) { }
    public PeriodicDefinition(String schemaVersion,String cron,String zoneId,Instant effectiveFrom,Instant effectiveUntil,
            MisfirePolicy misfirePolicy,int maximumCatchUp,int concurrency,PipelineReference pipeline,SelectorAssetService.Reference selector){
        this(schemaVersion,cron,zoneId,effectiveFrom,effectiveUntil,misfirePolicy,maximumCatchUp,concurrency,pipeline,selector,null);
    }
    public PeriodicDefinition(String schemaVersion,String cron,String zoneId,Instant effectiveFrom,Instant effectiveUntil,
            MisfirePolicy misfirePolicy,int maximumCatchUp,int concurrency,PipelineReference pipeline,SelectorAssetService.Reference selector,
            io.github.opensabre.iqc.task.retry.AutomaticRetryPolicy retryPolicy){
        this(schemaVersion,cron,zoneId,effectiveFrom,effectiveUntil,misfirePolicy,maximumCatchUp,concurrency,pipeline,selector,retryPolicy,null);
    }
    public PeriodicDefinition {
        boolean gray=ROLLOUT_SCHEMA.equals(schemaVersion);
        if((!SCHEMA.equals(schemaVersion)&&!gray) || cron==null || cron.length()>256 || zoneId==null || effectiveFrom==null
                || (effectiveUntil!=null && !effectiveUntil.isAfter(effectiveFrom)) || misfirePolicy==null
                || maximumCatchUp<1 || maximumCatchUp>100 || concurrency<1 || concurrency>32
                || (gray?(pipeline!=null||rollout==null||rollout.pipelineId()==null)
                         :(pipeline==null||pipeline.pipelineId()==null||pipeline.versionNo()<1||rollout!=null))
                || selector==null || selector.selectorId()==null || selector.versionNo()<1)
            throw new IllegalArgumentException("invalid periodic definition");
        Instant minimum=Instant.parse("1000-01-01T00:00:00Z"),maximum=Instant.parse("9999-12-31T23:59:59Z");
        if(effectiveFrom.isBefore(minimum) || effectiveFrom.isAfter(maximum)
                || (effectiveUntil!=null && (effectiveUntil.isBefore(minimum) || effectiveUntil.isAfter(maximum))))
            throw new IllegalArgumentException("periodic dates must fit MySQL datetime");
        ZoneId.of(zoneId);cron=CronExpression.parse(cron).toString();
        String target=gray?rollout.pipelineId():pipeline.pipelineId();
        if(!target.matches("[A-Za-z0-9:_-]{1,64}") || !selector.selectorId().matches("[A-Za-z0-9:_-]{1,64}"))
            throw new IllegalArgumentException("invalid periodic asset identity");
    }
    public Instant next(Instant after) {
        var value=CronExpression.parse(cron).next(after.atZone(ZoneId.of(zoneId)));
        if(value==null)return null;Instant instant=value.toInstant();
        if(!instant.isAfter(after))throw new IllegalStateException("Cron cursor must move forward");
        return instant.isAfter(Instant.parse("9999-12-31T23:59:59Z"))
                || (effectiveUntil!=null && !instant.isBefore(effectiveUntil))?null:instant;
    }
    public Instant first() {return next(effectiveFrom.minusNanos(1));}
    public List<Instant> preview(Instant from,int size) {
        if(from==null || size<1 || size>50)throw new IllegalArgumentException("invalid periodic preview");
        var values=new ArrayList<Instant>();Instant cursor=from.isBefore(effectiveFrom)?effectiveFrom.minusNanos(1):from;
        for(int i=0;i<size;i++){cursor=next(cursor);if(cursor==null)break;values.add(cursor);}return List.copyOf(values);
    }
    /** Locate the latest due tick without walking every missed second. next(probe)<=now is monotone. */
    private Instant latest(Instant first,Instant now) {
        long low=first.toEpochMilli()-1,high=now.toEpochMilli();
        while(high-low>1) {
            long mid=low+(high-low)/2;Instant candidate=next(Instant.ofEpochMilli(mid));
            if(candidate!=null && !candidate.isAfter(now))low=Math.max(mid,candidate.toEpochMilli()-1);else high=mid;
        }
        Instant answer=next(Instant.ofEpochMilli(high-1));
        if(answer==null || answer.isAfter(now) || answer.isBefore(first))throw new IllegalStateException("invalid latest Cron tick");
        return answer;
    }
    public Tick tick(Instant due,Instant now) {
        if(due==null || now==null || due.isAfter(now))throw new IllegalArgumentException("periodic cursor is not due");
        Instant following=next(due);
        if(!due.isBefore(now.minusSeconds(30)) || misfirePolicy==MisfirePolicy.CATCH_UP)
            return new Tick(due,following,false,null,null);
        Instant latest=latest(due,now),future=next(latest);
        return misfirePolicy==MisfirePolicy.SKIP?new Tick(due,future,true,due,latest.plusNanos(1))
                :new Tick(latest,future,false,due,latest);
    }
}
