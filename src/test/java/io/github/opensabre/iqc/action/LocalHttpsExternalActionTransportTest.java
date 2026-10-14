package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback TLS transport only; in-memory receiver fixtures do not prove business-system durability. */
class LocalHttpsExternalActionTransportTest {
    @TempDir static Path temporary;
    private static SSLContext tls;
    private final ObjectMapper json=new ObjectMapper();

    @BeforeAll static void certificate() throws Exception {
        var store=temporary.resolve("receiver.p12");
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),
                "-genkeypair","-alias","receiver","-keyalg","RSA","-keysize","2048","-validity","1",
                "-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1","-storetype","PKCS12",
                "-keystore",store.toString(),"-storepass","local-test-only","-keypass","local-test-only","-noprompt")
                .redirectErrorStream(true).redirectOutput(temporary.resolve("keytool.log").toFile()).start();
        if(!process.waitFor(30,TimeUnit.SECONDS)){process.destroyForcibly();fail("Local TLS certificate generation timed out");}
        assertEquals(0,process.exitValue(),()->"Local certificate generation failed");
        var keys=KeyStore.getInstance("PKCS12");
        try(var input=Files.newInputStream(store)){keys.load(input,"local-test-only".toCharArray());}
        var keyManagers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys,"local-test-only".toCharArray());
        var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(keys);
        tls=SSLContext.getInstance("TLS");tls.init(keyManagers.getKeyManagers(),trust.getTrustManagers(),null);
    }

    private final class Receiver implements AutoCloseable {
        final HttpsServer server;
        final HttpClient client;
        final AtomicInteger posts=new AtomicInteger(),gets=new AtomicInteger(),creations=new AtomicInteger(),redirectHits=new AtomicInteger();
        final ConcurrentHashMap<String,ObjectNode> receipts=new ConcurrentHashMap<>();
        volatile ObjectNode lastRequest;
        volatile boolean loseResponse,redirect;
        Receiver() throws Exception {
            server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setHttpsConfigurator(new HttpsConfigurator(tls));
            server.createContext("/events",exchange->{
                try(exchange){
                    posts.incrementAndGet();
                    if(!"POST".equals(exchange.getRequestMethod()) || !"Bearer local-test-secret".equals(exchange.getRequestHeaders().getFirst("Authorization"))){exchange.sendResponseHeaders(403,-1);return;}
                    lastRequest=(ObjectNode)json.readTree(exchange.getRequestBody().readNBytes(32769));
                    var key=lastRequest.path("idempotencyKey").asText();
                    var receipt=receipts.computeIfAbsent(key,ignored->json.createObjectNode()
                            .put("schemaVersion",ExternalActionReceiptVerifier.SCHEMA).put("idempotencyKey",key)
                            .put("inputSnapshotHash",lastRequest.path("inputSnapshotHash").asText())
                            .put("outcome","CONFIRMED_SUCCESS").put("evidenceReference","receipt:"+creations.incrementAndGet())
                            .put("resultReference","owned:1"));
                    if(loseResponse)return;
                    if(redirect){exchange.getResponseHeaders().set("Location","/redirect-target");exchange.sendResponseHeaders(302,-1);return;}
                    var bytes=json.writeValueAsBytes(receipt);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
                }
            });
            server.createContext("/receipts/",exchange->{
                try(exchange){
                    gets.incrementAndGet();
                    if(!"GET".equals(exchange.getRequestMethod())){exchange.sendResponseHeaders(405,-1);return;}
                    var receipt=receipts.get(exchange.getRequestURI().getPath().substring("/receipts/".length()));
                    if(receipt==null){exchange.sendResponseHeaders(404,-1);return;}
                    var bytes=json.writeValueAsBytes(receipt);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
                }
            });
            server.createContext("/redirect-target",exchange->{try(exchange){redirectHits.incrementAndGet();exchange.sendResponseHeaders(500,-1);}});
            server.start();
            client=HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        }
        ExternalActionDestinations.Destination destination(ActionExecutionContract.Type type){
            var base="https://127.0.0.1:"+server.getAddress().getPort();
            return new ExternalActionDestinations.Destination("receiver",1,type,URI.create(base+"/events"),URI.create(base+"/receipts/"),5000,15000,4096,"IQC_TEST_TOKEN");
        }
        HttpExternalActionTransport transport(){return new HttpExternalActionTransport(d->client,name->"local-test-secret");}
        public void close(){server.stop(0);client.close();}
    }
    private ActionAdapter.Request request(ActionExecutionContract.Type type){
        var source=new ActionExecutionContract.Source("task","execution","conversation","result","result",SelectorAssetService.hash("{}"),"external","alice");
        var key=source.idempotencyKey(type);
        return new ActionAdapter.Request(key,key,source,ExternalActionPreparationService.definition(type),1,"{}",SelectorAssetService.hash("{}"),"{}");
    }
    private ExternalActionConfiguration configuration(){return new ExternalActionConfiguration("receiver",1,json.createObjectNode().put("ticket","source:1"));}

    @ParameterizedTest @EnumSource(value=ActionExecutionContract.Type.class,names={"WEBHOOK","CREATE_WORK_ORDER","CREATE_COACHING_TASK"})
    void realTlsRequestCarriesFrozenSourceAndDuplicateKeyReturnsSameReceipt(ActionExecutionContract.Type type) throws Exception {
        try(var receiver=new Receiver()){
            var request=request(type);var transport=receiver.transport();var destination=receiver.destination(type);
            var first=transport.send(request,configuration(),destination);
            assertEquals(ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,first.outcome());
            assertEquals(first,transport.send(request,configuration(),destination));
            assertEquals(first,transport.lookup(request,configuration(),destination));
            assertEquals(2,receiver.posts.get());assertEquals(1,receiver.gets.get());assertEquals(1,receiver.creations.get());
            assertEquals("iqc-external-action-request-v1",receiver.lastRequest.path("schemaVersion").asText());
            assertEquals(type.name(),receiver.lastRequest.path("actionType").asText());
            assertEquals(json.valueToTree(request.source()),receiver.lastRequest.get("source"));
            assertEquals(configuration().payload(),receiver.lastRequest.get("payload"));
        }
    }
    @Test void responseLostAfterAcceptanceOnlyRecoversByGet() throws Exception {
        try(var receiver=new Receiver()){
            receiver.loseResponse=true;var request=request(ActionExecutionContract.Type.WEBHOOK);
            var destination=receiver.destination(request.adapter().type());var transport=receiver.transport();
            assertEquals(ActionExecutionContract.Outcome.UNKNOWN,transport.send(request,configuration(),destination).outcome());
            assertEquals(ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,transport.lookup(request,configuration(),destination).outcome());
            assertEquals(1,receiver.posts.get());assertEquals(1,receiver.gets.get());assertEquals(1,receiver.creations.get());
        }
    }
    @Test void redirectDoesNotReachAnotherEndpoint() throws Exception {
        try(var receiver=new Receiver()){
            receiver.redirect=true;var request=request(ActionExecutionContract.Type.WEBHOOK);
            assertEquals(ActionExecutionContract.Outcome.UNKNOWN,receiver.transport().send(request,configuration(),receiver.destination(request.adapter().type())).outcome());
            assertEquals(1,receiver.posts.get());assertEquals(0,receiver.redirectHits.get());
        }
    }
}
