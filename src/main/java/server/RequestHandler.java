package server;
import util.MimeTypes;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.commons.fileupload.MultipartStream;


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

            // Leer y parsear la línea inicial de la solicitud HTTP
            BufferedReader headerReader = new BufferedReader(new InputStreamReader(inputStream));
            String requestLine = headerReader.readLine();
            if (requestLine == null) return;

            String[] requestTokens = requestLine.split(" ");
            String method = requestTokens[0];
            String resource = requestTokens[1];

            // Imprimir en consola el método y el recurso solicitado
            System.out.println("Request: " + method + " " + resource);

            // Obtener el Content-Type
            String contentType = getContentType(headerReader);

            switch (method) {
                case "GET":
                    handleGet(resource, out);
                    break;
                case "POST":
                    handlePost(inputStream, out, contentType);
                    break;
                case "PUT":
                    handlePut(inputStream, resource, out);
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

    private void handlePost(InputStream in, OutputStream out, String contentType) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        if (contentType.startsWith("multipart/form-data")) {
            handleMultipartData(in, out, contentType);
        } else {
            StringBuilder payload = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                payload.append(line + "\n");
            }
            createFileFromPayload(payload.toString(), out);
        }
    }

    private void handlePut(InputStream in, String resource, OutputStream out) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        Path filePath = getFilePath(resource);

        StringBuilder payload = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            payload.append(line + "\n");
        }

        Files.write(filePath, payload.toString().getBytes());

        String response = "Data received and written in PUT request to " + resource;
        sendHeader(200, "OK", "text/plain", response.length(), out);
        out.write(response.getBytes());
        out.flush();
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

    private void createFileFromPayload(String data, OutputStream out) throws IOException {
        String fileName = "received_" + System.currentTimeMillis() + ".txt";
        Path path = Paths.get(fileName);
        try {
            Files.write(path, data.getBytes());
            sendHeader(201, "Created", "text/plain", 0, out);  // No body content
            out.flush();
        } catch (IOException e) {
            sendError(500, "Internal Server Error", out, true);
        }
    }

    private void handleMultipartData(InputStream in, OutputStream out, String contentType) throws IOException {
        String boundary = contentType.split("boundary=")[1];
        MultipartStream multipartStream = new MultipartStream(in, boundary.getBytes());

        boolean nextPart = multipartStream.skipPreamble();
        while (nextPart) {
            String headers = multipartStream.readHeaders();
            // Aquí deberías parsear los headers para obtener información como el nombre del archivo, etc.

            if (headers.contains("filename")) {
                // Abre un OutputStream para escribir el contenido del archivo si es parte de archivo
                File outputFile = new File("destinationPath", "filename_here");
                try (FileOutputStream outputFileStream = new FileOutputStream(outputFile)) {
                    multipartStream.readBodyData(outputFileStream);
                }
            } else {
                // Manejar otros datos del formulario
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                multipartStream.readBodyData(outputStream);
                String formData = outputStream.toString(StandardCharsets.UTF_8.name());
                // Procesar formData
            }
            nextPart = multipartStream.readBoundary();
        }
        sendHeader(201, "Created", "text/plain", 0, out);  // No body content
        out.flush();
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