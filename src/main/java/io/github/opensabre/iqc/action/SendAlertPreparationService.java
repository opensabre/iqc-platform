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
        return SendAlertConfiguration.parse(ActionFrozenPolicy.validateOutboundRequest(
                request, validated, row, DEFINITION, historical, json).configuration());
    }
}
