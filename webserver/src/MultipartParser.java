package webserver.src;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * MultipartParser — binary multipart/form-data parser.
 *
 * Networking concepts:
 *  • Boundary-based binary splitting — each part delimited by "--boundary"
 *  • Byte-level protocol parsing — headers separated from body by \r\n\r\n
 *  • Content-Disposition header parsing — extracts field name + filename
 *
 * RFC 2046 §5.1 : multipart bodies are delimited by a boundary string.
 */
public class MultipartParser {

    // ── Parsed part ──────────────────────────────────────────
    public static class Part {
        public String fieldName;
        public String fileName;   // null for non-file fields
        public String contentType;
        public byte[] data;

        public boolean isFile() { return fileName != null && !fileName.isEmpty(); }

        public String textValue() {
            return data == null ? "" : new String(data, StandardCharsets.UTF_8).trim();
        }
    }

    // ── Public API ───────────────────────────────────────────
    public static List<Part> parse(byte[] body, String boundary) throws IOException {
        List<Part> parts = new ArrayList<>();
        byte[] delimiterBytes = ("--" + boundary).getBytes(StandardCharsets.UTF_8);

        // Split body by delimiter
        List<byte[]> sections = splitByDelimiter(body, delimiterBytes);

        for (byte[] section : sections) {
            if (section.length < 4) continue;  // skip tiny/empty chunks

            // Check for closing delimiter "--boundary--"
            String start = new String(section, 0, Math.min(section.length, 4), StandardCharsets.UTF_8);
            if (start.startsWith("--")) continue;

            Part part = parsePart(section);
            if (part != null) parts.add(part);
        }
        return parts;
    }

    // ── Split bytes by delimiter ─────────────────────────────
    private static List<byte[]> splitByDelimiter(byte[] data, byte[] delimiter) {
        List<byte[]> result = new ArrayList<>();
        int start = 0;

        while (start < data.length) {
            int idx = indexOf(data, delimiter, start);
            if (idx == -1) {
                // Remaining data after last delimiter
                if (start < data.length) {
                    result.add(Arrays.copyOfRange(data, start, data.length));
                }
                break;
            }
            if (idx > start) {
                result.add(Arrays.copyOfRange(data, start, idx));
            }
            start = idx + delimiter.length;
        }
        return result;
    }

    // ── Find byte sequence in array ──────────────────────────
    private static int indexOf(byte[] haystack, byte[] needle, int fromIndex) {
        outer:
        for (int i = fromIndex; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    // ── Parse a single part (headers + body) ─────────────────
    private static Part parsePart(byte[] section) {
        // Skip leading \r\n
        int offset = 0;
        while (offset < section.length && (section[offset] == '\r' || section[offset] == '\n')) {
            offset++;
        }
        if (offset >= section.length) return null;

        // Find header/body separator: \r\n\r\n
        byte[] separator = "\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        int sepIndex = indexOf(section, separator, offset);
        if (sepIndex == -1) return null;

        String headers = new String(section, offset, sepIndex - offset, StandardCharsets.UTF_8);
        int bodyStart = sepIndex + separator.length;

        // Trim trailing \r\n from body
        int bodyEnd = section.length;
        while (bodyEnd > bodyStart && (section[bodyEnd - 1] == '\r' || section[bodyEnd - 1] == '\n')) {
            bodyEnd--;
        }

        Part part = new Part();
        part.data = Arrays.copyOfRange(section, bodyStart, bodyEnd);

        // Parse Content-Disposition
        for (String line : headers.split("\r\n")) {
            String lower = line.toLowerCase();
            if (lower.startsWith("content-disposition:")) {
                part.fieldName = extractParam(line, "name");
                part.fileName  = extractParam(line, "filename");
            } else if (lower.startsWith("content-type:")) {
                part.contentType = line.substring(line.indexOf(':') + 1).trim();
            }
        }
        return part;
    }

    // ── Extract a parameter value from a header line ─────────
    private static String extractParam(String header, String param) {
        String search = param + "=\"";
        int idx = header.indexOf(search);
        if (idx == -1) return null;
        int start = idx + search.length();
        int end   = header.indexOf('"', start);
        if (end == -1) return null;
        return header.substring(start, end);
    }
}
