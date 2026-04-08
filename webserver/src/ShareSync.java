package webserver.src;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ShareSync — main launcher that starts all 3 servers in a single JVM.
 *
 * Architecture:
 *   Browser → ProxyServer:8080 → routes to:
 *                                ├─ FileServer:8081  (upload, download, static files)
 *                                └─ ChatServer:8082  (SSE events, chat messages)
 *
 *   FileServer ──publishes──→ EventBus (BlockingQueue) ──→ ChatServer ──pushes──→ SSE clients
 *
 * Start-up order:
 *   1. EventBus broadcaster thread
 *   2. FileServer (port 8081)
 *   3. ChatServer (port 8082)
 *   4. ProxyServer (port 8080)
 */
public class ShareSync {

    public static void main(String[] args) throws Exception {
        System.out.println("╔═══════════════════════════════════════════╗");
        System.out.println("║          ShareSync — Starting Up          ║");
        System.out.println("╚═══════════════════════════════════════════╝");

        // Ensure uploads directory exists
        Files.createDirectories(Path.of("webserver", "uploads"));

        // ── 1. Shared EventBus ───────────────────────────────
        EventBus eventBus = new EventBus();
        eventBus.startBroadcaster();
        System.out.println("[ShareSync] EventBus broadcaster started");

        // ── 2. FileServer on 8081 ────────────────────────────
        Thread fileThread = new Thread(new FileServer(8081, eventBus), "file-server");
        fileThread.setDaemon(true);
        fileThread.start();

        // ── 3. ChatServer on 8082 ────────────────────────────
        Thread chatThread = new Thread(new ChatServer(8082, eventBus), "chat-server");
        chatThread.setDaemon(true);
        chatThread.start();

        // Small delay to let backends bind their ports
        Thread.sleep(300);

        // ── 4. Load Balancers ────────────────────────────────
        LoadBalancer fileBalancer = new LoadBalancer(8081);
        LoadBalancer chatBalancer = new LoadBalancer(8082);

        // ── 5. ProxyServer on 8080 ───────────────────────────
        Thread proxyThread = new Thread(
                new ProxyServer(8080, fileBalancer, chatBalancer), "proxy-server");
        proxyThread.setDaemon(false);  // keep JVM alive
        proxyThread.start();

        System.out.println();
        System.out.println("╔═══════════════════════════════════════════╗");
        System.out.println("║   All servers running!                    ║");
        System.out.println("║   Open http://localhost:8080 in browser   ║");
        System.out.println("╚═══════════════════════════════════════════╝");
        System.out.println();

        // Block main thread
        proxyThread.join();
    }
}
