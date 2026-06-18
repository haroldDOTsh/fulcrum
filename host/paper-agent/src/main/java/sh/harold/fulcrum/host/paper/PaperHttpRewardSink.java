package sh.harold.fulcrum.host.paper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

public final class PaperHttpRewardSink implements PaperRewardSink {
    private final URI endpoint;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public PaperHttpRewardSink(URI endpoint) {
        this(endpoint, HttpClient.newHttpClient(), Duration.ofSeconds(2));
    }

    PaperHttpRewardSink(URI endpoint, HttpClient httpClient, Duration requestTimeout) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
    }

    @Override
    public void publish(PaperSessionRewardReport report) {
        Objects.requireNonNull(report, "report");
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(
                        PaperSessionRewardReportCodec.encode(report),
                        StandardCharsets.UTF_8))
                .build();
        send(request);
    }

    private void send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Paper reward bridge request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Paper reward bridge request failed", exception);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "Paper reward bridge request failed with HTTP " + response.statusCode());
        }
    }
}
