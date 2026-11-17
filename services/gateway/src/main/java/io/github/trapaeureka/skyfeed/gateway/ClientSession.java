package io.github.trapaeureka.skyfeed.gateway;

import io.smallrye.mutiny.Uni;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * What one connected client still has to be sent, and the client's subscription.
 *
 * <p>Messages are handed to the connection one at a time: the next one goes out only when the previous send has
 * completed. Whoever offers a message never waits for the client, so one slow client cannot hold up the feed or
 * the other clients (R-RT-03). While a client is behind, a newer message with the same key replaces the older one
 * that is still waiting, so a slow client is sent the newest position of each aircraft, not a growing backlog.
 */
public final class ClientSession {

    private final Function<String, Uni<Void>> sender;
    private final Runnable closer;
    private final int maxPending;

    /** Waiting messages by key, in the order their keys first appeared. Guarded by this. */
    private final LinkedHashMap<String, String> pending = new LinkedHashMap<>();
    private boolean sending; // a send is in flight
    private boolean draining; // some thread is inside drain()
    private boolean closed;
    private long unkeyed;
    private long sent;
    private long replaced;

    private volatile Area area;
    private volatile boolean sensors;

    /**
     * @param sender sends one text message; the Uni completes when the connection has taken it
     * @param closer closes the connection; called at most once, when a send fails or too much is waiting
     * @param maxPending how many messages may wait before the client is disconnected
     */
    public ClientSession(Function<String, Uni<Void>> sender, Runnable closer, int maxPending) {
        this.sender = sender;
        this.closer = closer;
        this.maxPending = maxPending;
    }

    /**
     * Queues a message and returns at once.
     *
     * @param key messages with the same key replace each other while they wait; null for a message that must
     *            always be delivered
     */
    public void offer(String key, String message) {
        boolean overflow;
        synchronized (this) {
            if (closed) {
                return;
            }
            if (pending.put(key != null ? key : "#" + unkeyed++, message) != null) {
                replaced++;
            }
            overflow = pending.size() > maxPending;
        }
        if (overflow) {
            close();
        } else {
            drain();
        }
    }

    /** Sends waiting messages until one send is in flight or nothing waits. Safe to call from any thread, and from a send's own completion. */
    private void drain() {
        synchronized (this) {
            if (draining) {
                return; // the thread already in the loop will see the new state
            }
            draining = true;
        }
        while (true) {
            String next;
            synchronized (this) {
                if (sending || closed || pending.isEmpty()) {
                    draining = false;
                    return;
                }
                Iterator<Map.Entry<String, String>> first = pending.entrySet().iterator();
                next = first.next().getValue();
                first.remove();
                sending = true;
            }
            sender.apply(next).subscribe().with(done -> {
                synchronized (this) {
                    sending = false;
                    sent++;
                }
                drain();
            }, failure -> close());
        }
    }

    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            pending.clear();
        }
        closer.run();
    }

    public void subscribe(Area area, boolean sensors) {
        this.area = area;
        this.sensors = sensors;
    }

    public Area area() {
        return area;
    }

    public boolean sensors() {
        return sensors;
    }

    public synchronized long sent() {
        return sent;
    }

    /** How many waiting messages were replaced by a newer one with the same key. */
    public synchronized long replaced() {
        return replaced;
    }

    public synchronized int pending() {
        return pending.size();
    }

    public synchronized boolean isClosed() {
        return closed;
    }
}
