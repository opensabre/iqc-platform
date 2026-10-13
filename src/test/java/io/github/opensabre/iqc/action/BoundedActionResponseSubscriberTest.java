package io.github.opensabre.iqc.action;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;

class BoundedActionResponseSubscriberTest {
    static final class Subscription implements Flow.Subscription {
        long requests;
        boolean cancelled;
        public void request(long count) { requests += count; }
        public void cancel() { cancelled = true; }
    }

    @Test void acceptsExactlyBoundedSplitAndDirectBuffers() {
        var subscriber = new BoundedActionResponseSubscriber(4);
        var subscription = new Subscription();
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2})));
        var direct = ByteBuffer.allocateDirect(2);
        direct.put(new byte[]{3,4}).flip();
        subscriber.onNext(List.of(direct));
        subscriber.onComplete();
        assertArrayEquals(new byte[]{1,2,3,4}, subscriber.getBody().toCompletableFuture().join());
        assertFalse(subscription.cancelled);
        assertEquals(3, subscription.requests);
    }

    @Test void cancelsOversizeAcrossChunksAndCannotTurnFailureIntoSuccess() {
        var subscriber = new BoundedActionResponseSubscriber(3);
        var subscription = new Subscription();
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2})));
        var oversized = ByteBuffer.wrap(new byte[]{3,4});
        subscriber.onNext(List.of(oversized));
        assertTrue(subscription.cancelled);
        assertEquals(0, oversized.position());
        subscriber.onComplete();
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{5})));
        assertThrows(CompletionException.class, () -> subscriber.getBody().toCompletableFuture().join());
        assertEquals(2, subscription.requests);
    }

    @Test void preservesTransportFailureAndRejectsDuplicateSubscription() {
        var subscriber = new BoundedActionResponseSubscriber(3);
        var first = new Subscription();
        var duplicate = new Subscription();
        subscriber.onSubscribe(first);
        subscriber.onSubscribe(duplicate);
        assertTrue(duplicate.cancelled);
        var failure = new IllegalStateException("transport interrupted");
        subscriber.onError(failure);
        var thrown = assertThrows(CompletionException.class, () -> subscriber.getBody().toCompletableFuture().join());
        assertSame(failure, thrown.getCause());
        assertThrows(IllegalArgumentException.class, () -> new BoundedActionResponseSubscriber(65537));
    }
}
