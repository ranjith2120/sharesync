package webserver.src;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ChatServer — HTTP server for SSE and chat on port 8082.
 *
 * Networking concepts:
 *  • ServerSocket          — listens for incoming TCP connections
 *  • CachedThreadPool      — unbounded pool for long-lived SSE connections
 *  • Delegation pattern    — each socket dispatched to ChatHandler
 *
 * CachedThreadPool is chosen over FixedThreadPool because SSE connections are
 * long-lived and we don't want to block new connections when all threads are busy.
 */
public class ChatServer implements Runnable {

    private final int      port;
    private final EventBus eventBus;

    public ChatServer(int port, EventBus eventBus) {
        this.port     = port;
        this.eventBus = eventBus;
    }

    @Override
    public void run() {
        ExecutorService pool = Executors.newCachedThreadPool();

        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("[ChatServer] Listening on port " + port);

            while (!Thread.currentThread().isInterrupted()) {
                Socket client = server.accept();
                pool.submit(new ChatHandler(client, eventBus));
            }
        } catch (IOException e) {
            System.err.println("[ChatServer] Fatal: " + e.getMessage());
        } finally {
            pool.shutdownNow();
        }
    }
}
