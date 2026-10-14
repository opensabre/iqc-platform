package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.iqc.shared.IqcDataScope;
import java.util.Objects;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Executes destination-owned operations after local source proof commits; no parallel business model. */
public class ExternalActionAdapter implements ActionAdapter {
    private final Definition definition;
    private final ExternalActionPreparationService preparation;
    private final ExternalActionDestinations destinations;
    private final HttpExternalActionTransport transport;
    private final IqcDataScope scope;

    public ExternalActionAdapter(ActionExecutionContract.Type type, ExternalActionPreparationService preparation,
            ExternalActionDestinations destinations, HttpExternalActionTransport transport, IqcDataScope scope) {
        definition = ExternalActionPreparationService.definition(type);
        this.preparation = Objects.requireNonNull(preparation); this.destinations = Objects.requireNonNull(destinations);
        this.transport = Objects.requireNonNull(transport); this.scope = Objects.requireNonNull(scope);
    }
    @Override public Definition definition() { return definition; }
    /** Publication and claim require an exact locally configured route, without network or secret lookup. */
    @Override public void validateConfiguration(JsonNode configuration) {
        destinations.require(ExternalActionConfiguration.parse(configuration), definition.type());
    }
    /** Reject direct invocation inside any existing transaction, as with the original notification adapter. */
    @Override @Transactional(propagation=Propagation.NEVER)
    public Receipt execute(Request request) {
        requireRequest(request);
        try {
            var configuration = preparation.prepareSend(request);
            var destination = destinations.require(configuration, definition.type());
            scope.requireScheduledResources(ActionEnqueueService.REQUIRED_GRANTS);
            return transport.send(request, configuration, destination);
        } catch (Exception unavailable) { return ExternalActionReceiptVerifier.unknown(request); }
    }
    /** Historical source proof only; no new send grant or POST fallback. */
    @Override @Transactional(propagation=Propagation.NEVER)
    public Receipt reconcile(Request request) {
        requireRequest(request);
        try {
            var configuration = preparation.prepareLookup(request);
            return transport.lookup(request, configuration, destinations.require(configuration, definition.type()));
        } catch (Exception unavailable) { return ExternalActionReceiptVerifier.unknown(request); }
    }
    private void requireRequest(Request request) {
        if (request == null || !definition.equals(request.adapter()))
            throw new IllegalArgumentException("External adapter type/version mismatch");
    }
}
