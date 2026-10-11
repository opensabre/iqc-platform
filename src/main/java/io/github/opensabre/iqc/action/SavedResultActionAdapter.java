package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Objects;

/** SAVE_RESULT confirms the existing durable analysis result; it never creates a duplicate result. */
@Component
@RequiredArgsConstructor
public class SavedResultActionAdapter implements ActionAdapter {
    private static final Definition DEFINITION=new Definition(ActionExecutionContract.Type.SAVE_RESULT,1,true);
    private final ConversationInspectionResultMapper results;
    private final ObjectMapper json;
    @Override public Definition definition(){return DEFINITION;}
    @Override public void validateConfiguration(com.fasterxml.jackson.databind.JsonNode configuration) {
        if(configuration==null || !configuration.isObject() || !configuration.isEmpty())
            throw new IllegalArgumentException("SAVE_RESULT configuration must be an empty object");
    }
    @Override public Receipt execute(Request request) { return confirm(request); }
    /** Recovery shares the original read-only proof without executing an action. */
    @Override public Receipt reconcile(Request request) { return confirm(request); }
    private Receipt confirm(Request request) {
        Objects.requireNonNull(request);
        if(!DEFINITION.equals(request.adapter()))throw new IllegalArgumentException("SAVE_RESULT adapter version mismatch");
        try {
            var source=request.source();
            var policy=json.readValue(request.policySnapshotJson(),ActionPolicy.class);
            var configured=policy.actions().stream().filter(action->source.actionId().equals(action.actionId())).findFirst().orElse(null);
            if(configured==null || configured.type()!=DEFINITION.type() || configured.effectiveAdapterVersion()!=DEFINITION.version())
                return unknown(request,"ACTION_POLICY_MISMATCH");
            validateConfiguration(configured.configuration());
            var frozen=json.readTree(request.inputSnapshotJson());
            if(frozen==null || !"iqc-action-production-input-v1".equals(frozen.path("schemaVersion").asText())
                    || !source.taskId().equals(frozen.path("taskId").asText())
                    || !source.executionId().equals(frozen.path("executionId").asText())
                    || !source.conversationId().equals(frozen.path("conversationId").asText())
                    || !source.resultId().equals(frozen.path("resultId").asText())
                    || !source.owner().equals(frozen.path("owner").asText())
                    || !source.resultId().equals(source.decisionReference()))
                return unknown(request,"SOURCE_SNAPSHOT_MISMATCH");
            var result=results.selectById(source.resultId());
            if(result==null)return unknown(request,"SAVED_RESULT_UNAVAILABLE");
            if(!source.resultId().equals(result.getId()) || !source.taskId().equals(result.getTaskId()) || !source.executionId().equals(result.getExecutionId())
                    || !source.conversationId().equals(result.getConversationId()) || !"PIPELINE".equals(result.getAggregationMode())
                    || !Objects.equals(result.getExecutionTraceJson(),frozen.path("traceJson").asText())
                    || !Objects.equals(result.getScoringResultJson(),frozen.path("scoringResultJson").asText())
                    || !Objects.equals(result.getBusinessItemResultsJson(),frozen.path("businessItemResultsJson").asText())
                    || !Objects.equals(result.getResultStatus(),frozen.path("resultStatus").asText())
                    || !Objects.equals(result.getRiskLevel(),frozen.path("riskLevel").asText()))
                return unknown(request,"SOURCE_RESULT_MISMATCH");
            var scoring=json.readTree(result.getScoringResultJson());
            if(scoring==null || !Objects.equals(result.getScoreStatus(),scoring.path("scoreStatus").asText()))
                return unknown(request,"SOURCE_RESULT_MISMATCH");
            var score=scoring.get("finalScore");
            if(score==null || (score.isNull()?result.getFinalScore()!=null:
                    !score.isNumber() || result.getFinalScore()==null || score.decimalValue().compareTo(result.getFinalScore())!=0))
                return unknown(request,"SOURCE_RESULT_MISMATCH");
            // A real persisted result is the receipt and output. No simulated remote confirmation.
            return new Receipt(request.idempotencyKey(),ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,
                    result.getId(),result.getId(),null);
        } catch(com.fasterxml.jackson.core.JsonProcessingException | RuntimeException failure) {
            return unknown(request,"RESULT_CONFIRMATION_UNAVAILABLE");
        }
    }
    private Receipt unknown(Request request,String code) {
        return new Receipt(request.idempotencyKey(),ActionExecutionContract.Outcome.UNKNOWN,null,null,code);
    }
}
