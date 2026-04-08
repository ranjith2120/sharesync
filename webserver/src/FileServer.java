package webserver.src;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FileServer — HTTP server for file operations on port 8081.
 *
 * Networking concepts:
 *  • ServerSocket       — listens for incoming TCP connections
 *  • FixedThreadPool    — limits concurrency to 10 simultaneous uploads
 *  • Delegation pattern — each accepted socket dispatched to FileHandler
 */
public class FileServer implements Runnable {

    private final int      port;
    private final EventBus eventBus;

    public FileServer(int port, EventBus eventBus) {
        this.port     = port;
        this.eventBus = eventBus;
    }

    @Override
    public void run() {
        ExecutorService pool = Executors.newFixedThreadPool(10);

        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("[FileServer] Listening on port " + port);

            while (!Thread.currentThread().isInterrupted()) {
                Socket client = server.accept();
                pool.submit(new FileHandler(client, eventBus));
            }
        } catch (IOException e) {
            System.err.println("[FileServer] Fatal: " + e.getMessage());
        } finally {
            pool.shutdownNow();
        }
    }
}
