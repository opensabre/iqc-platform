package io.github.opensabre.iqc.pipelinebridge;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.finding.Finding;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
class TranscriptTimingMetricsTest {
 ConversationMessage segment(String id,String role,Long start,Long end){var m=new ConversationMessage();m.setId(id);m.setConversationId("conversation");m.setSpeakerRole(role);m.setStartMs(start);m.setEndMs(end);return m;}
 TranscriptTimingMetrics.Measurement measure(TranscriptTimingMetrics.Metric metric,List<ConversationMessage> messages){return TranscriptTimingMetrics.measure(metric,messages,"agent","customer");}
 @Test void suppliedIntervalsUseUnionInsteadOfDoubleCountingAndDoNotWrapAt24Hours(){
 var messages=List.of(segment("late","agent",86400300L,86400500L),segment("nested","customer",86400050L,86400100L),segment("first","customer",86400000L,86400200L));
 var gap=measure(TranscriptTimingMetrics.Metric.TRANSCRIPT_GAP_MS,messages);assertEquals(Finding.State.KNOWN,gap.state());assertEquals(new BigDecimal("100"),gap.value());
 assertEquals(new BigDecimal("0.500000"),measure(TranscriptTimingMetrics.Metric.AGENT_TRANSCRIPT_TIME_SHARE,messages).value());
 }
 @Test void overlapCountsExcludeTouchingAndZeroLengthAndSameRolePairs(){
 var messages=List.of(segment("a","agent",0L,100L),segment("b","customer",50L,150L),segment("c","agent",100L,200L),segment("zero","customer",100L,100L));
 assertEquals(new BigDecimal("2"),measure(TranscriptTimingMetrics.Metric.CROSS_ROLE_OVERLAP_PAIRS,messages).value());
 }
 @Test void missingIntervalsAreUnknownAndMixedConversationIsRejected(){
 var unknown=measure(TranscriptTimingMetrics.Metric.TRANSCRIPT_GAP_MS,List.of(segment("missing","agent",null,null)));assertEquals(Finding.State.UNKNOWN,unknown.state());assertNull(unknown.value());
 var foreign=segment("foreign","customer",0L,20L);foreign.setConversationId("other");assertThrows(IllegalArgumentException.class,()->measure(TranscriptTimingMetrics.Metric.TRANSCRIPT_GAP_MS,List.of(segment("a","agent",0L,10L),foreign)));
 }
}
