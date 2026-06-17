package sh.harold.fulcrum.distribution.launcher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import sh.harold.fulcrum.host.api.HostObservation;
import sh.harold.fulcrum.host.api.HostObservationWireCodec;
import sh.harold.fulcrum.host.paper.PaperObservationSink;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class PaperObservationBridgeServer implements AutoCloseable {
    private final URI configuredUrl;
    private final PaperObservationSink observationSink;
    private final ExecutorService publisher;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private HttpServer server;

    PaperObservationBridgeServer(URI configuredUrl, PaperObservationSink observationSink) {
        this.configuredUrl = Objects.requireNonNull(configuredUrl, "configuredUrl");
        this.observationSink = Objects.requireNonNull(observationSink, "observationSink");
        this.publisher = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "fulcrum-paper-observation-publisher");
            thread.setDaemon(true);
            return thread;
        });
        if (configuredUrl.getHost() == null || configuredUrl.getHost().isBlank()) {
            throw new IllegalArgumentException("configuredUrl must include a host");
        }
        if (configuredUrl.getPort() < 0) {
            throw new IllegalArgumentException("configuredUrl must include a port");
        }
        if (path().equals("/")) {
            throw new IllegalArgumentException("configuredUrl must include an observation path");
        }
    }

    void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Paper observation bridge is already running");
        }
        try {
            server = HttpServer.create(new InetSocketAddress(configuredUrl.getHost(), configuredUrl.getPort()), 0);
        } catch (IOException exception) {
            started.set(false);
            publisher.shutdownNow();
            throw new IllegalStateException("Could not bind Paper observation bridge " + configuredUrl, exception);
        }
        server.createContext(path(), this::handle);
        server.start();
    }

    URI uri() {
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

    Throwable failure() {
        return failure.get();
    }

    @Override
    public void close() {
        HttpServer current = server;
        if (current != null) {
            current.stop(0);
        }
        publisher.shutdown();
        try {
            if (!publisher.awaitTermination(5, TimeUnit.SECONDS)) {
                publisher.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            publisher.shutdownNow();
        }
        started.set(false);
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "method not allowed\n");
            return;
        }
        String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        HostObservation observation;
        try {
            observation = HostObservationWireCodec.decode(payload);
        } catch (RuntimeException exception) {
            respond(exchange, 400, exception.getMessage() + "\n");
            return;
        }
        try {
            publisher.execute(() -> publish(observation));
        } catch (RejectedExecutionException exception) {
            respond(exchange, 503, "observation bridge is stopping\n");
            return;
        }
        respond(exchange, 202, "accepted\n");
    }

    private void publish(HostObservation observation) {
        try {
            observationSink.publish(observation);
        } catch (RuntimeException exception) {
            failure.compareAndSet(null, exception);
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
