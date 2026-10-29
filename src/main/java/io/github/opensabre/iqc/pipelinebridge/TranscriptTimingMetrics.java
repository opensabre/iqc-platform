package io.github.opensabre.iqc.pipelinebridge;

import io.github.opensabre.iqc.conversation.TranscriptAlignment;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.finding.Finding;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Deterministic measurements of supplied transcript intervals, not acoustic silence or interruption inference. */
public final class TranscriptTimingMetrics {
    private TranscriptTimingMetrics() { }
    public enum Metric {TRANSCRIPT_GAP_MS,CUSTOMER_TO_AGENT_MEAN_GAP_MS,AGENT_TRANSCRIPT_TIME_SHARE,CROSS_ROLE_OVERLAP_PAIRS}
    public record Measurement(Metric metric,String unit,Finding.State state,BigDecimal value,int observations,String reason) { }
    private record Segment(String id,String role,long start,long end) { }
    public static Measurement measure(Metric metric,List<ConversationMessage> messages,String agentRole,String customerRole) {
        Objects.requireNonNull(metric);Objects.requireNonNull(messages);
        if(agentRole==null || agentRole.isBlank() || customerRole==null || customerRole.isBlank() || agentRole.equals(customerRole))
            throw new IllegalArgumentException("Distinct participant roles are required");
        if(messages.size()>10000)throw new IllegalArgumentException("Transcript metric input exceeds 10000 segments");
        if(messages.isEmpty())return unknown(metric,"No transcript intervals",0);
        var segments=new ArrayList<Segment>();var ids=new HashSet<String>();String conversation=null;boolean missing=false;
        for(var message:messages) {
            if(message==null || message.getId()==null || message.getId().isBlank() || !ids.add(message.getId())
                    || message.getConversationId()==null || message.getConversationId().isBlank())
                throw new IllegalArgumentException("Transcript segment identity is invalid");
            if(conversation==null)conversation=message.getConversationId();
            else if(!conversation.equals(message.getConversationId()))throw new IllegalArgumentException("Transcript metrics require one conversation");
            TranscriptAlignment.validate(message.getStartMs(),message.getEndMs(),message.getConfidence());
            if(message.getStartMs()==null){missing=true;continue;}
            segments.add(new Segment(message.getId(),message.getSpeakerRole(),message.getStartMs(),message.getEndMs()));
        }
        if(missing)return unknown(metric,"At least one transcript interval is unknown",messages.size());
        segments.sort(Comparator.comparingLong(Segment::start).thenComparingLong(Segment::end).thenComparing(Segment::id));
        if(metric==Metric.TRANSCRIPT_GAP_MS) {
            long last=segments.stream().mapToLong(Segment::end).max().orElseThrow();
            return known(metric,BigDecimal.valueOf(last-segments.getFirst().start()-union(segments)),segments.size());
        }
        if(segments.stream().anyMatch(segment->!agentRole.equals(segment.role()) && !customerRole.equals(segment.role())))
            return unknown(metric,"A transcript participant role is not declared",segments.size());
        if(metric==Metric.AGENT_TRANSCRIPT_TIME_SHARE) {
            long agent=union(segments.stream().filter(s->agentRole.equals(s.role())).toList());
            long customer=union(segments.stream().filter(s->customerRole.equals(s.role())).toList());
            if(agent+customer==0)return unknown(metric,"Participant interval duration is zero",segments.size());
            return known(metric,BigDecimal.valueOf(agent).divide(BigDecimal.valueOf(agent+customer),6,RoundingMode.HALF_UP),segments.size());
        }
        if(metric==Metric.CUSTOMER_TO_AGENT_MEAN_GAP_MS) {
            BigDecimal total=BigDecimal.ZERO;int pairs=0;
            for(int i=1;i<segments.size();i++) {
                var previous=segments.get(i-1);var current=segments.get(i);
                if(customerRole.equals(previous.role()) && agentRole.equals(current.role())) {
                    total=total.add(BigDecimal.valueOf(Math.max(0,current.start()-previous.end())));pairs++;
                }
            }
            return pairs==0?unknown(metric,"No adjacent customer-to-agent interval pair",0)
                    :known(metric,total.divide(BigDecimal.valueOf(pairs),6,RoundingMode.HALF_UP),pairs);
        }
        // Count crossing-role overlaps with a bounded active queue; touching/zero-length intervals do not overlap.
        var active=new PriorityQueue<Segment>(Comparator.comparingLong(Segment::end));
        var counts=new HashMap<String,Integer>();long overlaps=0;
        for(var segment:segments) {
            while(!active.isEmpty() && active.peek().end()<=segment.start()) {
                var expired=active.remove();counts.merge(expired.role(),-1,Integer::sum);
            }
            if(segment.end()==segment.start())continue;
            overlaps+=active.size()-counts.getOrDefault(segment.role(),0);
            active.add(segment);counts.merge(segment.role(),1,Integer::sum);
        }
        return known(metric,BigDecimal.valueOf(overlaps),segments.size());
    }
    private static long union(List<Segment> ordered) {
        if(ordered.isEmpty())return 0;
        long start=ordered.getFirst().start(),end=ordered.getFirst().end(),total=0;
        for(int i=1;i<ordered.size();i++) {
            var segment=ordered.get(i);
            if(segment.start()>end){total+=end-start;start=segment.start();end=segment.end();}
            else end=Math.max(end,segment.end());
        }
        return total+end-start;
    }
    private static String unit(Metric metric) {return metric==Metric.AGENT_TRANSCRIPT_TIME_SHARE?"ratio":metric==Metric.CROSS_ROLE_OVERLAP_PAIRS?"pairs":"ms";}
    private static Measurement unknown(Metric metric,String reason,int observations) {return new Measurement(metric,unit(metric),Finding.State.UNKNOWN,null,observations,reason);}
    private static Measurement known(Metric metric,BigDecimal value,int observations) {return new Measurement(metric,unit(metric),Finding.State.KNOWN,value,observations,"Measured from supplied transcript intervals");}
}
