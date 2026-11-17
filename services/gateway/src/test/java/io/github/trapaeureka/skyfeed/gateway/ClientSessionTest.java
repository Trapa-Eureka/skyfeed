package io.github.trapaeureka.skyfeed.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.mutiny.Uni;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClientSessionTest {

    /** A client whose sends complete only when the test says so. */
    static final class SlowClient {
        final List<String> received = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger closed = new AtomicInteger();
        private CompletableFuture<Void> inFlight;

        Uni<Void> send(String message) {
            received.add(message);
            inFlight = new CompletableFuture<>();
            return Uni.createFrom().completionStage(inFlight);
        }

        void finishSend() {
            inFlight.complete(null);
        }

        void failSend() {
            inFlight.completeExceptionally(new IllegalStateException("connection reset"));
        }

        ClientSession session(int maxPending) {
            return new ClientSession(this::send, closed::incrementAndGet, maxPending);
        }
    }

    private static ClientSession fastSession(List<String> received) {
        return new ClientSession(message -> {
            received.add(message);
            return Uni.createFrom().voidItem();
        }, () -> {}, 1000);
    }

    @Test
    @DisplayName("R-RT-03 while a client is behind, the newest position of an aircraft replaces the older ones waiting")
    void newestReplacesWaiting() {
        SlowClient client = new SlowClient();
        ClientSession session = client.session(100);

        session.offer("aircraft:a", "a at 1"); // goes out at once and stays in flight
        session.offer("aircraft:a", "a at 2");
        session.offer("aircraft:b", "b at 1");
        session.offer("aircraft:a", "a at 3");
        session.offer("aircraft:a", "a at 4");
        assertEquals(List.of("a at 1"), client.received);
        assertEquals(2, session.pending());

        client.finishSend();
        client.finishSend();

        assertEquals(List.of("a at 1", "a at 4", "b at 1"), client.received);
        assertEquals(2, session.replaced());
    }

    @Test
    @DisplayName("R-RT-03 a client that takes nothing does not delay another client")
    void slowClientDoesNotDelayOthers() {
        SlowClient stuck = new SlowClient();
        ClientSession slow = stuck.session(100);
        List<String> received = new ArrayList<>();
        ClientSession fast = fastSession(received);

        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            String message = "position " + i;
            slow.offer("aircraft:" + (i % 50), message);
            fast.offer("aircraft:" + (i % 50), message);
        }
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(10_000, received.size(), "the fast client must get every message");
        assertEquals(1, stuck.received.size(), "the stuck client took only the first message");
        assertTrue(slow.pending() <= 50, "at most one waiting message per aircraft, but " + slow.pending() + " are waiting");
        assertTrue(millis < 2_000, "offering must not wait for the stuck client, took " + millis + " ms");
    }

    @Test
    @DisplayName("messages without a key are all delivered, in order")
    void unkeyedMessagesAreKept() {
        SlowClient client = new SlowClient();
        ClientSession session = client.session(100);

        session.offer(null, "hello");
        session.offer(null, "subscribed");
        session.offer(null, "error");
        client.finishSend();
        client.finishSend();

        assertEquals(List.of("hello", "subscribed", "error"), client.received);
        assertEquals(0, session.replaced());
    }

    @Test
    @DisplayName("a long run of sends that complete at once does not exhaust the stack")
    void manyImmediateSends() {
        List<String> received = new ArrayList<>();
        ClientSession session = fastSession(received);
        for (int i = 0; i < 200_000; i++) {
            session.offer(null, "m");
        }
        assertEquals(200_000, received.size());
        assertEquals(200_000, session.sent());
    }

    @Test
    @DisplayName("a client with too much waiting is disconnected, once")
    void overflowCloses() {
        SlowClient client = new SlowClient();
        ClientSession session = client.session(3);

        for (int i = 0; i < 10; i++) {
            session.offer("aircraft:" + i, "position");
        }

        assertTrue(session.isClosed());
        assertEquals(1, client.closed.get());
        assertEquals(0, session.pending());
        session.offer(null, "ignored");
        assertEquals(1, client.received.size());
    }

    @Test
    @DisplayName("a failed send disconnects the client")
    void failedSendCloses() {
        SlowClient client = new SlowClient();
        ClientSession session = client.session(100);

        session.offer(null, "hello");
        session.offer(null, "never sent");
        client.failSend();

        assertTrue(session.isClosed());
        assertEquals(1, client.closed.get());
        assertEquals(List.of("hello"), client.received);
    }

    @Test
    @DisplayName("offers from many threads with sends completing on other threads: nothing is lost, repeated or reordered")
    void concurrentOffers() throws Exception {
        int threads = 8;
        int perThread = 5_000;
        ExecutorService network = Executors.newFixedThreadPool(2);
        ExecutorService producers = Executors.newFixedThreadPool(threads);
        List<String> received = Collections.synchronizedList(new ArrayList<>());
        ClientSession session = new ClientSession(message -> {
            received.add(message);
            return Uni.createFrom().completionStage(CompletableFuture.runAsync(() -> {}, network));
        }, () -> {}, threads * perThread + 1);

        try {
            List<CompletableFuture<Void>> done = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int thread = t;
                done.add(CompletableFuture.runAsync(() -> {
                    for (int i = 0; i < perThread; i++) {
                        session.offer(null, thread + ":" + i);
                    }
                }, producers));
            }
            CompletableFuture.allOf(done.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (session.sent() < (long) threads * perThread && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
        } finally {
            producers.shutdownNow();
            network.shutdownNow();
        }

        assertEquals(threads * perThread, received.size());
        int[] next = new int[threads];
        for (String message : received) {
            String[] parts = message.split(":");
            int thread = Integer.parseInt(parts[0]);
            assertEquals(next[thread]++, Integer.parseInt(parts[1]), "messages of thread " + thread + " out of order");
        }
        assertFalse(session.isClosed());
    }
}
