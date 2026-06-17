package sh.harold.fulcrum.host.paper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

public final class PaperCapabilityBridgeClient implements PaperCapabilityBridge {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);

    private final URI subjectViewUrl;
    private final URI chatDecorationUrl;
    private final HttpClient httpClient;

    public PaperCapabilityBridgeClient(URI bridgeUrl) {
        this(bridgeUrl, HttpClient.newHttpClient());
    }

    public PaperCapabilityBridgeClient(URI bridgeUrl, HttpClient httpClient) {
        Objects.requireNonNull(bridgeUrl, "bridgeUrl");
        this.subjectViewUrl = endpoint(bridgeUrl, "subject-view");
        this.chatDecorationUrl = endpoint(bridgeUrl, "chat-decoration");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public PaperSubjectCapabilityView subjectView(PaperSubjectCapabilityRequest request) {
        String response = post(subjectViewUrl, PaperCapabilityBridgeCodec.encodeSubjectRequest(request));
        return PaperCapabilityBridgeCodec.decodeSubjectView(response);
    }

    @Override
    public PaperChatDecorationResponse decorateChat(PaperChatDecorationRequest request) {
        String response = post(chatDecorationUrl, PaperCapabilityBridgeCodec.encodeChatRequest(request));
        return PaperCapabilityBridgeCodec.decodeChatResponse(response);
    }

    private String post(URI url, String payload) {
        HttpRequest httpRequest = HttpRequest.newBuilder(url)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Paper capability bridge request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Paper capability bridge request failed", exception);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Paper capability bridge failed with HTTP "
                    + response.statusCode()
                    + ": "
                    + response.body().strip());
        }
        return response.body();
    }

    static URI endpoint(URI bridgeUrl, String endpointName) {
        String basePath = bridgeUrl.getPath();
        String normalizedBase = basePath == null || basePath.isBlank() || "/".equals(basePath)
                ? ""
                : stripTrailingSlash(basePath);
        return URI.create(bridgeUrl.getScheme()
                + "://"
                + bridgeUrl.getHost()
                + ":"
                + bridgeUrl.getPort()
                + normalizedBase
                + "/"
                + endpointName);
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
