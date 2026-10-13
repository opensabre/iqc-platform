package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the actual request builder and receipt decoder; no external business service is contacted. */
class HttpExternalActionTransportTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = mock(HttpClient.class);
    private final HttpExternalActionTransport transport = new HttpExternalActionTransport(d -> client, name -> "secret");
    private ActionAdapter.Request request() {
        var source = new ActionExecutionContract.Source("task", "execution", "conversation", "result", "result",
                SelectorAssetService.hash("{}"), "webhook", "alice");
        var type = ActionExecutionContract.Type.WEBHOOK;
        var key = source.idempotencyKey(type);
        return new ActionAdapter.Request(key, key, source, ExternalActionPreparationService.definition(type),
                1, "{}", SelectorAssetService.hash("{}"), "{}");
    }
    private ExternalActionConfiguration configuration() { return new ExternalActionConfiguration("receiver",1,json.createObjectNode()); }
    private ExternalActionDestinations.Destination destination(int timeout) {
        return new ExternalActionDestinations.Destination("receiver",1,ActionExecutionContract.Type.WEBHOOK,
                URI.create("https://receiver.example/events"),URI.create("https://receiver.example/receipts/"),
                1,timeout,4096,"IQC_RECEIVER_TOKEN");
    }
    private String receipt(ActionAdapter.Request request) {
        return json.createObjectNode().put("schemaVersion",ExternalActionReceiptVerifier.SCHEMA)
                .put("idempotencyKey",request.idempotencyKey()).put("inputSnapshotHash",request.inputSnapshotHash())
                .put("outcome","CONFIRMED_SUCCESS").put("evidenceReference","receipt:123")
                .put("resultReference","work:123").toString();
    }
    @SuppressWarnings("unchecked")
    private void response(int status,String body) {
        HttpResponse<byte[]> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        when(client.sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
    }
    @Test void postsWithFrozenIdentityAndAcceptsOnlyBoundReceipt() {
        var request=request();response(200,receipt(request));
        assertEquals(ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,
                transport.send(request,configuration(),destination(1000)).outcome());
        var captured=ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).sendAsync(captured.capture(),any(HttpResponse.BodyHandler.class));
        assertEquals("POST",captured.getValue().method());
        assertEquals(destination(1000).sendUri(),captured.getValue().uri());
        assertEquals(request.idempotencyKey(),captured.getValue().headers().firstValue("Idempotency-Key").orElseThrow());
        assertEquals("Bearer secret",captured.getValue().headers().firstValue("Authorization").orElseThrow());
        assertTrue(captured.getValue().bodyPublisher().orElseThrow().contentLength()>0);
    }
    @Test void missingReceiptLookupRemainsGetOnly() {
        var request=request();response(404,"{}");
        assertEquals(ActionExecutionContract.Outcome.UNKNOWN,transport.lookup(request,configuration(),destination(1000)).outcome());
        var captured=ArgumentCaptor.forClass(HttpRequest.class);
        verify(client,times(1)).sendAsync(captured.capture(),any(HttpResponse.BodyHandler.class));
        assertEquals("GET",captured.getValue().method());
        assertEquals(destination(1000).receiptUri(request.idempotencyKey()),captured.getValue().uri());
        assertTrue(captured.getValue().bodyPublisher().isEmpty());
    }
    @Test void malformedMismatchedAndRedirectResponsesCannotConfirmSuccess() {
        var request=request();
        for(var body:new String[]{receipt(request).replace(request.inputSnapshotHash(),"a".repeat(64)),
                receipt(request)+" {}",receipt(request).replace("\"outcome\":", "\"outcome\":\"UNKNOWN\",\"outcome\":"),"{}"}) {
            response(200,body);
            assertEquals(ActionExecutionContract.Outcome.UNKNOWN,transport.send(request,configuration(),destination(1000)).outcome());
        }
        response(302,receipt(request));
        assertEquals(ActionExecutionContract.Outcome.UNKNOWN,transport.send(request,configuration(),destination(1000)).outcome());
    }
    @SuppressWarnings("unchecked")
    @Test void unfinishedBodyDeadlineCancelsRequestAndMissingSecretPreventsIo() {
        var future=new CompletableFuture<HttpResponse<byte[]>>();
        when(client.sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(future);
        assertEquals(ActionExecutionContract.Outcome.UNKNOWN,transport.send(request(),configuration(),destination(1)).outcome());
        assertTrue(future.isCancelled());
        clearInvocations(client);
        var unavailable=new HttpExternalActionTransport(d->client,name->null);
        assertEquals(ActionExecutionContract.Outcome.UNKNOWN,unavailable.send(request(),configuration(),destination(1000)).outcome());
        verifyNoInteractions(client);
    }
    @Test void durableFailureRequiresEvidenceAndBoundedCode() throws Exception {
        var request=request();var body=json.readTree(receipt(request));
        var object=(com.fasterxml.jackson.databind.node.ObjectNode)body;
        object.put("outcome","CONFIRMED_RETRYABLE_FAILURE").put("errorCode","REMOTE_BUSY");
        assertEquals(ActionExecutionContract.Outcome.CONFIRMED_RETRYABLE_FAILURE,ExternalActionReceiptVerifier.verify(request,body).outcome());
        object.remove("evidenceReference");
        assertEquals(ActionExecutionContract.Outcome.UNKNOWN,ExternalActionReceiptVerifier.verify(request,body).outcome());
        object.put("evidenceReference","receipt:123").put("errorCode","unbounded provider message");
        assertEquals(ActionExecutionContract.Outcome.UNKNOWN,ExternalActionReceiptVerifier.verify(request,body).outcome());
    }
}
