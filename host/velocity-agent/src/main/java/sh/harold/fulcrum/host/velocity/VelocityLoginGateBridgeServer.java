package sh.harold.fulcrum.host.velocity;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VelocityLoginGateBridgeServer implements AutoCloseable {
    private final URI configuredUrl;
    private final VelocityLoginGateEvaluator evaluator;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private HttpServer server;

    public VelocityLoginGateBridgeServer(URI configuredUrl, VelocityLoginGateEvaluator evaluator) {
        this.configuredUrl = Objects.requireNonNull(configuredUrl, "configuredUrl");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        if (configuredUrl.getHost() == null || configuredUrl.getHost().isBlank()) {
            throw new IllegalArgumentException("configuredUrl must include a host");
        }
        if (configuredUrl.getPort() < 0) {
            throw new IllegalArgumentException("configuredUrl must include a port");
        }
        if (path().equals("/")) {
            throw new IllegalArgumentException("configuredUrl must include a login gate bridge path");
        }
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Velocity login gate bridge is already running");
        }
        try {
            server = HttpServer.create(new InetSocketAddress(configuredUrl.getHost(), configuredUrl.getPort()), 0);
        } catch (IOException exception) {
            started.set(false);
            throw new IllegalStateException("Could not bind Velocity login gate bridge " + configuredUrl, exception);
        }
        server.createContext(path(), this::handle);
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

    private void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "method not allowed\n");
            return;
        }
        VelocityLoginGateRequest request;
        try {
            String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            request = VelocityLoginGateBridgeCodec.decodeRequest(payload);
        } catch (RuntimeException exception) {
            respond(exchange, 400, exception.getMessage() + "\n");
            return;
        }
        try {
            respond(exchange, 200, VelocityLoginGateBridgeCodec.encodeDecision(evaluator.evaluate(request)));
        } catch (SecurityException exception) {
            respond(exchange, 403, exception.getMessage() + "\n");
        } catch (RuntimeException exception) {
            respond(exchange, 500, exception.getMessage() + "\n");
        }
    }

    private String path() {
        String path = configuredUrl.getPath();
        return path == null || path.isBlank() ? "/" : path;
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
