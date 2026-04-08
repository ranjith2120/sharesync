package webserver.src;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ChatHandler — handles HTTP requests routed to the ChatServer.
 *
 * Networking concepts:
 *  • SSE (Server-Sent Events)   — GET /events  → persistent text/event-stream
 *  • Long-lived connections     — SSE keeps the socket open, server pushes data
 *  • POST /chat                 — receives chat messages, publishes to EventBus
 */
public class ChatHandler implements Runnable {

    private final Socket   socket;
    private final EventBus eventBus;

    public ChatHandler(Socket socket, EventBus eventBus) {
        this.socket   = socket;
        this.eventBus = eventBus;
    }

    @Override
    public void run() {
        try {
            InputStream  rawIn  = socket.getInputStream();
            OutputStream rawOut = socket.getOutputStream();

            // ── Read request line + headers ──────────────────
            ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
            int prev = -1, curr;
            int crlfCount = 0;
            while ((curr = rawIn.read()) != -1) {
                headerBuf.write(curr);
                if (curr == '\n' && prev == '\r') crlfCount++;
                else if (curr != '\r') crlfCount = 0;
                if (crlfCount == 2) break;
                prev = curr;
            }

            String headerBlock = headerBuf.toString(StandardCharsets.UTF_8);
            if (headerBlock.isBlank()) { socket.close(); return; }

            String[] headerLines = headerBlock.split("\r\n");
            String requestLine   = headerLines[0];
            String[] parts       = requestLine.split(" ");
            if (parts.length < 2) { socket.close(); return; }

            String method = parts[0].toUpperCase();
            String path   = parts[1];

            // Parse content-length for POST
            int contentLength = 0;
            for (int i = 1; i < headerLines.length; i++) {
                if (headerLines[i].toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(headerLines[i].substring(15).trim());
                }
            }

            // ── Route ────────────────────────────────────────
            if (method.equals("GET") && path.equals("/events")) {
                handleSSE(rawOut);
            } else if (method.equals("POST") && path.equals("/chat")) {
                handleChat(rawIn, rawOut, contentLength);
            } else if (method.equals("GET") && path.equals("/status")) {
                handleStatus(rawOut);
            } else {
                sendResponse(rawOut, 404, "text/plain", "Not Found");
                socket.close();
            }

        } catch (IOException e) {
            System.err.println("[ChatHandler] Error: " + e.getMessage());
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // ── GET /events — SSE endpoint ───────────────────────────
    private void handleSSE(OutputStream out) throws IOException {
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/event-stream\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Connection: keep-alive\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.flush();

        // Register this client's output stream with the EventBus
        eventBus.addClient(out);
        System.out.println("[ChatHandler] SSE client connected (total: " + eventBus.clientCount() + ")");

        // Send a welcome event
        String welcome = "event: connected\ndata: {\"message\":\"Connected to ShareSync\"}\n\n";
        out.write(welcome.getBytes(StandardCharsets.UTF_8));
        out.flush();

        // Keep the connection alive — block until client disconnects
        try {
            // Heartbeat to detect disconnections
            while (!socket.isClosed()) {
                Thread.sleep(15000);
                String heartbeat = ": heartbeat\n\n";
                out.write(heartbeat.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (InterruptedException | IOException e) {
            // Client disconnected
        } finally {
            eventBus.removeClient(out);
            System.out.println("[ChatHandler] SSE client disconnected (total: " + eventBus.clientCount() + ")");
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // ── POST /chat — receive and broadcast a chat message ────
    private void handleChat(InputStream in, OutputStream out, int contentLength) throws IOException {
        byte[] body = new byte[contentLength];
        int offset = 0;
        while (offset < contentLength) {
            int read = in.read(body, offset, contentLength - offset);
            if (read == -1) break;
            offset += read;
        }

        String rawBody = new String(body, StandardCharsets.UTF_8).trim();

        // Expect JSON: {"user":"...", "message":"..."}
        String user    = extractJsonValue(rawBody, "user");
        String message = extractJsonValue(rawBody, "message");

        if (user == null || user.isBlank()) user = "Anonymous";
        if (message == null || message.isBlank()) {
            sendResponse(out, 400, "application/json", "{\"error\":\"Empty message\"}");
            socket.close();
            return;
        }

        // Build chat event JSON
        String chatJson = "{"
                + "\"user\":\"" + escapeJson(user) + "\","
                + "\"message\":\"" + escapeJson(message) + "\","
                + "\"timestamp\":" + System.currentTimeMillis()
                + "}";

        // Publish to EventBus
        eventBus.publish("chat-message", chatJson);
        System.out.println("[ChatHandler] Chat from " + user + ": " + message);

        sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}");
        socket.close();
    }

    // ── GET /status — server status ──────────────────────────
    private void handleStatus(OutputStream out) throws IOException {
        String json = "{"
                + "\"server\":\"ChatServer\","
                + "\"sseClients\":" + eventBus.clientCount() + ","
                + "\"timestamp\":" + System.currentTimeMillis()
                + "}";
        sendResponse(out, 200, "application/json", json);
        socket.close();
    }

    // ── Utilities ────────────────────────────────────────────

    private void sendResponse(OutputStream out, int status, String contentType, String body)
            throws IOException {
        String statusText = switch (status) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            default  -> "Unknown";
        };
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + " " + statusText + "\r\n"
                + "Content-Type: " + contentType + "; charset=utf-8\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();
    }

    /** Simple JSON value extractor — no external libs needed */
    private static String extractJsonValue(String json, String key) {
        String search = "\"" + key + "\"";
        int keyIdx = json.indexOf(search);
        if (keyIdx == -1) return null;

        int colonIdx = json.indexOf(':', keyIdx + search.length());
        if (colonIdx == -1) return null;

        // Skip whitespace after colon
        int start = colonIdx + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;

        if (start >= json.length()) return null;

        if (json.charAt(start) == '"') {
            // String value
            int end = start + 1;
            while (end < json.length()) {
                if (json.charAt(end) == '"' && json.charAt(end - 1) != '\\') break;
                end++;
            }
            return json.substring(start + 1, end);
        } else {
            // Non-string value
            int end = start;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
            return json.substring(start, end).trim();
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
