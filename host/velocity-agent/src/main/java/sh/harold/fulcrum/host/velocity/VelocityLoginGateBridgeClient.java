package sh.harold.fulcrum.host.velocity;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class VelocityLoginGateBridgeClient implements VelocityLoginGateEvaluator {
    private final URI loginGateBridgeUrl;
    private final HttpClient httpClient;

    public VelocityLoginGateBridgeClient(URI loginGateBridgeUrl) {
        this(loginGateBridgeUrl, HttpClient.newHttpClient());
    }

    public VelocityLoginGateBridgeClient(URI loginGateBridgeUrl, HttpClient httpClient) {
        this.loginGateBridgeUrl = Objects.requireNonNull(loginGateBridgeUrl, "loginGateBridgeUrl");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public VelocityLoginGateDecision evaluate(VelocityLoginGateRequest request) {
        Objects.requireNonNull(request, "request");
        HttpRequest httpRequest = HttpRequest.newBuilder(loginGateBridgeUrl)
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(
                        VelocityLoginGateBridgeCodec.encodeRequest(request),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Velocity login gate bridge request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Velocity login gate bridge request failed", exception);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Velocity login gate bridge failed with HTTP "
                    + response.statusCode()
                    + ": "
                    + response.body().strip());
        }
        return VelocityLoginGateBridgeCodec.decodeDecision(response.body());
    }
}
