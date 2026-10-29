package io.github.opensabre.iqc.action;

import io.github.opensabre.iqc.task.retry.AutomaticRetryPolicy;
import java.time.Instant;
import java.nio.charset.StandardCharsets;

/** Immutable source and retry boundary for independent actions; no detection writes or network I/O. */
public final class ActionExecutionContract {
    private ActionExecutionContract() { }
    public enum Type {SAVE_RESULT,UPDATE_LABEL,CREATE_RISK_EVENT,SEND_ALERT,CREATE_REVIEW,CREATE_WORK_ORDER,CREATE_COACHING_TASK,WEBHOOK,ADD_TO_EVALUATION_CANDIDATE}
    public enum Outcome {CONFIRMED_SUCCESS,CONFIRMED_RETRYABLE_FAILURE,CONFIRMED_FINAL_FAILURE,UNKNOWN}
    public enum Next {COMPLETE,WAITING_RETRY,FAILED,REQUIRES_RECONCILIATION}
    public record Source(String taskId,String executionId,String conversationId,String resultId,String decisionReference,
                         String actionPolicyHash,String actionId,String owner){
        public Source {
            reference(taskId);reference(executionId);reference(conversationId);reference(resultId);reference(decisionReference);reference(actionId);
            if(owner==null || owner.isBlank() || owner.length()>128 || owner.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Action owner invalid");
            if(actionPolicyHash==null || !actionPolicyHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Frozen action policy hash required");
        }
        public String idempotencyKey(Type type){
            if(type==null)throw new IllegalArgumentException("Action type required");
            String framed=frame("iqc-action-execution-v1")+frame(taskId)+frame(executionId)+frame(conversationId)+frame(resultId)
                +frame(decisionReference)+frame(actionPolicyHash)+frame(actionId)+frame(owner)+frame(type.name());
            // This is length-framed UTF-8, not JSON; JSON canonicalization cannot hash this contract.
            try {
                return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(framed.getBytes(StandardCharsets.UTF_8)));
            } catch(java.security.NoSuchAlgorithmException unavailable) {
                throw new IllegalStateException("SHA-256 unavailable",unavailable);
            }
        }
    }
    public record Plan(Next next,Instant notBefore) { }
    /** Attempt includes the first send. UNKNOWN never enters the automatic retry queue. */
    public static Plan plan(Outcome outcome,int attempt,boolean adapterRetrySafe,AutomaticRetryPolicy policy,Instant now){
        if(outcome==null || attempt<1 || now==null)throw new IllegalArgumentException("Action attempt evidence required");
        if(outcome==Outcome.UNKNOWN)return new Plan(Next.REQUIRES_RECONCILIATION,null);
        if(outcome==Outcome.CONFIRMED_SUCCESS)return new Plan(Next.COMPLETE,null);
        if(outcome==Outcome.CONFIRMED_FINAL_FAILURE || !adapterRetrySafe || policy==null || attempt>=policy.maximumAttempts())return new Plan(Next.FAILED,null);
        Instant notBefore=now.plusSeconds(policy.delaySeconds(attempt));
        if(policy.effectiveUntil()!=null && !notBefore.isBefore(policy.effectiveUntil()))return new Plan(Next.FAILED,null);
        return new Plan(Next.WAITING_RETRY,notBefore);
    }
    private static void reference(String value){if(value==null || !value.matches("[A-Za-z0-9][A-Za-z0-9:_./@+-]{0,127}"))throw new IllegalArgumentException("Action source reference invalid");}
    private static String frame(String value){return value.getBytes(StandardCharsets.UTF_8).length+":"+value;}
}
