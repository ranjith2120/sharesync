package webserver.src;

import java.io.OutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * EventBus — shared pub/sub infrastructure between servers.
 *
 * Networking concepts:
 *  • BlockingQueue    — thread-safe producer/consumer channel
 *  • CopyOnWriteArrayList — safe iteration while clients connect/disconnect
 *  • SSE framing      — "data: …\n\n" written to each subscriber's OutputStream
 *
 * FileServer publishes events → EventBus → ChatServer's SSE clients receive them.
 */
public class EventBus {

    // ── Event wrapper ────────────────────────────────────────
    public record Event(String type, String jsonPayload) {}

    // ── Shared state ─────────────────────────────────────────
    private final BlockingQueue<Event> queue = new LinkedBlockingQueue<>();
    private final CopyOnWriteArrayList<OutputStream> sseClients = new CopyOnWriteArrayList<>();

    // ── Publisher API (called by FileServer / ChatHandler) ───
    public void publish(String type, String jsonPayload) {
        queue.offer(new Event(type, jsonPayload));
    }

    // ── Subscriber API (called by ChatHandler for each SSE client) ─
    public void addClient(OutputStream out)    { sseClients.add(out); }
    public void removeClient(OutputStream out) { sseClients.remove(out); }
    public int  clientCount()                  { return sseClients.size(); }

    // ── Broadcaster thread (started once from ShareSync) ─────
    public void startBroadcaster() {
        Thread broadcaster = new Thread(() -> {
            System.out.println("[EventBus] Broadcaster thread started");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Event event = queue.take();              // blocks until an event arrives
                    String sseFrame = formatSSE(event);
                    byte[] bytes = sseFrame.getBytes(StandardCharsets.UTF_8);

                    for (OutputStream client : sseClients) {
                        try {
                            client.write(bytes);
                            client.flush();
                        } catch (IOException e) {
                            // Client disconnected — remove silently
                            sseClients.remove(client);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            System.out.println("[EventBus] Broadcaster thread stopped");
        }, "eventbus-broadcaster");
        broadcaster.setDaemon(true);
        broadcaster.start();
    }

    // ── SSE frame formatting ─────────────────────────────────
    private String formatSSE(Event event) {
        StringBuilder sb = new StringBuilder();
        sb.append("event: ").append(event.type()).append('\n');
        // SSE spec: each line of data must start with "data: "
        for (String line : event.jsonPayload().split("\n")) {
            sb.append("data: ").append(line).append('\n');
        }
        sb.append('\n');  // blank line = end of event
        return sb.toString();
    }
}
