package webserver.src;

/**
 * FileMetadata — POJO representing uploaded file information.
 * Contains file name, size, MIME type, upload timestamp, and uploader info.
 * Provides toJson() for SSE event serialization.
 */
public class FileMetadata {

    private final String fileName;
    private final long   fileSize;
    private final String mimeType;
    private final long   uploadTimestamp;
    private final String uploader;

    public FileMetadata(String fileName, long fileSize, String mimeType, String uploader) {
        this.fileName        = fileName;
        this.fileSize        = fileSize;
        this.mimeType        = mimeType;
        this.uploadTimestamp  = System.currentTimeMillis();
        this.uploader        = (uploader == null || uploader.isBlank()) ? "Anonymous" : uploader;
    }

    // ── Getters ──────────────────────────────────────────────
    public String getFileName()       { return fileName; }
    public long   getFileSize()       { return fileSize; }
    public String getMimeType()       { return mimeType; }
    public long   getUploadTimestamp(){ return uploadTimestamp; }
    public String getUploader()       { return uploader; }

    // ── Human-readable size ──────────────────────────────────
    public String getReadableSize() {
        if (fileSize < 1024)                return fileSize + " B";
        if (fileSize < 1024 * 1024)         return String.format("%.1f KB", fileSize / 1024.0);
        if (fileSize < 1024 * 1024 * 1024)  return String.format("%.1f MB", fileSize / (1024.0 * 1024));
        return String.format("%.2f GB", fileSize / (1024.0 * 1024 * 1024));
    }

    // ── JSON serialisation (no external libs) ────────────────
    public String toJson() {
        return "{"
             + "\"fileName\":\""   + escapeJson(fileName)   + "\","
             + "\"fileSize\":"     + fileSize                + ","
             + "\"readableSize\":\"" + escapeJson(getReadableSize()) + "\","
             + "\"mimeType\":\""   + escapeJson(mimeType)   + "\","
             + "\"timestamp\":"    + uploadTimestamp          + ","
             + "\"uploader\":\""   + escapeJson(uploader)   + "\""
             + "}";
    }

    @Override
    public String toString() {
        return "FileMetadata{" + fileName + ", " + getReadableSize() + ", " + mimeType + "}";
    }

    // ── Helpers ──────────────────────────────────────────────
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
