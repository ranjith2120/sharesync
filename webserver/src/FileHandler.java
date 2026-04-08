package webserver.src;

import java.io.*;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/**
 * FileHandler — handles HTTP requests routed to the FileServer.
 *
 * Networking concepts:
 *  • Multipart form-data parsing   (POST /upload)
 *  • Binary I/O streaming          (GET /download/*)
 *  • Content-Disposition header    (forces browser download)
 *  • MIME type resolution          (static file serving)
 *  • JSON API responses            (GET /files)
 */
public class FileHandler implements Runnable {

    private static final Path UPLOAD_DIR = Path.of("webserver", "uploads");
    private static final Path STATIC_DIR = Path.of("webserver", "static");

    private final Socket   socket;
    private final EventBus eventBus;

    public FileHandler(Socket socket, EventBus eventBus) {
        this.socket   = socket;
        this.eventBus = eventBus;
    }

    @Override
    public void run() {
        try (socket;
             InputStream  rawIn  = socket.getInputStream();
             OutputStream rawOut = socket.getOutputStream()) {

            // ── Read request line + headers ──────────────────
            ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
            int prev = -1, curr;
            int crlfCount = 0;
            while ((curr = rawIn.read()) != -1) {
                headerBuf.write(curr);
                if (curr == '\n' && prev == '\r') crlfCount++;
                else if (curr != '\r') crlfCount = 0;
                if (crlfCount == 2) break;  // \r\n\r\n
                prev = curr;
            }

            String headerBlock = headerBuf.toString(StandardCharsets.UTF_8);
            if (headerBlock.isBlank()) return;

            String[] headerLines = headerBlock.split("\r\n");
            String requestLine   = headerLines[0];
            String[] parts       = requestLine.split(" ");
            if (parts.length < 2) return;

            String method = parts[0].toUpperCase();
            String path   = parts[1];

            // Parse headers into key-value
            int contentLength = 0;
            String contentType = "";
            for (int i = 1; i < headerLines.length; i++) {
                String line = headerLines[i];
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                } else if (line.toLowerCase().startsWith("content-type:")) {
                    contentType = line.substring(13).trim();
                }
            }

            // ── Route requests ───────────────────────────────
            if (method.equals("POST") && path.equals("/upload")) {
                handleUpload(rawIn, rawOut, contentLength, contentType);
            } else if (method.equals("GET") && path.startsWith("/download/")) {
                handleDownload(rawOut, path);
            } else if (method.equals("GET") && path.equals("/files")) {
                handleListFiles(rawOut);
            } else if (method.equals("GET")) {
                handleStaticFile(rawOut, path);
            } else {
                sendResponse(rawOut, 405, "text/plain", "Method Not Allowed");
            }

        } catch (IOException e) {
            System.err.println("[FileHandler] Error: " + e.getMessage());
        }
    }

    // ── POST /upload ─────────────────────────────────────────
    private void handleUpload(InputStream in, OutputStream out,
                              int contentLength, String contentType) throws IOException {

        if (!contentType.contains("multipart/form-data")) {
            sendResponse(out, 400, "text/plain", "Expected multipart/form-data");
            return;
        }

        // Extract boundary from Content-Type header
        String boundary = null;
        for (String param : contentType.split(";")) {
            param = param.trim();
            if (param.startsWith("boundary=")) {
                boundary = param.substring(9).trim();
                // Remove surrounding quotes if present
                if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                break;
            }
        }
        if (boundary == null) {
            sendResponse(out, 400, "text/plain", "Missing boundary in Content-Type");
            return;
        }

        // Read entire body
        byte[] body = readExactly(in, contentLength);
        List<MultipartParser.Part> parts = MultipartParser.parse(body, boundary);

        String uploader = "Anonymous";
        MultipartParser.Part filePart = null;

        for (MultipartParser.Part part : parts) {
            if ("uploader".equals(part.fieldName)) {
                uploader = part.textValue();
            } else if (part.isFile()) {
                filePart = part;
            }
        }

        if (filePart == null) {
            sendResponse(out, 400, "text/plain", "No file provided");
            return;
        }

        // Ensure upload directory exists
        Files.createDirectories(UPLOAD_DIR);

        // Sanitize filename and save
        String safeFileName = sanitizeFileName(filePart.fileName);
        Path target = UPLOAD_DIR.resolve(safeFileName);

        // Handle duplicate filenames
        int counter = 1;
        String baseName = safeFileName.contains(".")
                ? safeFileName.substring(0, safeFileName.lastIndexOf('.'))
                : safeFileName;
        String extension = safeFileName.contains(".")
                ? safeFileName.substring(safeFileName.lastIndexOf('.'))
                : "";
        while (Files.exists(target)) {
            safeFileName = baseName + "_" + counter + extension;
            target = UPLOAD_DIR.resolve(safeFileName);
            counter++;
        }

        Files.write(target, filePart.data);

        // Build metadata and publish to EventBus
        String mime = filePart.contentType != null ? filePart.contentType : guessMimeType(safeFileName);
        FileMetadata meta = new FileMetadata(safeFileName, filePart.data.length, mime, uploader);
        eventBus.publish("file-upload", meta.toJson());

        System.out.println("[FileHandler] Uploaded: " + meta);

        // Respond with JSON
        sendResponse(out, 200, "application/json",
                "{\"status\":\"ok\",\"file\":" + meta.toJson() + "}");
    }

    // ── GET /download/<filename> ─────────────────────────────
    private void handleDownload(OutputStream out, String path) throws IOException {
        String encodedName = path.substring("/download/".length());
        String fileName = URLDecoder.decode(encodedName, StandardCharsets.UTF_8);
        fileName = sanitizeFileName(fileName);

        Path file = UPLOAD_DIR.resolve(fileName);
        if (!Files.exists(file) || Files.isDirectory(file)) {
            sendResponse(out, 404, "text/plain", "File not found");
            return;
        }

        byte[] fileBytes = Files.readAllBytes(file);
        String mime = guessMimeType(fileName);

        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + mime + "\r\n"
                + "Content-Length: " + fileBytes.length + "\r\n"
                + "Content-Disposition: attachment; filename=\"" + fileName + "\"\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(fileBytes);
        out.flush();
    }

    // ── GET /files — JSON list of uploaded files ─────────────
    private void handleListFiles(OutputStream out) throws IOException {
        Files.createDirectories(UPLOAD_DIR);

        StringBuilder json = new StringBuilder("[");
        boolean first = true;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(UPLOAD_DIR)) {
            for (Path file : stream) {
                if (Files.isDirectory(file)) continue;
                if (!first) json.append(",");
                first = false;

                String name = file.getFileName().toString();
                long size   = Files.size(file);
                String mime = guessMimeType(name);
                long mod    = Files.getLastModifiedTime(file).toMillis();

                FileMetadata meta = new FileMetadata(name, size, mime, "Unknown");
                json.append(meta.toJson());
            }
        }
        json.append("]");

        sendResponse(out, 200, "application/json", json.toString());
    }

    // ── Static file serving (index.html, styles, JS) ────────
    private void handleStaticFile(OutputStream out, String path) throws IOException {
        if (path.equals("/")) path = "/index.html";

        // Security: prevent directory traversal
        String cleaned = path.replace("..", "").replace("\\", "/");
        if (cleaned.startsWith("/")) cleaned = cleaned.substring(1);

        Path file = STATIC_DIR.resolve(cleaned);
        if (!Files.exists(file) || Files.isDirectory(file)) {
            sendResponse(out, 404, "text/html",
                    "<html><body><h1>404 — Not Found</h1></body></html>");
            return;
        }

        byte[] fileBytes = Files.readAllBytes(file);
        String mime = guessMimeType(file.getFileName().toString());

        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + mime + "\r\n"
                + "Content-Length: " + fileBytes.length + "\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(fileBytes);
        out.flush();
    }

    // ── Utility methods ──────────────────────────────────────

    private void sendResponse(OutputStream out, int status, String contentType, String body)
            throws IOException {
        String statusText = switch (status) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 500 -> "Internal Server Error";
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

    private byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] buf = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(buf, offset, length - offset);
            if (read == -1) break;
            offset += read;
        }
        return buf;
    }

    private String sanitizeFileName(String name) {
        if (name == null) return "unknown";
        // Strip path separators and dangerous characters
        return name.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
    }

    static String guessMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".css"))   return "text/css";
        if (lower.endsWith(".js"))    return "application/javascript";
        if (lower.endsWith(".json"))  return "application/json";
        if (lower.endsWith(".png"))   return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif"))   return "image/gif";
        if (lower.endsWith(".svg"))   return "image/svg+xml";
        if (lower.endsWith(".ico"))   return "image/x-icon";
        if (lower.endsWith(".webp"))  return "image/webp";
        if (lower.endsWith(".pdf"))   return "application/pdf";
        if (lower.endsWith(".zip"))   return "application/zip";
        if (lower.endsWith(".mp4"))   return "video/mp4";
        if (lower.endsWith(".mp3"))   return "audio/mpeg";
        if (lower.endsWith(".woff"))  return "font/woff";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".ttf"))   return "font/ttf";
        if (lower.endsWith(".txt"))   return "text/plain";
        if (lower.endsWith(".xml"))   return "application/xml";
        if (lower.endsWith(".csv"))   return "text/csv";
        return "application/octet-stream";
    }
}
