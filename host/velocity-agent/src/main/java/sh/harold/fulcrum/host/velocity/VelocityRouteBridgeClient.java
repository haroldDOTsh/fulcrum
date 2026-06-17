package sh.harold.fulcrum.host.velocity;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

public final class VelocityRouteBridgeClient {
    private final URI routeBridgeUrl;
    private final HttpClient httpClient;

    public VelocityRouteBridgeClient(URI routeBridgeUrl) {
        this(routeBridgeUrl, HttpClient.newHttpClient());
    }

    public VelocityRouteBridgeClient(URI routeBridgeUrl, HttpClient httpClient) {
        this.routeBridgeUrl = Objects.requireNonNull(routeBridgeUrl, "routeBridgeUrl");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    public Optional<VelocityRouteTransfer> execute(VelocityRouteBridgeRequest request) {
        Objects.requireNonNull(request, "request");
        HttpRequest httpRequest = HttpRequest.newBuilder(routeBridgeUrl)
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(
                        VelocityRouteBridgeCodec.encodeRequest(request),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Velocity route bridge request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Velocity route bridge request failed", exception);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Velocity route bridge failed with HTTP "
                    + response.statusCode()
                    + ": "
                    + response.body().strip());
        }
        return VelocityRouteBridgeCodec.decodeResponse(response.body());
    }
}
