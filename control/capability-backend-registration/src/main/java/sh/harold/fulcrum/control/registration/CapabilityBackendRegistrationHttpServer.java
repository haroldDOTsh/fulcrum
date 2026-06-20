package sh.harold.fulcrum.control.registration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationClient;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationReceipt;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationRequest;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationWireCodec;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class CapabilityBackendRegistrationHttpServer implements AutoCloseable {
    public static final String REGISTRATION_PATH = "/authority-backends/register";

    private final HttpServer server;

    private CapabilityBackendRegistrationHttpServer(HttpServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    public static CapabilityBackendRegistrationHttpServer start(
            InetSocketAddress address,
            AuthorityBackendRegistrationClient registrationClient) {
        Objects.requireNonNull(address, "address");
        AuthorityBackendRegistrationClient checkedClient = Objects.requireNonNull(
                registrationClient,
                "registrationClient");
        try {
            HttpServer server = HttpServer.create(address, 0);
            server.createContext(REGISTRATION_PATH, exchange -> handle(exchange, checkedClient));
            server.start();
            return new CapabilityBackendRegistrationHttpServer(server);
        } catch (IOException exception) {
            throw new IllegalStateException("authority backend registration HTTP server failed to start", exception);
        }
    }

    public URI endpointUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + REGISTRATION_PATH);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static void handle(
            HttpExchange exchange,
            AuthorityBackendRegistrationClient registrationClient) throws IOException {
        try (exchange) {
            if (!REGISTRATION_PATH.equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 404, "unknown authority backend registration route");
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                respond(exchange, 405, "authority backend registration requires POST");
                return;
            }
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                AuthorityBackendRegistrationRequest request =
                        AuthorityBackendRegistrationWireCodec.decodeRequest(body);
                AuthorityBackendRegistrationReceipt receipt = registrationClient.register(request);
                respond(exchange, 200, AuthorityBackendRegistrationWireCodec.encodeReceipt(receipt));
            } catch (IllegalArgumentException exception) {
                respond(exchange, 400, exception.getMessage());
            } catch (RuntimeException exception) {
                respond(exchange, 500, exception.getMessage());
            }
        }
    }

    private static void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = Objects.requireNonNullElse(body, "").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", AuthorityBackendRegistrationWireCodec.CONTENT_TYPE);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
