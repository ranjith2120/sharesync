package webserver.src;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ProxyServer — reverse proxy on port 8080.
 *
 * Networking concepts:
 *  • Reverse proxy pattern  — single entry point for all client traffic
 *  • CachedThreadPool       — dynamic thread allocation for bursty traffic
 *  • Request delegation     — each connection handled by ProxyHandler
 *
 * All browser requests hit port 8080.  ProxyHandler inspects the path and
 * forwards to either FileServer (8081) or ChatServer (8082).
 */
public class ProxyServer implements Runnable {

    private final int          port;
    private final LoadBalancer fileBalancer;
    private final LoadBalancer chatBalancer;

    public ProxyServer(int port, LoadBalancer fileBalancer, LoadBalancer chatBalancer) {
        this.port         = port;
        this.fileBalancer = fileBalancer;
        this.chatBalancer = chatBalancer;
    }

    @Override
    public void run() {
        ExecutorService pool = Executors.newCachedThreadPool();

        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("[ProxyServer] Listening on port " + port + "  →  http://localhost:" + port);

            while (!Thread.currentThread().isInterrupted()) {
                Socket client = server.accept();
                pool.submit(new ProxyHandler(client, fileBalancer, chatBalancer));
            }
        } catch (IOException e) {
            System.err.println("[ProxyServer] Fatal: " + e.getMessage());
        } finally {
            pool.shutdownNow();
        }
    }
}
