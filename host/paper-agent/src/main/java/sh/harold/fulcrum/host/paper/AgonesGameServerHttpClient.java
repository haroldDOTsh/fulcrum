package sh.harold.fulcrum.host.paper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

public final class AgonesGameServerHttpClient implements AgonesGameServerSdkClient {
    private static final Pattern NAME_FIELD = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern NAMESPACE_FIELD = Pattern.compile("\"namespace\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern STATE_FIELD = Pattern.compile("\"state\"\\s*:\\s*\"([^\"]+)\"");

    private final URI sdkEndpoint;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public AgonesGameServerHttpClient(URI sdkEndpoint) {
        this(sdkEndpoint, HttpClient.newHttpClient(), Duration.ofSeconds(2));
    }

    public AgonesGameServerHttpClient(URI sdkEndpoint, HttpClient httpClient, Duration requestTimeout) {
        this.sdkEndpoint = Objects.requireNonNull(sdkEndpoint, "sdkEndpoint");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
    }

    @Override
    public void ready() {
        post("/ready", "{}");
    }

    @Override
    public void health() {
        post("/health", "{}");
    }

    @Override
    public void allocate() {
        post("/allocate", "{}");
    }

    @Override
    public void reserve(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("duration must be positive");
        }
        long seconds = Math.max(1, (duration.toMillis() + 999) / 1_000);
        post("/reserve", "{\"seconds\":" + seconds + "}");
    }

    @Override
    public void shutdown() {
        post("/shutdown", "{}");
    }

    @Override
    public AgonesGameServerSnapshot gameServer() {
        String body = send(HttpRequest.newBuilder(endpoint("/gameserver"))
                .timeout(requestTimeout)
                .GET()
                .build());
        return new AgonesGameServerSnapshot(
                field(NAME_FIELD, body, "name"),
                field(NAMESPACE_FIELD, body, "namespace"),
                field(STATE_FIELD, body, "state"),
                body);
    }

    private void post(String path, String body) {
        send(HttpRequest.newBuilder(endpoint(path))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build());
    }

    private String send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Agones GameServer SDK request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Agones GameServer SDK request failed", exception);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Agones GameServer SDK request failed with HTTP " + response.statusCode());
        }
        return response.body();
    }

    private URI endpoint(String path) {
        return sdkEndpoint.resolve(path);
    }

    private static String field(Pattern pattern, String body, String label) {
        var matcher = pattern.matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException("Agones GameServer SDK response is missing " + label);
        }
        return matcher.group(1);
    }
}
