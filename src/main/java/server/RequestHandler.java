package server;
import org.apache.commons.fileupload.MultipartStream;
import org.json.JSONException;
import org.json.JSONObject;
import util.MimeTypes;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;


public class RequestHandler implements Runnable {
    private final Socket clientSocket;

    public RequestHandler(Socket clientSocket) {
        this.clientSocket = clientSocket;
    }

    @Override
    public void run() {
        try {
            InputStream inputStream = clientSocket.getInputStream();
            OutputStream out = clientSocket.getOutputStream();

            // Usar BufferedReader para leer las cabeceras
            BufferedReader headerReader = new BufferedReader(new InputStreamReader(inputStream));
            String requestLine = headerReader.readLine();
            if (requestLine == null) return;

            String[] requestTokens = requestLine.split(" ");
            String method = requestTokens[0];
            String resource = requestTokens[1];

            // Imprimir en consola el método y el recurso solicitado
            System.out.println("Request: " + method + " " + resource);

            // Leer Content-Length de las cabeceras
            int contentLength = getContentLength(headerReader);

            switch (method) {
                case "GET":
                    handleGet(resource, out);
                    break;
                case "POST":
                    handlePost(headerReader, inputStream, out);
                    break;
                case "PUT":
                    handlePut(headerReader, inputStream, resource, out);
                    break;
                case "HEAD":
                    handleHead(resource, out);
                    break;
                default:
                    sendError(405, "Method Not Allowed", out, false);
                    break;
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void handleGet(String resource, OutputStream out) throws IOException {
        Path filePath = getFilePath(resource);
        if (Files.exists(filePath)) {
            String mimeType = MimeTypes.getMimeType(filePath.toString());
            sendHeader(200, "OK", mimeType, Files.size(filePath), out);
            Files.copy(filePath, out);
            out.flush();
        } else {
            sendError(404, "Not Found", out, true);
        }
    }


    private void handleHead(String resource, OutputStream out) throws IOException {
        Path filePath = getFilePath(resource);
        if (Files.exists(filePath)) {
            String mimeType = MimeTypes.getMimeType(filePath.toString());
            long contentLength = Files.size(filePath);
            String lastModified = Files.getLastModifiedTime(filePath).toString();

            PrintWriter writer = new PrintWriter(out, true);
            writer.print("HTTP/1.1 200 OK\r\n");
            writer.print("Content-Type: " + mimeType + "\r\n");
            writer.print("Content-Length: " + contentLength + "\r\n");
            writer.print("Last-Modified: " + lastModified + "\r\n");
            writer.print("\r\n");
            writer.flush();
        } else {
            sendError(404, "Not Found", out, false);
        }
    }

    private void handlePost(BufferedReader headerReader, InputStream inputStream, OutputStream out) throws IOException {
        String contentType = getContentType(headerReader);
        int contentLength = getContentLength(headerReader);

        if (contentType.equals("application/x-www-form-urlencoded")) {
            String body = readBody(headerReader, contentLength);
            String fileName = extractFileNameFromBody(body);
            sendFile(fileName, out);
        } else {
            sendError(415, "Unsupported Media Type", out, false);
        }
    }

    private String readBody(BufferedReader reader, int contentLength) throws IOException {
        char[] body = new char[contentLength];
        reader.read(body, 0, contentLength);
        return new String(body);
    }


    private String extractFileNameFromBody(String body) {
        Map<String, String> params = Arrays.stream(body.split("&"))
                .map(param -> param.split("="))
                .collect(Collectors.toMap(p -> p[0], p -> p.length > 1 ? p[1] : null));
        return params.get("filename");
    }

    private String extractFileNameFromJson(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            return obj.optString("filename", null);
        } catch (JSONException e) {
            e.printStackTrace();
            return null;
        }
    }

    private void sendFile(String fileName, OutputStream out) throws IOException {
        if (fileName == null || fileName.trim().isEmpty()) {
            sendError(400, "Bad Request: Filename is required", out, false);
            return;
        }
        Path filePath = Paths.get(fileName).toAbsolutePath(); // Asegúrate de que el path es seguro y válido
        if (Files.exists(filePath)) {
            String mimeType = MimeTypes.getMimeType(filePath.toString());
            sendHeader(200, "OK", mimeType, Files.size(filePath), out);
            Files.copy(filePath, out);
            out.flush();
        } else {
            sendError(404, "Not Found", out, true);
        }
    }


    private void handlePut(BufferedReader headerReader, InputStream in, String resource, OutputStream out) throws IOException {
        // Obtén la ruta del archivo
        Path filePath = getFilePath(resource);
        System.out.println("Handling PUT for: " + filePath);

        // Leer las cabeceras y encontrar la longitud del contenido
        int contentLength = getContentLength(headerReader);
        System.out.println("Content-Length: " + contentLength);

        // Crea el archivo si no existe
        if (!Files.exists(filePath)) {
            Files.createFile(filePath);
            System.out.println("File created: " + filePath);
        }

        // Escribir los datos del InputStream al archivo
        try (OutputStream fileOut = new FileOutputStream(filePath.toFile())) {
            byte[] buffer = new byte[1024];
            int totalBytesRead = 0;
            int bytesRead;

            while (totalBytesRead < contentLength && (bytesRead = in.read(buffer)) != -1) {
                fileOut.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;
            }
            System.out.println("Total bytes read: " + totalBytesRead);
        }

        // Comprobar si se escribieron datos
        if (Files.size(filePath) > 0) {
            System.out.println("File write successful: " + filePath);
            sendHeader(200, "OK", "text/plain", Files.size(filePath), out);
        } else {
            System.out.println("File write failed: " + filePath);
            sendError(500, "Internal Server Error: Failed to write file", out, false);
        }
    }

    private int getContentLength(BufferedReader headerReader) throws IOException {
        String line;
        int contentLength = -1; // Inicializar a -1 para detectar si realmente se encontró la cabecera

        while ((line = headerReader.readLine()) != null) {
            System.out.println("Header: " + line); // Log para depuración
            if (line.isEmpty()) {
                break; // Fin de las cabeceras
            }
            if (line.toLowerCase().startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring(15).trim());
            }
        }
        return contentLength;
    }


    private Path getFilePath(String resource) {
        if ("/".equals(resource)) {
            resource = "/index.html";
        }
        Path filePath = Paths.get(".", resource).toAbsolutePath();
        System.out.println("File path: " + filePath);
        return filePath;
    }

    private void sendHeader(int statusCode, String statusText, String contentType, long contentLength, OutputStream out) throws IOException {
        PrintWriter writer = new PrintWriter(out, true);
        writer.println("HTTP/1.1 " + statusCode + " " + statusText);
        writer.println("Content-Type: " + contentType);
        writer.println("Content-Length: " + contentLength);
        writer.println();
        writer.flush();
    }

    private String getContentType(BufferedReader headerReader) throws IOException {
        String line;
        String contentType = "application/octet-stream"; // Default value
        while (!(line = headerReader.readLine()).isEmpty()) {
            if (line.toLowerCase().startsWith("content-type:")) {
                contentType = line.substring(line.indexOf(":") + 1).trim();
                break;
            }
        }
        return contentType;
    }


    private void sendError(int statusCode, String message, OutputStream out, boolean includeBody) throws IOException {
        // Construye el cuerpo de la respuesta si es necesario
        String body = includeBody ? "<html><body><h1>" + message + "</h1></body></html>" : "";

        // Crea el PrintWriter para enviar la respuesta
        PrintWriter writer = new PrintWriter(out, true);

        // Envía las cabeceras HTTP
        writer.print("HTTP/1.1 " + statusCode + " " + message + "\r\n");
        writer.print("Content-Type: text/html\r\n");
        writer.print("Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n");
        writer.print("\r\n");

        // Envía el cuerpo de la respuesta solo si es necesario
        if (includeBody) {
            writer.print(body);
        }
        writer.flush();
    }


}