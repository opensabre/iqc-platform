package io.github.opensabre.iqc.rule.dls;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class DlsSessionAggregationTest {
 ConversationMessage message(String id,int sequence,String role,String text){var m=new ConversationMessage();m.setId(id);m.setSequenceNo(sequence);m.setSpeakerRole(role);m.setContent(text);return m;}
 DlsEngine.Compiled rule(){return DlsEngine.compile(new DlsRuleDocument("1.0",null,List.of(new DlsRuleDocument.Definition("rule_offer","RULE","offer","agent")),"Offers","[rule_offer]"));}
 @Test void countsDistinctMatchingMessagesInsteadOfOccurrencesOrOtherRoles(){
 var result=DlsEngine.evaluateSession(rule(),List.of(message("a",1,"agent","offer offer"),message("b",2,"customer","offer"),message("c",3,"agent","offer")),"rule_offer");assertTrue(result.entry().hit());assertEquals(2,result.matchedMessageCount());assertEquals(List.of("a","c"),result.countedEvidence().stream().map(DlsEngine.Hit::messageId).toList());
 }
 @Test void emptyCountIsKnownZeroAndEntryRemainsFalse(){var result=DlsEngine.evaluateSession(rule(),List.of(message("a",1,"agent","hello")),"rule_offer");assertFalse(result.entry().hit());assertEquals(0,result.matchedMessageCount());assertTrue(result.countedEvidence().isEmpty());}
 @Test void invalidDefinitionAndEmptySessionRejectInsteadOfProducingCount(){assertThrows(IllegalArgumentException.class,()->DlsEngine.evaluateSession(rule(),List.of(message("a",1,"agent","offer")),"missing"));assertThrows(IllegalArgumentException.class,()->DlsEngine.evaluateSession(rule(),List.of(),"rule_offer"));}
}
