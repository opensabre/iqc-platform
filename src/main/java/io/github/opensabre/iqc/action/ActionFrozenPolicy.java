package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Shared immutable-policy validation for claim and blocked recovery; never performs state writes or adapter I/O. */
final class ActionFrozenPolicy {
    record Prepared(ActionPolicy.Action action,ActionExecutionContract.Source source,String policyBody) {
        boolean exhausted(ActionExecution row,Instant now) {
            int maximum=action.retry()==null?1:action.retry().maximumAttempts();
            return row.getAttemptCount()>=maximum || action.retry()!=null && action.retry().effectiveUntil()!=null
                    && !now.isBefore(action.retry().effectiveUntil());
        }
    }
    private ActionFrozenPolicy() { }
    /** Standalone policy copies inherit numeric decoding from their immutable source task. */
    static ActionPolicy readFrozen(ObjectMapper json,String policyBody,String inputBody)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        var input=json.readTree(inputBody);
        var reader=json.readerFor(ActionPolicy.class);
        // Historical callers without an embedded task keep the original v1 interpretation.
        if(input!=null && input.path("taskSnapshotJson").isTextual()) {
            var task=io.github.opensabre.iqc.pipelineasset.PipelineAssetService.readFrozenTree(json,input.path("taskSnapshotJson").asText());
            String schema=task==null ? io.github.opensabre.iqc.pipelineasset.PipelineRelease.SCHEMA
                    : task.path("pipelineSnapshot").path("release").path("schemaVersion").asText(io.github.opensabre.iqc.pipelineasset.PipelineRelease.SCHEMA);
            if(io.github.opensabre.iqc.pipelineasset.PipelineRelease.EXACT_SCHEMA.equals(schema))
                reader=reader.with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            else if(!io.github.opensabre.iqc.pipelineasset.PipelineRelease.SCHEMA.equals(schema))
                throw new IllegalArgumentException("Unsupported frozen Action source release protocol");
        }
        return reader.readValue(policyBody);
    }

    static Prepared validate(ActionExecution row,ActionProductionSourceValidator.Reference reference,
                             ActionProductionSourceValidator.Validated validated,ObjectMapper json)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        if(validated.policy()==null)throw IqcException.invalidState("动作领取缺少原生产策略");
        var action=validated.policy().actions().stream().filter(value->Objects.equals(value.actionId(),row.getActionId())).findFirst().orElse(null);
        if(action==null)throw IqcException.invalidState("动作领取缺少冻结动作");
        String policyBody=json.writeValueAsString(validated.policy());
        var source=new ActionExecutionContract.Source(reference.taskId(),reference.executionId(),reference.conversationId(),
                reference.resultId(),validated.decisionReference(),SelectorAssetService.hash(policyBody),action.actionId(),reference.owner());
        ActionEnqueueService.requireSame(row,source,action,validated,source.idempotencyKey(action.type()),policyBody);
        if(ActionPolicy.evaluate(action.condition(),validated.context())!=ActionPolicy.Match.MATCH)
            throw IqcException.invalidState("动作领取条件不再成立");
        if(row.getAttemptCount()==null || row.getAttemptCount()<0)throw IqcException.invalidState("动作尝试计数无效");
        return new Prepared(action,source,policyBody);
    }
    /** Validates an outbound request against existing frozen source and claim; no network or state writes. */
    static ActionPolicy.Action validateOutboundRequest(ActionAdapter.Request request,
            ActionProductionSourceValidator.Validated validated,ActionExecution row,
            ActionAdapter.Definition definition,boolean historical,ObjectMapper json) {
        if (request == null || !definition.equals(request.adapter()) || validated == null || validated.policy() == null || row == null)
            throw IqcException.invalidState("告警缺少原生产政策或领取记录");
        var action = validated.policy().actions().stream()
                .filter(value -> request.source().actionId().equals(value.actionId())).findFirst().orElseThrow();
        if (action.type() != definition.type() || action.effectiveAdapterVersion() != definition.version()
                || ActionPolicy.evaluate(action.condition(), validated.context()) != ActionPolicy.Match.MATCH)
            throw IqcException.invalidState("告警冻结政策类型或触发条件不一致");
        try {
            String body = json.writeValueAsString(validated.policy());
            ActionEnqueueService.requireSame(row, request.source(), action, validated, request.idempotencyKey(), body);
            if (!Objects.equals(request.policySnapshotJson(), body)
                    || !Objects.equals(request.inputSnapshotJson(), validated.inputSnapshotJson())
                    || !Objects.equals(request.inputSnapshotHash(), validated.inputSnapshotHash())
                    || !Objects.equals(row.getAttemptCount(), request.attempt())
                    || row.getVersion() == null || row.getVersion() < 1
                    || row.getClaimToken() == null || !row.getClaimToken().matches("[a-f0-9]{32}")
                    || row.getClaimedAtUtc() == null
                    || (!historical && !"RUNNING".equals(row.getStatus()))
                    || (historical && !Set.of("REQUIRES_RECONCILIATION", "COMPLETE").contains(row.getStatus())))
                throw IqcException.invalidState("告警领取或原冻结请求已变化");
            if (!historical && (request.attempt() > (action.retry() == null ? 1 : action.retry().maximumAttempts())
                    || action.retry() != null && action.retry().effectiveUntil() != null
                    && !Instant.now().isBefore(action.retry().effectiveUntil())))
                throw IqcException.invalidState("告警尝试次数或期限已耗尽");
            return action;
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw IqcException.invalidState("告警冻结政策损坏");
        }
    }

}
