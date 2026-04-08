package webserver.src;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * ProxyHandler — routes incoming HTTP requests to the appropriate backend server.
 *
 * Networking concepts:
 *  • Reverse proxy           — client connects to 8080, proxy forwards to backend
 *  • HTTP forwarding via raw sockets — reads request, opens new socket to backend
 *  • Request routing         — path-based routing (/events, /chat, /status → ChatServer)
 *  • Load balancing          — uses LoadBalancer for round-robin port selection
 *  • Full-duplex byte relay  — streams request body AND response back transparently
 */
public class ProxyHandler implements Runnable {

    private final Socket       clientSocket;
    private final LoadBalancer fileBalancer;
    private final LoadBalancer chatBalancer;

    public ProxyHandler(Socket clientSocket, LoadBalancer fileBalancer, LoadBalancer chatBalancer) {
        this.clientSocket = clientSocket;
        this.fileBalancer = fileBalancer;
        this.chatBalancer = chatBalancer;
    }

    @Override
    public void run() {
        try (clientSocket;
             InputStream  clientIn  = clientSocket.getInputStream();
             OutputStream clientOut = clientSocket.getOutputStream()) {

            // ── Read request headers (peek to determine route) ─
            ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
            int prev = -1, curr;
            int crlfCount = 0;
            while ((curr = clientIn.read()) != -1) {
                headerBuf.write(curr);
                if (curr == '\n' && prev == '\r') crlfCount++;
                else if (curr != '\r') crlfCount = 0;
                if (crlfCount == 2) break;
                prev = curr;
            }

            byte[] headerBytes = headerBuf.toByteArray();
            String headerBlock = new String(headerBytes, StandardCharsets.UTF_8);
            if (headerBlock.isBlank()) return;

            String[] headerLines = headerBlock.split("\r\n");
            String requestLine   = headerLines[0];
            String[] parts       = requestLine.split(" ");
            if (parts.length < 2) return;

            String method = parts[0];
            String fullPath = parts[1];
            
            // Strip query parameters for routing (e.g., /status?auth=... -> /status)
            String path = fullPath.contains("?")
                        ? fullPath.substring(0, fullPath.indexOf('?'))
                        : fullPath;

            // ── Determine content length for body forwarding ─
            int contentLength = 0;
            for (int i = 1; i < headerLines.length; i++) {
                if (headerLines[i].toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(headerLines[i].substring(15).trim());
                }
            }

            // ── Route to backend ─────────────────────────────
            int backendPort;
            boolean isChatRoute = path.equals("/events")
                               || path.equals("/chat");

            if (isChatRoute) {
                backendPort = chatBalancer.nextPort();
            } else {
                backendPort = fileBalancer.nextPort();
            }

            // ── Forward to backend via new socket ────────────
            try (Socket backend = new Socket("127.0.0.1", backendPort);
                 InputStream  backendIn  = backend.getInputStream();
                 OutputStream backendOut = backend.getOutputStream()) {

                // Forward request headers
                backendOut.write(headerBytes);

                // Forward request body if present
                if (contentLength > 0) {
                    relay(clientIn, backendOut, contentLength);
                }
                backendOut.flush();

                // ── Relay response back to client ────────────
                // For SSE connections, we need streaming relay
                if (path.equals("/events")) {
                    // SSE: stream indefinitely until disconnect
                    streamRelay(backendIn, clientOut);
                } else {
                    // Normal HTTP: relay entire response
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = backendIn.read(buffer)) != -1) {
                        clientOut.write(buffer, 0, bytesRead);
                        clientOut.flush();
                    }
                }
            }

        } catch (IOException e) {
            // Connection reset, client disconnected, etc. — normal for proxy
            if (!e.getMessage().contains("Connection reset") &&
                !e.getMessage().contains("Socket closed") &&
                !e.getMessage().contains("Broken pipe")) {
                System.err.println("[ProxyHandler] " + e.getMessage());
            }
        }
    }

    /** Relay exactly `length` bytes from in to out */
    private void relay(InputStream in, OutputStream out, int length) throws IOException {
        byte[] buf = new byte[Math.min(length, 8192)];
        int remaining = length;
        while (remaining > 0) {
            int toRead = Math.min(buf.length, remaining);
            int read = in.read(buf, 0, toRead);
            if (read == -1) break;
            out.write(buf, 0, read);
            remaining -= read;
        }
    }

    /** Stream-relay for SSE: forward bytes as they arrive until disconnect */
    private void streamRelay(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
            out.flush();
        }
    }
}
