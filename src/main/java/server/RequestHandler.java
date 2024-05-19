package server;
import util.MimeTypes;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class RequestHandler implements Runnable {
    private final Socket clientSocket;

    public RequestHandler(Socket clientSocket) {
        this.clientSocket = clientSocket;
    }

    @Override
    public void run() {
        try {
            DataInputStream inputStream = new DataInputStream(clientSocket.getInputStream());
            byte[] bytes = new byte[50000];
            int t = inputStream.read(bytes);
            String request = new String(bytes, 0, t);

            OutputStream out = clientSocket.getOutputStream();

            System.out.println(request);

            // Parse the request line
            BufferedReader headerReader = new BufferedReader(new StringReader(request));
            String requestLine = headerReader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                sendError(400, "Bad Request: Invalid Request Line", out, true);
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length != 3) {
                sendError(400, "Bad Request: Invalid Request Line", out, true);
                return;
            }

            String method = parts[0];
            String resource = parts[1];
            int contentLength = getContentLength(headerReader);

            switch (method) {
                case "GET":
                    handleGet(resource, out);
                    break;
                case "POST":
                    handlePost(headerReader, inputStream, out, contentLength);
                    break;
                case "PUT":
                    handlePut(resource, inputStream, out, contentLength, bytes, t, request);
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

    private void handlePost(BufferedReader headerReader, InputStream inputStream, OutputStream out, int contentLength) throws IOException {
        // Leer el cuerpo de la solicitud después de las cabeceras
        StringBuilder bodyBuilder = new StringBuilder();
        char[] buffer = new char[contentLength];
        int bytesRead = headerReader.read(buffer, 0, contentLength);
        bodyBuilder.append(buffer, 0, bytesRead);

        // Convertir el cuerpo de la solicitud a un String y eliminar espacios en blanco adicionales
        String fileName = bodyBuilder.toString().trim();
        System.out.println("File requested: " + fileName);

        // Obtener la ruta del archivo solicitado
        Path filePath = getFilePath("/" + fileName);
        if (Files.exists(filePath)) {
            String mimeType = MimeTypes.getMimeType(filePath.toString());
            sendHeader(200, "OK", mimeType, Files.size(filePath), out);
            Files.copy(filePath, out);
            out.flush();
        } else {
            sendError(404, "Not Found", out, true);
        }
    }

    private void handlePut(String resource, InputStream inputStream, OutputStream out, int contentLength, byte[] fullRequest, int requestLength, String request) throws IOException {
        // Leer el cuerpo de la solicitud después de las cabeceras
        int headerEndIndex = request.indexOf("\r\n\r\n") + 4;
        if (headerEndIndex < 4) {
            sendError(400, "Bad Request: Invalid Request Body", out, true);
            return;
        }

        int contentStartIndex = headerEndIndex;
        byte[] content = new byte[contentLength];
        System.arraycopy(fullRequest, contentStartIndex, content, 0, contentLength);

        String fileName = resource.substring(1);  // Quitar el primer slash '/'
        Path filePath = getFilePath("/" + fileName);
        Files.createDirectories(filePath.getParent());

        try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
            fos.write(content);
            sendHeader(201, "Created", "text/plain", 0, out);
        } catch (IOException e) {
            e.printStackTrace();
            sendError(500, "Internal Server Error: " + e.getMessage(), out, true);
        } finally {
            inputStream.close();
            out.close();
        }
    }

    private int getContentLength(BufferedReader headerReader) throws IOException {
        String line;
        int contentLength = -1;

        while ((line = headerReader.readLine()) != null) {
            if (line.isEmpty()) {
                break;
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
        return Paths.get("C:/Users/david/P3 Redes/Practica3/", resource).toAbsolutePath();
    }

    private void sendHeader(int statusCode, String statusText, String contentType, long contentLength, OutputStream out) throws IOException {
        PrintWriter writer = new PrintWriter(out, true);
        writer.println("HTTP/1.1 " + statusCode + " " + statusText);
        writer.println("Content-Type: " + contentType);
        writer.println("Content-Length: " + contentLength);
        writer.println();
        writer.flush();
    }

    private void sendError(int statusCode, String message, OutputStream out, boolean includeBody) throws IOException {
        String body = includeBody ? "<html><body><h1>" + message + "</h1></body></html>" : "";

        PrintWriter writer = new PrintWriter(out, true);
        writer.print("HTTP/1.1 " + statusCode + " " + message + "\r\n");
        writer.print("Content-Type: text/html\r\n");
        writer.print("Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n");
        writer.print("\r\n");

        if (includeBody) {
            writer.print(body);
        }
        writer.flush();
    }
}
