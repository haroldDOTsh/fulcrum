package sh.harold.fulcrum.host.paper;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PaperCapabilityBridgeServer implements AutoCloseable {
    private final URI configuredUrl;
    private final PaperCapabilityBridge capabilityBridge;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private HttpServer server;

    public PaperCapabilityBridgeServer(URI configuredUrl, PaperCapabilityBridge capabilityBridge) {
        this.configuredUrl = Objects.requireNonNull(configuredUrl, "configuredUrl");
        this.capabilityBridge = Objects.requireNonNull(capabilityBridge, "capabilityBridge");
        if (configuredUrl.getHost() == null || configuredUrl.getHost().isBlank()) {
            throw new IllegalArgumentException("configuredUrl must include a host");
        }
        if (configuredUrl.getPort() < 0) {
            throw new IllegalArgumentException("configuredUrl must include a port");
        }
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Paper capability bridge is already running");
        }
        try {
            server = HttpServer.create(new InetSocketAddress(configuredUrl.getHost(), configuredUrl.getPort()), 0);
        } catch (IOException exception) {
            started.set(false);
            throw new IllegalStateException("Could not bind Paper capability bridge " + configuredUrl, exception);
        }
        server.createContext(PaperCapabilityBridgeClient.endpoint(configuredUrl, "subject-view").getPath(), this::handleSubjectView);
        server.createContext(PaperCapabilityBridgeClient.endpoint(configuredUrl, "chat-decoration").getPath(), this::handleChatDecoration);
        server.start();
    }

    public URI uri() {
        HttpServer current = server;
        if (current == null) {
            return configuredUrl;
        }
        return URI.create(configuredUrl.getScheme()
                + "://"
                + configuredUrl.getHost()
                + ":"
                + current.getAddress().getPort()
                + path());
    }

    @Override
    public void close() {
        HttpServer current = server;
        if (current != null) {
            current.stop(0);
        }
        started.set(false);
    }

    private void handleSubjectView(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "method not allowed\n");
            return;
        }
        try {
            String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            PaperSubjectCapabilityView view = capabilityBridge.subjectView(
                    PaperCapabilityBridgeCodec.decodeSubjectRequest(payload));
            respond(exchange, 200, PaperCapabilityBridgeCodec.encodeSubjectView(view));
        } catch (SecurityException exception) {
            respond(exchange, 403, exception.getMessage() + "\n");
        } catch (RuntimeException exception) {
            respond(exchange, 400, exception.getMessage() + "\n");
        }
    }

    private void handleChatDecoration(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "method not allowed\n");
            return;
        }
        try {
            String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            PaperChatDecorationResponse response = capabilityBridge.decorateChat(
                    PaperCapabilityBridgeCodec.decodeChatRequest(payload));
            respond(exchange, 200, PaperCapabilityBridgeCodec.encodeChatResponse(response));
        } catch (SecurityException exception) {
            respond(exchange, 403, exception.getMessage() + "\n");
        } catch (RuntimeException exception) {
            respond(exchange, 400, exception.getMessage() + "\n");
        }
    }

    private String path() {
        String path = configuredUrl.getPath();
        return path == null || path.isBlank() ? "" : path;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var response = exchange.getResponseBody()) {
            response.write(bytes);
        }
    }
}
