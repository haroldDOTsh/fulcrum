package sh.harold.fulcrum.adapters.objectstorage;

import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.core.artifact.ArtifactBlobLayout;
import sh.harold.fulcrum.core.artifact.ArtifactDigestReference;
import sh.harold.fulcrum.core.artifact.ArtifactObjectAddress;
import sh.harold.fulcrum.core.manifest.ArtifactPin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class S3ObjectStorageAdapter implements ObjectStorageAdapter {
    private static final String OBJECT_SCHEME = "object://";
    private static final String SERVICE = "s3";
    private static final String TERMINATOR = "aws4_request";
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter SCOPE_DATE = DateTimeFormatter
            .ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC);

    private final HttpClient httpClient;
    private final Clock clock;
    private final URI endpoint;
    private final String region;
    private final String accessKeyId;
    private final String secretAccessKey;
    private final String bucket;
    private boolean bucketReady;

    public S3ObjectStorageAdapter(
            URI endpoint,
            String region,
            String accessKeyId,
            String secretAccessKey,
            String bucket) {
        this(HttpClient.newHttpClient(), Clock.systemUTC(), endpoint, region, accessKeyId, secretAccessKey, bucket);
    }

    S3ObjectStorageAdapter(
            HttpClient httpClient,
            Clock clock,
            URI endpoint,
            String region,
            String accessKeyId,
            String secretAccessKey,
            String bucket) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.endpoint = requireEndpoint(endpoint);
        this.region = requireNonBlank(region, "region");
        this.accessKeyId = requireNonBlank(accessKeyId, "accessKeyId");
        this.secretAccessKey = requireNonBlank(secretAccessKey, "secretAccessKey");
        this.bucket = requireBucket(bucket);
    }

    @Override
    public StoredObject put(ArtifactPin artifactPin, byte[] bytes) throws IOException {
        Objects.requireNonNull(artifactPin, "artifactPin");
        byte[] copiedBytes = Objects.requireNonNull(bytes, "bytes").clone();
        ArtifactDigestReference digest = verifiedDigest(artifactPin, copiedBytes);
        ArtifactObjectAddress address = ArtifactBlobLayout.objectAddress(bucket, artifactPin);
        ensureBucket();
        HttpResponse<byte[]> response = send("PUT", keyFor(address), copiedBytes);
        requireStatus(response, "put object", 200);
        return new StoredObject(address, copiedBytes.length, digest);
    }

    @Override
    public Optional<byte[]> read(ArtifactObjectAddress address) throws IOException {
        String key = keyFor(address);
        HttpResponse<byte[]> response = send("GET", key, new byte[0]);
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        requireStatus(response, "read object", 200);
        return Optional.of(response.body());
    }

    @Override
    public boolean exists(ArtifactObjectAddress address) throws IOException {
        String key = keyFor(address);
        HttpResponse<byte[]> response = send("HEAD", key, new byte[0]);
        if (response.statusCode() == 404) {
            return false;
        }
        requireStatus(response, "check object", 200);
        return true;
    }

    private synchronized void ensureBucket() throws IOException {
        if (bucketReady) {
            return;
        }
        HttpResponse<byte[]> head = send("HEAD", "", new byte[0]);
        if (head.statusCode() == 200) {
            bucketReady = true;
            return;
        }
        if (head.statusCode() != 404) {
            requireStatus(head, "check bucket", 200, 404);
        }
        HttpResponse<byte[]> create = send("PUT", "", new byte[0]);
        requireStatus(create, "create bucket", 200, 201);
        bucketReady = true;
    }

    private HttpResponse<byte[]> send(String method, String key, byte[] body) throws IOException {
        try {
            URI uri = objectUri(key);
            byte[] copiedBody = body.clone();
            String payloadHash = sha256(copiedBody);
            Instant now = clock.instant();
            String amzDate = AMZ_DATE.format(now);
            String scopeDate = SCOPE_DATE.format(now);
            String host = hostHeader(uri);
            String authorization = authorization(method, uri, host, payloadHash, amzDate, scopeDate);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(copiedBody))
                    .header("x-amz-date", amzDate)
                    .header("x-amz-content-sha256", payloadHash)
                    .header("Authorization", authorization)
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while calling S3 object storage", exception);
        }
    }

    private String authorization(
            String method,
            URI uri,
            String host,
            String payloadHash,
            String amzDate,
            String scopeDate) {
        String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
        String canonicalHeaders = "host:" + host + "\n"
                + "x-amz-content-sha256:" + payloadHash + "\n"
                + "x-amz-date:" + amzDate + "\n";
        String canonicalRequest = method + "\n"
                + uri.getRawPath() + "\n"
                + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + payloadHash;
        String credentialScope = scopeDate + "/" + region + "/" + SERVICE + "/" + TERMINATOR;
        String stringToSign = ALGORITHM + "\n"
                + amzDate + "\n"
                + credentialScope + "\n"
                + sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        String signature = hmacHex(signingKey(scopeDate), stringToSign);
        return ALGORITHM
                + " Credential=" + accessKeyId + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;
    }

    private byte[] signingKey(String scopeDate) {
        byte[] dateKey = hmac(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8), scopeDate);
        byte[] regionKey = hmac(dateKey, region);
        byte[] serviceKey = hmac(regionKey, SERVICE);
        return hmac(serviceKey, TERMINATOR);
    }

    private URI objectUri(String key) {
        String path = "/" + bucket;
        if (!key.isBlank()) {
            path += "/" + key;
        }
        return endpoint.resolve(path);
    }

    private String keyFor(ArtifactObjectAddress address) {
        Objects.requireNonNull(address, "address");
        String prefix = OBJECT_SCHEME + bucket + "/";
        if (!address.value().startsWith(prefix)) {
            throw new IllegalArgumentException("object address is outside the configured bucket");
        }
        String key = address.value().substring(prefix.length());
        if (key.isBlank() || key.contains("\\") || key.contains("..")) {
            throw new IllegalArgumentException("object address contains an invalid object key");
        }
        return key;
    }

    private static void requireStatus(HttpResponse<byte[]> response, String action, int... expected)
            throws IOException {
        for (int status : expected) {
            if (response.statusCode() == status) {
                return;
            }
        }
        throw new IOException("failed to " + action + " through S3 object storage: HTTP "
                + response.statusCode() + " body=" + new String(response.body(), StandardCharsets.UTF_8));
    }

    private static ArtifactDigestReference verifiedDigest(ArtifactPin artifactPin, byte[] bytes) {
        ArtifactDigestReference digest = ArtifactBlobLayout.digestFor(artifactPin);
        if (!digest.algorithm().equals("sha-256")) {
            throw new IllegalArgumentException("S3 object storage supports sha-256 artifact pins");
        }
        String actualDigest = sha256(bytes);
        if (!actualDigest.equals(digest.value())) {
            throw new IllegalArgumentException("artifact bytes do not match the pinned digest");
        }
        return digest;
    }

    private static URI requireEndpoint(URI endpoint) {
        URI checked = Objects.requireNonNull(endpoint, "endpoint").normalize();
        String scheme = checked.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("S3 object storage endpoint must use http or https");
        }
        if (checked.getHost() == null || checked.getHost().isBlank()) {
            throw new IllegalArgumentException("S3 object storage endpoint must include a host");
        }
        if (checked.getRawQuery() != null || checked.getRawFragment() != null || checked.getRawUserInfo() != null) {
            throw new IllegalArgumentException("S3 object storage endpoint must not include user info, query, or fragment");
        }
        String path = checked.getRawPath();
        if (path != null && !path.isBlank() && !"/".equals(path)) {
            throw new IllegalArgumentException("S3 object storage endpoint must not include a path");
        }
        return URI.create(scheme + "://" + hostHeader(checked) + "/");
    }

    private static String hostHeader(URI uri) {
        int port = uri.getPort();
        if (port == -1) {
            return uri.getHost();
        }
        return uri.getHost() + ":" + port;
    }

    private static String requireBucket(String bucket) {
        String checked = requireNonBlank(bucket, "bucket").toLowerCase(Locale.ROOT);
        ArtifactBlobLayout.objectAddress(checked, new ArtifactPin(
                new ArtifactId("artifact.bucket.validation"),
                "0".repeat(64),
                "validation"));
        return checked;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }

    private static String hmacHex(byte[] key, String value) {
        return HexFormat.of().formatHex(hmac(key, value));
    }
}
