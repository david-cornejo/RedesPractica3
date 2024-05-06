package util;
import java.util.HashMap;
import java.util.Map;

public class MimeTypes {
    private static final Map<String, String> mimeMap = new HashMap<>();

    static {
        mimeMap.put("html", "text/html");       // MIME type para archivos HTML
        mimeMap.put("txt", "text/plain");       // MIME type para archivos de texto
        mimeMap.put("jpg", "image/jpeg");       // MIME type para imágenes JPEG
        mimeMap.put("json", "application/json");// MIME type para archivos JSON
    }

    public static String getMimeType(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < fileName.length() - 1) {
            String ext = fileName.substring(dotIndex + 1).toLowerCase();
            return mimeMap.getOrDefault(ext, "application/octet-stream");
        }
        return "application/octet-stream";  // Tipo MIME por defecto si no hay extensión
    }
}
