package io.github.trapaeureka.skyfeed.gateway;

import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/** The WebSocket endpoint of the live feed. The messages are described in contracts/README.md. */
@WebSocket(path = "/ws/v1")
public class LiveEndpoint {

    private static final Logger LOG = Logger.getLogger(LiveEndpoint.class);

    @Inject
    LiveFeed feed;

    @ConfigProperty(name = "skyfeed.gateway.heartbeat-interval")
    Duration heartbeatInterval;

    @ConfigProperty(name = "skyfeed.gateway.max-pending-messages")
    int maxPendingMessages;

    @OnOpen
    void onOpen(WebSocketConnection connection) {
        ClientSession session = new ClientSession(connection::sendText, () -> {
            LOG.infof("Closing client %s: a send failed or too many messages were waiting", connection.id());
            connection.close().subscribe().with(done -> {}, failure -> {});
        }, maxPendingMessages);
        feed.add(connection.id(), session);
        session.offer(null, Messages.hello(heartbeatInterval.toMillis() / 1000.0));
    }

    @OnTextMessage
    void onMessage(String text, WebSocketConnection connection) {
        ClientSession session = feed.get(connection.id());
        if (session == null) {
            return;
        }
        try {
            Messages.Subscription subscription = Messages.parseSubscribe(text);
            session.subscribe(subscription.area(), subscription.sensors());
            session.offer(null, Messages.subscribed(subscription));
        } catch (IllegalArgumentException e) {
            session.offer(null, Messages.error(e.getMessage()));
        }
    }

    @OnClose
    void onClose(WebSocketConnection connection) {
        feed.remove(connection.id());
    }
}
