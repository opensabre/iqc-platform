package io.github.opensabre.iqc.action;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.shared.IqcDataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Short local source/claim validation, committed before any owning notification RPC. */
@Service @RequiredArgsConstructor
public class SendAlertPreparationService {
    public static final ActionAdapter.Definition DEFINITION =
            new ActionAdapter.Definition(ActionExecutionContract.Type.SEND_ALERT, 1, true);
    public static final Set<String> RESOURCES = Set.of("iqc:action:alert");
    private final ActionProductionSourceValidator sources;
    private final ActionExecutionMapper actions;
    private final IqcDataScope scope;
    private final ObjectMapper json;

    /** No send is performed here; source locks and claim checks complete before returning. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public SendAlertConfiguration prepareSend(ActionAdapter.Request request) {
        requireRequest(request);
        scope.requireScheduledResources(ActionEnqueueService.REQUIRED_GRANTS);
        scope.requireScheduledResources(RESOURCES);
        var validated = sources.validate(reference(request));
        scope.requireScheduledBound(validated.ownerBound());
        var row = actions.selectOne(Wrappers.<ActionExecution>lambdaQuery()
                .eq(ActionExecution::getId, request.executionId()).last("FOR UPDATE"));
        var configuration = validate(request, validated, row, false);
        scope.requireScheduledResources(ActionEnqueueService.REQUIRED_GRANTS);
        scope.requireScheduledResources(RESOURCES);
        return configuration;
    }

    /** Historical proof only: no current-execution refresh, send grant, claim or write. */
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public SendAlertConfiguration prepareLookup(ActionAdapter.Request request) {
        requireRequest(request);
        var validated = sources.validateHistorical(reference(request));
        return validate(request, validated, actions.selectById(request.executionId()), true);
    }

    private void requireRequest(ActionAdapter.Request request) {
        if (request == null || !DEFINITION.equals(request.adapter())
                || !Objects.equals(request.executionId(), request.idempotencyKey())
                || !Objects.equals(scope.owner(), request.source().owner()))
            throw IqcException.accessDenied("告警动作协议或负责人不一致");
    }

    private ActionProductionSourceValidator.Reference reference(ActionAdapter.Request request) {
        var source = request.source();
        return new ActionProductionSourceValidator.Reference(source.taskId(), source.executionId(),
                source.conversationId(), source.resultId(), source.owner());
    }

    private SendAlertConfiguration validate(ActionAdapter.Request request,
            ActionProductionSourceValidator.Validated validated, ActionExecution row, boolean historical) {
        if (validated == null || validated.policy() == null || row == null)
            throw IqcException.invalidState("告警缺少原生产政策或领取记录");
        var action = validated.policy().actions().stream()
                .filter(value -> request.source().actionId().equals(value.actionId())).findFirst().orElseThrow();
        if (action.type() != DEFINITION.type() || action.effectiveAdapterVersion() != DEFINITION.version()
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
            return SendAlertConfiguration.parse(action.configuration());
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw IqcException.invalidState("告警冻结政策损坏");
        }
    }
}
