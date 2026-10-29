package io.github.opensabre.iqc.conversation;

import java.time.LocalTime;

public record ConversationMessageDraft(
        int sequence,
        String speakerRole,
        LocalTime relativeTime,
        String content,
        String rawLine,
        int lineNumber,Long startMs,Long endMs,java.math.BigDecimal confidence) {
    public ConversationMessageDraft(int sequence,String speakerRole,LocalTime relativeTime,String content,String rawLine,int lineNumber){
        this(sequence,speakerRole,relativeTime,content,rawLine,lineNumber,null,null,null);
    }
    public ConversationMessageDraft { TranscriptAlignment.validate(startMs,endMs,confidence); }
}
