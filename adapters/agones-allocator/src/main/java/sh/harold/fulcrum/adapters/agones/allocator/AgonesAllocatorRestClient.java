package sh.harold.fulcrum.adapters.agones.allocator;

import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.SlotId;
import sh.harold.fulcrum.host.api.HostAllocationClaim;
import sh.harold.fulcrum.host.api.HostAllocationPort;
import sh.harold.fulcrum.host.api.HostAllocationRequest;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostNetworkEndpoint;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public final class AgonesAllocatorRestClient implements HostAllocationPort {
    private static final String ALLOCATION_PATH = "/gameserverallocation";

    private final URI allocatorEndpoint;
    private final String namespace;
    private final HttpClient httpClient;

    public AgonesAllocatorRestClient(URI allocatorEndpoint, String namespace) {
        this(allocatorEndpoint, namespace, HttpClient.newHttpClient());
    }

    public AgonesAllocatorRestClient(URI allocatorEndpoint, String namespace, HttpClient httpClient) {
        this.allocatorEndpoint = Objects.requireNonNull(allocatorEndpoint, "allocatorEndpoint");
        this.namespace = AgonesAllocatorJson.requireNonBlank(namespace, "namespace");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    public static AgonesAllocatorRestClient mtls(
            URI allocatorEndpoint,
            String namespace,
            Path clientCertificatePath,
            Path clientKeyPath,
            Path caCertificatePath) {
        return new AgonesAllocatorRestClient(
                allocatorEndpoint,
                namespace,
                mtlsHttpClient(clientCertificatePath, clientKeyPath, caCertificatePath));
    }

    public static HttpClient mtlsHttpClient(
            Path clientCertificatePath,
            Path clientKeyPath,
            Path caCertificatePath) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(
                    keyManagers(clientCertificatePath, clientKeyPath),
                    trustManagers(caCertificatePath),
                    null);
            return HttpClient.newBuilder()
                    .sslContext(context)
                    .build();
        } catch (GeneralSecurityException | IOException exception) {
            throw new IllegalStateException("Failed to configure Agones allocator mTLS client", exception);
        }
    }

    @Override
    public HostAllocationClaim allocate(HostAllocationRequest request) {
        Objects.requireNonNull(request, "request");
        String requestBody = AgonesAllocatorJson.allocationRequest(namespace, request);
        HttpRequest httpRequest = HttpRequest.newBuilder(allocatorEndpoint.resolve(ALLOCATION_PATH))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> httpResponse;
        try {
            httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Agones allocation request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Agones allocation request failed", exception);
        }

        if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
            throw new IllegalStateException("Agones allocation failed with HTTP " + httpResponse.statusCode());
        }

        AgonesAllocationResponse allocation = AgonesAllocatorJson.allocationResponse(httpResponse.body());
        if (!HostInstanceKinds.PAPER.equals(allocation.instanceKind())) {
            throw new IllegalStateException("Agones allocation returned non-Paper Instance: " + allocation.instanceKind());
        }

        HostInstanceIdentity instanceIdentity = new HostInstanceIdentity(
                new InstanceId(allocation.instanceId()),
                HostInstanceKinds.PAPER,
                request.poolId(),
                new MachineRef(allocation.machineRef()),
                new PrincipalId(allocation.principalId()));

        return new HostAllocationClaim(
                new SlotId(allocation.slotId()),
                request.sessionId(),
                instanceIdentity,
                request.resolvedManifestId(),
                new HostNetworkEndpoint(allocation.address(), allocation.minecraftPort()),
                request.traceEnvelope(),
                request.requestedAt());
    }

    private static javax.net.ssl.KeyManager[] keyManagers(
            Path clientCertificatePath,
            Path clientKeyPath) throws GeneralSecurityException, IOException {
        X509Certificate[] certificates = certificates(clientCertificatePath);
        PrivateKey privateKey = privateKey(clientKeyPath);
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("agones-client", privateKey, new char[0], certificates);
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore, new char[0]);
        return factory.getKeyManagers();
    }

    private static javax.net.ssl.TrustManager[] trustManagers(
            Path caCertificatePath) throws GeneralSecurityException, IOException {
        X509Certificate[] certificates = certificates(caCertificatePath);
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        for (int index = 0; index < certificates.length; index++) {
            trustStore.setCertificateEntry("agones-ca-" + index, certificates[index]);
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trustStore);
        return factory.getTrustManagers();
    }

    private static X509Certificate[] certificates(Path path) throws GeneralSecurityException, IOException {
        Objects.requireNonNull(path, "path");
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> loaded;
        try (var input = Files.newInputStream(path)) {
            loaded = factory.generateCertificates(input);
        }
        if (loaded.isEmpty()) {
            throw new GeneralSecurityException("No X.509 certificates in " + path);
        }
        List<X509Certificate> certificates = new ArrayList<>(loaded.size());
        for (Certificate certificate : loaded) {
            certificates.add((X509Certificate) certificate);
        }
        return certificates.toArray(X509Certificate[]::new);
    }

    private static PrivateKey privateKey(Path path) throws IOException, GeneralSecurityException {
        Objects.requireNonNull(path, "path");
        String pem = Files.readString(path, StandardCharsets.US_ASCII);
        PemBlock block = pemBlock(pem, "PRIVATE KEY", "RSA PRIVATE KEY");
        byte[] encoded = block.decoded();
        if ("RSA PRIVATE KEY".equals(block.label())) {
            encoded = pkcs8RsaPrivateKey(encoded);
        }
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(encoded);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    private static PemBlock pemBlock(String pem, String... allowedLabels) {
        Matcher matcher = Pattern.compile(
                        "-----BEGIN ([A-Z ]+)-----(.*?)-----END \\1-----",
                        Pattern.DOTALL)
                .matcher(pem);
        while (matcher.find()) {
            String label = matcher.group(1);
            for (String allowedLabel : allowedLabels) {
                if (allowedLabel.equals(label)) {
                    String base64 = matcher.group(2).replaceAll("\\s", "");
                    return new PemBlock(label, Base64.getDecoder().decode(base64));
                }
            }
        }
        throw new IllegalArgumentException("PEM content missing one of " + List.of(allowedLabels));
    }

    private static byte[] pkcs8RsaPrivateKey(byte[] pkcs1) {
        byte[] version = new byte[]{0x02, 0x01, 0x00};
        byte[] algorithm = new byte[]{
                0x30, 0x0d,
                0x06, 0x09,
                0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
                0x05, 0x00};
        byte[] privateKey = der(0x04, pkcs1);
        return der(0x30, concat(version, algorithm, privateKey));
    }

    private static byte[] der(int tag, byte[] value) {
        byte[] length = derLength(value.length);
        return concat(new byte[]{(byte) tag}, length, value);
    }

    private static byte[] derLength(int length) {
        if (length < 0x80) {
            return new byte[]{(byte) length};
        }
        int value = length;
        int bytes = 0;
        while (value > 0) {
            bytes++;
            value >>>= 8;
        }
        byte[] encoded = new byte[bytes + 1];
        encoded[0] = (byte) (0x80 | bytes);
        for (int index = bytes; index > 0; index--) {
            encoded[index] = (byte) (length & 0xff);
            length >>>= 8;
        }
        return encoded;
    }

    private static byte[] concat(byte[]... arrays) {
        int size = 0;
        for (byte[] array : arrays) {
            size += array.length;
        }
        byte[] result = new byte[size];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }

    private record PemBlock(String label, byte[] decoded) {
        private PemBlock {
            label = Objects.requireNonNull(label, "label");
            decoded = decoded.clone();
        }

        @Override
        public byte[] decoded() {
            return decoded.clone();
        }
    }
}
