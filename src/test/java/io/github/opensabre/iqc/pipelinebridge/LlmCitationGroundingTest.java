package io.github.opensabre.iqc.pipelinebridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Raw quote claims are measured before filtering, without changing frozen messages or model output. */
class LlmCitationGroundingTest {
    private final ObjectMapper json=new ObjectMapper();
    private ConversationMessage message(String text){var m=new ConversationMessage();m.setId("m");m.setConversationId("c");m.setSpeakerRole("agent");m.setContent(text);return m;}
    private com.fasterxml.jackson.databind.node.ObjectNode quote(){return json.createObjectNode().put("messageId","m").put("text","processed text");}
    private com.fasterxml.jackson.databind.node.ObjectNode legacy(){return json.createObjectNode().put("hit",true).put("reason","explicit");}
    @Test void countsEachRawClaimIncludingUnknownMessageAndNonverbatimText(){
        var root=legacy();var quotes=root.putArray("evidence");quotes.add(quote());quotes.add(quote().put("messageId","unknown"));quotes.add(quote().put("text","invented text"));
        String before=root.toString();var source=message("the processed text is frozen");
        var d=LlmCitationGrounding.inspect(root,List.of(source));
        assertThat(d.state()).isEqualTo("MEASURED");assertThat(d.claimedQuotes()).isEqualTo(3);assertThat(d.invalidQuotes()).isEqualTo(2);
        assertThat(root.toString()).isEqualTo(before);assertThat(source.getContent()).isEqualTo("the processed text is frozen");
    }
    @Test void checksExplicitIdentityAndCandidateRequiredIdentity(){
        var root=json.createObjectNode().put("schemaVersion","iqc-item-candidates-v1");
        root.putArray("quotes").add(quote()).add(quote().put("conversationId","c").put("speakerRole","agent"))
                .add(quote().put("conversationId","other").put("speakerRole","agent"));
        var d=LlmCitationGrounding.inspect(root,List.of(message("processed text")));
        assertThat(d.claimedQuotes()).isEqualTo(3);assertThat(d.invalidQuotes()).isEqualTo(2);
        var legacy=legacy();legacy.putArray("evidence").add(quote().put("speakerRole","customer"));
        assertThat(LlmCitationGrounding.inspect(legacy,List.of(message("processed text"))).invalidQuotes()).isEqualTo(1);
    }
    @Test void labelQuotesRequireFactSubjectRoleAndRetainMalformedContainers(){
        var root=json.createObjectNode().put("schemaVersion","iqc-label-facts-v2");var facts=root.putArray("facts");
        facts.addObject().put("subjectRole","agent").putArray("evidence").add(quote());
        facts.addObject().put("subjectRole","customer").putArray("evidence").add(quote());
        facts.addObject().put("subjectRole","agent").put("evidence","not an array");
        var d=LlmCitationGrounding.inspect(root,List.of(message("processed text")));
        assertThat(d.state()).isEqualTo("PARTIAL");assertThat(d.claimedQuotes()).isEqualTo(2);assertThat(d.invalidQuotes()).isEqualTo(1);assertThat(d.malformedContainers()).isEqualTo(1);
    }
    @Test void emptyKnownQuotesDifferFromMissingContainerAndUnknownProtocol(){
        var root=legacy();root.putArray("evidence");
        var d=LlmCitationGrounding.inspect(root,List.of());assertThat(d.state()).isEqualTo("MEASURED");assertThat(d.claimedQuotes()).isZero();
        root.putNull("evidence");d=LlmCitationGrounding.inspect(root,List.of());assertThat(d.state()).isEqualTo("PARTIAL");assertThat(d.malformedContainers()).isEqualTo(1);
        root.remove("evidence");d=LlmCitationGrounding.inspect(root,List.of());assertThat(d.state()).isEqualTo("UNMEASURED");assertThat(d.unmeasuredOutputs()).isEqualTo(1);
        assertThat(LlmCitationGrounding.inspect(null,List.of()).state()).isEqualTo("UNMEASURED");
    }
    @Test void observationEnvelopesCountKnownAndUnknownOutputsWithoutRecursiveSearch(){
        for(String schema:List.of("iqc-stage-observations-v1","iqc-rule-observations-v2")){
            var root=json.createObjectNode().put("schemaVersion",schema);var observations=root.putArray("observations");
            var known=legacy();known.putArray("evidence").add(quote());observations.addObject().set("finding",known);
            observations.addObject().set("finding",json.createObjectNode().set("hidden",known));
            var d=LlmCitationGrounding.inspect(root,List.of(message("processed text")));
            assertThat(d.state()).isEqualTo("PARTIAL");assertThat(d.outputs()).isEqualTo(2);assertThat(d.recognizedOutputs()).isEqualTo(1);assertThat(d.unmeasuredOutputs()).isEqualTo(1);assertThat(d.claimedQuotes()).isEqualTo(1);
        }
    }
    @Test void compatibleMatchedTextAndDuplicateMessageIdsUseFirstSource(){
        var root=legacy();var q=quote();q.remove("text");q.put("matchedText","processed text");root.putArray("evidence").add(q);
        assertThat(LlmCitationGrounding.inspect(root,List.of(message("processed text"),message("different"))).invalidQuotes()).isZero();
        assertThat(LlmCitationGrounding.inspect(root,List.of(message("different"),message("processed text"))).invalidQuotes()).isEqualTo(1);
    }
}
