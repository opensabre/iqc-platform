package io.github.opensabre.iqc.conversation;

import io.github.opensabre.iqc.conversation.model.ConversationMessage;

import java.util.List;
import java.util.Locale;

/** Maps unambiguous transcript aliases; the target-role dictionary cannot represent legacy or unknown speakers. */
public final class ConversationSpeakerRole {
    private ConversationSpeakerRole() { }

    /** Unknown speakers remain distinct rather than being guessed as the customer or agent. */
    public static String canonical(String role) {
        if (role == null) return null;
        String value = role.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "agent", "坐席", "客服" -> "agent";
            case "user", "客户", "用户" -> "user";
            case "customer" -> "customer";
            default -> value;
        };
    }

    /** Normalizes execution copies of archived messages without rewriting stored transcripts. */
    public static void canonicalize(List<ConversationMessage> messages) {
        if (messages == null) return;
        messages.forEach(message -> {
            if (message != null) message.setSpeakerRole(canonical(message.getSpeakerRole()));
        });
    }
}
