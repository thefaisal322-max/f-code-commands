package com.fainet.fcode;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A tiny web server for "Open in Browser": it serves the files in the home folder to this phone
 * only (127.0.0.1), so the project can be opened in Chrome like a real website.
 */
final class LocalServer {

    private static final Map<String, String> TYPES = new HashMap<>();

    static {
        TYPES.put("html", "text/html; charset=utf-8");
        TYPES.put("htm", "text/html; charset=utf-8");
        TYPES.put("css", "text/css; charset=utf-8");
        TYPES.put("js", "text/javascript; charset=utf-8");
        TYPES.put("mjs", "text/javascript; charset=utf-8");
        TYPES.put("json", "application/json; charset=utf-8");
        TYPES.put("txt", "text/plain; charset=utf-8");
        TYPES.put("md", "text/plain; charset=utf-8");
        TYPES.put("svg", "image/svg+xml");
        TYPES.put("png", "image/png");
        TYPES.put("jpg", "image/jpeg");
        TYPES.put("jpeg", "image/jpeg");
        TYPES.put("gif", "image/gif");
        TYPES.put("webp", "image/webp");
        TYPES.put("ico", "image/x-icon");
        TYPES.put("woff", "font/woff");
        TYPES.put("woff2", "font/woff2");
        TYPES.put("ttf", "font/ttf");
        TYPES.put("mp3", "audio/mpeg");
        TYPES.put("mp4", "video/mp4");
        TYPES.put("wasm", "application/wasm");
        TYPES.put("pdf", "application/pdf");
    }

    private final File root;
    private ServerSocket server;

    LocalServer(File root) {
        this.root = root;
    }

    /** Starts the server if needed and returns its port. */
    synchronized int start() throws IOException {
        if (server != null && !server.isClosed()) return server.getLocalPort();
        final ServerSocket socket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        server = socket;
        Thread thread = new Thread(() -> {
            while (!socket.isClosed()) {
                try {
                    final Socket client = socket.accept();
                    Thread worker = new Thread(() -> serve(client), "fcode-http-request");
                    worker.setDaemon(true);
                    worker.start();
                } catch (IOException e) {
                    // closed, or one failed connection: the loop condition decides
                }
            }
        }, "fcode-http");
        thread.setDaemon(true);
        thread.start();
        return socket.getLocalPort();
    }

    synchronized void stop() {
        if (server == null) return;
        try {
            server.close();
        } catch (IOException ignored) {
            // already closed
        }
        server = null;
    }

    private void serve(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(10_000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String requestLine = reader.readLine();
            if (requestLine == null) return;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // headers are not needed
            }

            OutputStream out = socket.getOutputStream();
            String[] parts = requestLine.split(" ");
            if (parts.length < 2 || !(parts[0].equals("GET") || parts[0].equals("HEAD"))) {
                respond(out, "405 Method Not Allowed", "text/plain; charset=utf-8", "Only GET is supported".getBytes(StandardCharsets.UTF_8), true);
                return;
            }

            File file = resolve(parts[1]);
            if (file != null && file.isDirectory()) file = new File(file, "index.html");
            if (file == null || !file.isFile()) {
                respond(out, "404 Not Found", "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8), true);
                return;
            }

            String name = file.getName();
            int dot = name.lastIndexOf('.');
            String type = dot < 0 ? null : TYPES.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (type == null) type = "application/octet-stream";

            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: " + type + "\r\n"
                    + "Content-Length: " + file.length() + "\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            if (parts[0].equals("GET")) {
                try (InputStream in = new FileInputStream(file)) {
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
                }
            }
            out.flush();
        } catch (IOException ignored) {
            // the browser went away
        }
    }

    private static void respond(OutputStream out, String status, String type, byte[] body, boolean withBody) throws IOException {
        String head = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        if (withBody) out.write(body);
        out.flush();
    }

    /** Maps a request path to a file under the root, or null when it tries to leave it. */
    private File resolve(String target) {
        int cut = target.indexOf('?');
        if (cut >= 0) target = target.substring(0, cut);
        cut = target.indexOf('#');
        if (cut >= 0) target = target.substring(0, cut);
        String path;
        try {
            path = URLDecoder.decode(target.replace("+", "%2B"), "UTF-8");
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
        Deque<String> parts = new ArrayDeque<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (parts.isEmpty()) return null;
                parts.removeLast();
            } else {
                parts.addLast(part);
            }
        }
        File file = root;
        for (String part : parts) file = new File(file, part);
        return file;
    }
}
