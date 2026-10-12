package io.github.opensabre.iqc.conversation;

import java.util.List;

/** Shared structured conversation ingress, independent of transport controllers. */
public record ApiConversationInput(String externalId,String batchNo,String title,ConversationMetadata metadata,List<Message> messages) {
    public record Message(String time,String role,String content,@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.conversation.ExactMillisecondsDeserializer.class) @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Long startMs,@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.conversation.ExactMillisecondsDeserializer.class) @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Long endMs,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) java.math.BigDecimal confidence) {
        public Message(String time,String role,String content){this(time,role,content,null,null,null);}
    }
}
