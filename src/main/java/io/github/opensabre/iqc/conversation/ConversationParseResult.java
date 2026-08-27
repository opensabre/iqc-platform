package io.github.opensabre.iqc.conversation;

import java.util.List;

public record ConversationParseResult(
        List<ConversationMessageDraft> messages,
        List<ConversationParseError> errors,
        int ignoredBlankLines) {

    public boolean successful() {
        return errors.isEmpty() && !messages.isEmpty();
    }

    public record ConversationParseError(int lineNumber, String rawLine, String reason) {
    }
}
