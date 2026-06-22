package sh.harold.fulcrum.sdk.authority;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class HttpAuthorityBackendRegistrationClient implements AuthorityBackendRegistrationClient {
    private final URI endpoint;
    private final HttpClient client;

    public HttpAuthorityBackendRegistrationClient(URI endpoint) {
        this(endpoint, HttpClient.newHttpClient());
    }

    public HttpAuthorityBackendRegistrationClient(URI endpoint, HttpClient client) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public AuthorityBackendRegistrationReceipt register(AuthorityBackendRegistrationRequest request) {
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", AuthorityBackendRegistrationWireCodec.CONTENT_TYPE)
                .header("Accept", AuthorityBackendRegistrationWireCodec.CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(
                        AuthorityBackendRegistrationWireCodec.encodeRequest(request),
                        StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = client.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("authority backend registration failed with HTTP "
                        + response.statusCode()
                        + ": "
                        + response.body());
            }
            return AuthorityBackendRegistrationWireCodec.decodeReceipt(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("authority backend registration was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("authority backend registration request failed", exception);
        }
    }

    @Override
    public AuthorityBackendDeregistrationReceipt deregister(AuthorityBackendDeregistrationRequest request) {
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint.resolve("deregister"))
                .header("Content-Type", AuthorityBackendRegistrationWireCodec.CONTENT_TYPE)
                .header("Accept", AuthorityBackendRegistrationWireCodec.CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(
                        AuthorityBackendRegistrationWireCodec.encodeDeregistrationRequest(request),
                        StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = client.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("authority backend deregistration failed with HTTP "
                        + response.statusCode()
                        + ": "
                        + response.body());
            }
            return AuthorityBackendRegistrationWireCodec.decodeDeregistrationReceipt(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("authority backend deregistration was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("authority backend deregistration request failed", exception);
        }
    }
}
