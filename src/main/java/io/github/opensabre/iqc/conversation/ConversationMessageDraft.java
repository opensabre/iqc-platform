package io.github.opensabre.iqc.conversation;

import java.time.LocalTime;

public record ConversationMessageDraft(
        int sequence,
        String speakerRole,
        LocalTime relativeTime,
        String content,
        String rawLine,
        int lineNumber) {
}
