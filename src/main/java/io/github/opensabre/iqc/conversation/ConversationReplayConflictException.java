package io.github.opensabre.iqc.conversation;
import io.github.opensabre.common.core.exception.BaseException;
import io.github.opensabre.iqc.governance.IqcErrorType;
/** Deterministic identity/content disagreement, detected before any archive write. */
public final class ConversationReplayConflictException extends BaseException {
    public ConversationReplayConflictException(String message){super(IqcErrorType.INVALID_STATE,message);}
}
