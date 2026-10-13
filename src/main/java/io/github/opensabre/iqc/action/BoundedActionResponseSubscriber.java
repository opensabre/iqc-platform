package io.github.opensabre.iqc.action;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Limits receipt bytes while streaming, rather than buffering an unbounded remote response first. */
final class BoundedActionResponseSubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final int maximum;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private Flow.Subscription subscription;

    BoundedActionResponseSubscriber(int maximum) {
        if (maximum < 1 || maximum > 65536) throw new IllegalArgumentException("Invalid receipt byte limit");
        this.maximum = maximum;
    }

    @Override public CompletionStage<byte[]> getBody() { return body; }

    @Override public void onSubscribe(Flow.Subscription incoming) {
        if (subscription != null || body.isDone()) { incoming.cancel(); return; }
        subscription = incoming;
        incoming.request(1);
    }

    @Override public void onNext(List<ByteBuffer> buffers) {
        if (body.isDone()) return;
        for (var buffer : buffers) {
            if (buffer.remaining() > maximum - bytes.size()) {
                subscription.cancel();
                body.completeExceptionally(new IllegalArgumentException("External receipt exceeds byte limit"));
                return;
            }
            while (buffer.hasRemaining()) bytes.write(buffer.get());
        }
        subscription.request(1);
    }

    @Override public void onError(Throwable failure) { body.completeExceptionally(failure); }
    @Override public void onComplete() { body.complete(bytes.toByteArray()); }
}
