package sh.harold.fulcrum.distribution.launcher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import sh.harold.fulcrum.host.paper.PaperRewardSink;
import sh.harold.fulcrum.host.paper.PaperSessionRewardReport;
import sh.harold.fulcrum.host.paper.PaperSessionRewardReportCodec;

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

final class PaperRewardBridgeServer implements AutoCloseable {
    private final URI configuredUrl;
    private final PaperRewardSink rewardSink;
    private final int deliveryCopies;
    private final ExecutorService publisher;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private HttpServer server;

    PaperRewardBridgeServer(URI configuredUrl, PaperRewardSink rewardSink) {
        this(configuredUrl, rewardSink, 1);
    }

    PaperRewardBridgeServer(URI configuredUrl, PaperRewardSink rewardSink, int deliveryCopies) {
        this.configuredUrl = Objects.requireNonNull(configuredUrl, "configuredUrl");
        this.rewardSink = Objects.requireNonNull(rewardSink, "rewardSink");
        this.deliveryCopies = deliveryCopies;
        this.publisher = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "fulcrum-paper-reward-publisher");
            thread.setDaemon(true);
            return thread;
        });
        if (deliveryCopies <= 0) {
            throw new IllegalArgumentException("deliveryCopies must be positive");
        }
        if (configuredUrl.getHost() == null || configuredUrl.getHost().isBlank()) {
            throw new IllegalArgumentException("configuredUrl must include a host");
        }
        if (configuredUrl.getPort() < 0) {
            throw new IllegalArgumentException("configuredUrl must include a port");
        }
        if (path().equals("/")) {
            throw new IllegalArgumentException("configuredUrl must include a reward path");
        }
    }

    void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Paper reward bridge is already running");
        }
        try {
            server = HttpServer.create(new InetSocketAddress(configuredUrl.getHost(), configuredUrl.getPort()), 0);
        } catch (IOException exception) {
            started.set(false);
            publisher.shutdownNow();
            throw new IllegalStateException("Could not bind Paper reward bridge " + configuredUrl, exception);
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
        PaperSessionRewardReport report;
        try {
            report = PaperSessionRewardReportCodec.decode(payload);
        } catch (RuntimeException exception) {
            respond(exchange, 400, exception.getMessage() + "\n");
            return;
        }
        try {
            publisher.execute(() -> publish(report));
        } catch (RejectedExecutionException exception) {
            respond(exchange, 503, "reward bridge is stopping\n");
            return;
        }
        respond(exchange, 202, "accepted\n");
    }

    private void publish(PaperSessionRewardReport report) {
        try {
            for (int copy = 0; copy < deliveryCopies; copy++) {
                rewardSink.publish(report);
            }
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
