package sh.harold.fulcrum.distribution.launcher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

final class RuntimeProbeServer implements AutoCloseable {
    private final HttpServer server;
    private final Supplier<List<RuntimeServiceSnapshot>> snapshots;

    RuntimeProbeServer(String host, int port, Supplier<List<RuntimeServiceSnapshot>> snapshots) throws IOException {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/live", this::handleLive);
        server.createContext("/ready", this::handleReady);
        server.createContext("/identity", this::handleIdentity);
    }

    void start() {
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handleLive(HttpExchange exchange) throws IOException {
        List<RuntimeServiceSnapshot> current = snapshots.get();
        boolean live = current.stream().allMatch(RuntimeServiceSnapshot::live);
        respond(exchange, live ? 200 : 503, statusJson("live", live, current));
    }

    private void handleReady(HttpExchange exchange) throws IOException {
        List<RuntimeServiceSnapshot> current = snapshots.get();
        boolean ready = current.stream().allMatch(RuntimeServiceSnapshot::ready);
        respond(exchange, ready ? 200 : 503, statusJson("ready", ready, current));
    }

    private void handleIdentity(HttpExchange exchange) throws IOException {
        respond(exchange, 200, statusJson("identity", true, snapshots.get()));
    }

    private static String statusJson(String statusName, boolean status, List<RuntimeServiceSnapshot> snapshots) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\"").append(statusName).append("\":").append(status).append(",\"services\":[");
        for (int index = 0; index < snapshots.size(); index++) {
            RuntimeServiceSnapshot snapshot = snapshots.get(index);
            if (index > 0) {
                builder.append(',');
            }
            builder.append('{')
                    .append("\"role\":\"").append(escape(snapshot.role())).append("\",")
                    .append("\"processFamily\":\"").append(escape(snapshot.processFamily())).append("\",")
                    .append("\"instanceId\":\"").append(escape(snapshot.instanceId())).append("\",")
                    .append("\"instanceKind\":\"").append(escape(snapshot.instanceKind())).append("\",")
                    .append("\"principalId\":\"").append(escape(snapshot.principalId())).append("\",")
                    .append("\"credentialRef\":\"").append(escape(snapshot.credentialRef())).append("\",")
                    .append("\"live\":").append(snapshot.live()).append(',')
                    .append("\"ready\":").append(snapshot.ready()).append(',')
                    .append("\"loopCount\":").append(snapshot.loopCount())
                    .append('}');
        }
        builder.append("]}");
        return builder.toString();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var response = exchange.getResponseBody()) {
            response.write(bytes);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
