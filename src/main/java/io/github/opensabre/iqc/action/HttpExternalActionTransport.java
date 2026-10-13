package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Bounded HTTPS transport; recovery reads receipts and never falls back to sending again. */
public final class HttpExternalActionTransport {
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(65536).build())
            .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Function<ExternalActionDestinations.Destination,HttpClient> clients;
    private final Function<String,String> secrets;

    /** Clients are reused per immutable destination, with redirect following disabled. */
    public HttpExternalActionTransport() {
        var cache = new ConcurrentHashMap<ExternalActionDestinations.Destination,HttpClient>();
        clients = destination -> cache.computeIfAbsent(destination, profile -> HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(profile.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER).build());
        secrets = System::getenv;
    }

    HttpExternalActionTransport(Function<ExternalActionDestinations.Destination,HttpClient> clients,
            Function<String,String> secrets) {
        this.clients = Objects.requireNonNull(clients); this.secrets = Objects.requireNonNull(secrets);
    }

    /** Caller must have completed preparation and current authorization outside any analysis transaction. */
    public ActionAdapter.Receipt send(ActionAdapter.Request request, ExternalActionConfiguration configuration,
            ExternalActionDestinations.Destination destination) {
        requireDestination(request, configuration, destination);
        return exchange(request, configuration, destination, false);
    }

    /** Lookup has no POST fallback for missing evidence, denied access, malformed bodies or timeouts. */
    public ActionAdapter.Receipt lookup(ActionAdapter.Request request, ExternalActionConfiguration configuration,
            ExternalActionDestinations.Destination destination) {
        requireDestination(request, configuration, destination);
        return exchange(request, configuration, destination, true);
    }

    private ActionAdapter.Receipt exchange(ActionAdapter.Request request, ExternalActionConfiguration configuration,
            ExternalActionDestinations.Destination destination, boolean lookup) {
        try {
            var builder = HttpRequest.newBuilder(lookup ? destination.receiptUri(request.idempotencyKey()) : destination.sendUri())
                    .timeout(Duration.ofMillis(destination.requestTimeoutMs()))
                    .header("Accept", "application/json");
            if (destination.bearerTokenEnvironmentVariable() != null) {
                var token = secrets.apply(destination.bearerTokenEnvironmentVariable());
                if (token == null || !token.matches("[!-~]{1,8192}")) return ExternalActionReceiptVerifier.unknown(request);
                builder.header("Authorization", "Bearer " + token);
            }
            if (lookup) builder.GET();
            else {
                var body = json.createObjectNode().put("schemaVersion", "iqc-external-action-request-v1")
                        .put("actionType", request.adapter().type().name()).put("idempotencyKey", request.idempotencyKey())
                        .put("inputSnapshotHash", request.inputSnapshotHash());
                body.set("source", json.valueToTree(request.source()));
                body.set("payload", configuration.payload());
                builder.header("Content-Type", "application/json").header("Idempotency-Key", request.idempotencyKey())
                        .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
            }
            var future = clients.apply(destination).sendAsync(builder.build(),
                    info -> new BoundedActionResponseSubscriber(destination.maximumResponseBytes()));
            try {
                // This deadline covers headers and the complete body, including a stalled streaming response.
                var response = future.get(destination.requestTimeoutMs(), TimeUnit.MILLISECONDS);
                if (response.statusCode() < 200 || response.statusCode() >= 300)
                    return ExternalActionReceiptVerifier.unknown(request);
                var bytes = response.body();
                if (bytes == null || bytes.length > destination.maximumResponseBytes())
                    return ExternalActionReceiptVerifier.unknown(request);
                return ExternalActionReceiptVerifier.verify(request, json.readTree(bytes));
            } finally {
                if (!future.isDone()) future.cancel(true);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ExternalActionReceiptVerifier.unknown(request);
        } catch (Exception unavailable) {
            return ExternalActionReceiptVerifier.unknown(request);
        }
    }

    private static void requireDestination(ActionAdapter.Request request, ExternalActionConfiguration configuration,
            ExternalActionDestinations.Destination destination) {
        Objects.requireNonNull(request); Objects.requireNonNull(configuration); Objects.requireNonNull(destination);
        if (!ExternalActionPreparationService.definition(request.adapter().type()).equals(request.adapter())
                || destination.type() != request.adapter().type() || !destination.id().equals(configuration.destinationId())
                || destination.version() != configuration.destinationVersion())
            throw new IllegalArgumentException("External transport destination does not match frozen declaration");
    }
}
