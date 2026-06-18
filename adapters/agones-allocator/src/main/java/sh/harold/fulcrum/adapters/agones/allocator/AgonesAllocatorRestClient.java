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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
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
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

public final class AgonesAllocatorRestClient implements HostAllocationPort {
    private static final String ALLOCATION_PATH = "/gameserverallocation";

    private final URI allocatorEndpoint;
    private final String namespace;
    private final AllocatorHttpTransport transport;

    public AgonesAllocatorRestClient(URI allocatorEndpoint, String namespace) {
        this(allocatorEndpoint, namespace, HttpClient.newHttpClient());
    }

    public AgonesAllocatorRestClient(URI allocatorEndpoint, String namespace, HttpClient httpClient) {
        this(allocatorEndpoint, namespace, new JdkHttpClientTransport(Objects.requireNonNull(httpClient, "httpClient")));
    }

    private AgonesAllocatorRestClient(URI allocatorEndpoint, String namespace, AllocatorHttpTransport transport) {
        this.allocatorEndpoint = Objects.requireNonNull(allocatorEndpoint, "allocatorEndpoint");
        this.namespace = AgonesAllocatorJson.requireNonBlank(namespace, "namespace");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public static AgonesAllocatorRestClient mtls(
            URI allocatorEndpoint,
            String namespace,
            Path clientCertificatePath,
            Path clientKeyPath,
            Path caCertificatePath) {
        return mtls(allocatorEndpoint, namespace, clientCertificatePath, clientKeyPath, caCertificatePath, false);
    }

    public static AgonesAllocatorRestClient mtls(
            URI allocatorEndpoint,
            String namespace,
            Path clientCertificatePath,
            Path clientKeyPath,
            Path caCertificatePath,
            boolean disableHostnameVerification) {
        return new AgonesAllocatorRestClient(
                allocatorEndpoint,
                namespace,
                tlsTransport(
                        mtlsSslContext(
                                clientCertificatePath,
                                clientKeyPath,
                                caCertificatePath,
                                disableHostnameVerification),
                        disableHostnameVerification));
    }

    public static AgonesAllocatorRestClient tls(
            URI allocatorEndpoint,
            String namespace,
            Path caCertificatePath) {
        return tls(allocatorEndpoint, namespace, caCertificatePath, false);
    }

    public static AgonesAllocatorRestClient tls(
            URI allocatorEndpoint,
            String namespace,
            Path caCertificatePath,
            boolean disableHostnameVerification) {
        return new AgonesAllocatorRestClient(
                allocatorEndpoint,
                namespace,
                tlsTransport(tlsSslContext(caCertificatePath, disableHostnameVerification), disableHostnameVerification));
    }

    public static HttpClient mtlsHttpClient(
            Path clientCertificatePath,
            Path clientKeyPath,
            Path caCertificatePath) {
        return httpClient(mtlsSslContext(clientCertificatePath, clientKeyPath, caCertificatePath));
    }

    public static HttpClient tlsHttpClient(Path caCertificatePath) {
        return httpClient(tlsSslContext(caCertificatePath, false));
    }

    private static SSLContext mtlsSslContext(
            Path clientCertificatePath,
            Path clientKeyPath,
            Path caCertificatePath) {
        return mtlsSslContext(clientCertificatePath, clientKeyPath, caCertificatePath, false);
    }

    private static SSLContext mtlsSslContext(
            Path clientCertificatePath,
            Path clientKeyPath,
            Path caCertificatePath,
            boolean pinnedCaTrustOnly) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(
                    keyManagers(clientCertificatePath, clientKeyPath),
                    trustManagers(caCertificatePath, pinnedCaTrustOnly),
                    null);
            return context;
        } catch (GeneralSecurityException | IOException exception) {
            throw new IllegalStateException("Failed to configure Agones allocator mTLS client", exception);
        }
    }

    private static SSLContext tlsSslContext(Path caCertificatePath, boolean pinnedCaTrustOnly) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers(caCertificatePath, pinnedCaTrustOnly), null);
            return context;
        } catch (GeneralSecurityException | IOException exception) {
            throw new IllegalStateException("Failed to configure Agones allocator TLS client", exception);
        }
    }

    private static HttpClient httpClient(SSLContext context) {
        return HttpClient.newBuilder().sslContext(context).build();
    }

    private static AllocatorHttpTransport tlsTransport(SSLContext context, boolean disableHostnameVerification) {
        if (disableHostnameVerification) {
            return new UrlConnectionTransport(context, true);
        }
        return new JdkHttpClientTransport(httpClient(context));
    }

    @Override
    public HostAllocationClaim allocate(HostAllocationRequest request) {
        Objects.requireNonNull(request, "request");
        String requestBody = AgonesAllocatorJson.allocationRequest(namespace, request);
        AllocatorHttpResponse httpResponse;
        try {
            httpResponse = transport.send(allocatorEndpoint.resolve(ALLOCATION_PATH), requestBody);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Agones allocation request was interrupted", exception);
        } catch (IOException exception) {
            System.err.println("Agones allocator HTTP request failed: "
                    + exception.getClass().getName() + ": " + exception.getMessage());
            exception.printStackTrace(System.err);
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

    private interface AllocatorHttpTransport {
        AllocatorHttpResponse send(URI uri, String requestBody) throws IOException, InterruptedException;
    }

    private record AllocatorHttpResponse(int statusCode, String body) {
    }

    private record JdkHttpClientTransport(HttpClient httpClient) implements AllocatorHttpTransport {
        private JdkHttpClientTransport {
            httpClient = Objects.requireNonNull(httpClient, "httpClient");
        }

        @Override
        public AllocatorHttpResponse send(URI uri, String requestBody) throws IOException, InterruptedException {
            HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new AllocatorHttpResponse(response.statusCode(), response.body());
        }
    }

    private record UrlConnectionTransport(
            SSLContext sslContext,
            boolean disableHostnameVerification) implements AllocatorHttpTransport {
        private UrlConnectionTransport {
            sslContext = Objects.requireNonNull(sslContext, "sslContext");
        }

        @Override
        public AllocatorHttpResponse send(URI uri, String requestBody) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            if (connection instanceof HttpsURLConnection httpsConnection) {
                if (disableHostnameVerification) {
                    httpsConnection.setSSLSocketFactory(new HostnameVerificationDisabledSocketFactory(
                            sslContext.getSocketFactory()));
                    httpsConnection.setHostnameVerifier((hostname, session) -> true);
                } else {
                    httpsConnection.setSSLSocketFactory(sslContext.getSocketFactory());
                }
            }
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = requestBody.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }

            int statusCode = connection.getResponseCode();
            InputStream input = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String responseBody = "";
            if (input != null) {
                try (input) {
                    responseBody = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            connection.disconnect();
            return new AllocatorHttpResponse(statusCode, responseBody);
        }
    }

    private static final class HostnameVerificationDisabledSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        private HostnameVerificationDisabledSocketFactory(SSLSocketFactory delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
            return withoutEndpointIdentification(delegate.createSocket(socket, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return withoutEndpointIdentification(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localAddress, int localPort) throws IOException {
            return withoutEndpointIdentification(delegate.createSocket(host, port, localAddress, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return withoutEndpointIdentification(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(
                InetAddress address,
                int port,
                InetAddress localAddress,
                int localPort) throws IOException {
            return withoutEndpointIdentification(delegate.createSocket(address, port, localAddress, localPort));
        }

        private static Socket withoutEndpointIdentification(Socket socket) {
            if (socket instanceof SSLSocket sslSocket) {
                SSLParameters parameters = sslSocket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm(null);
                sslSocket.setSSLParameters(parameters);
            }
            return socket;
        }
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
        return trustManagers(caCertificatePath, false);
    }

    private static javax.net.ssl.TrustManager[] trustManagers(
            Path caCertificatePath,
            boolean pinnedCaTrustOnly) throws GeneralSecurityException, IOException {
        X509Certificate[] certificates = certificates(caCertificatePath);
        if (pinnedCaTrustOnly) {
            return new javax.net.ssl.TrustManager[]{new PinnedCaTrustManager(certificates)};
        }
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

    private static final class PinnedCaTrustManager implements X509TrustManager {
        private static final String SERVER_AUTH_EXTENDED_KEY_USAGE = "1.3.6.1.5.5.7.3.1";

        private final X509Certificate[] acceptedIssuers;

        private PinnedCaTrustManager(X509Certificate[] acceptedIssuers) {
            this.acceptedIssuers = acceptedIssuers.clone();
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
            throw new java.security.cert.CertificateException("Pinned Agones allocator trust is server-only");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
            if (chain == null || chain.length == 0) {
                throw new java.security.cert.CertificateException("Agones allocator did not present a certificate chain");
            }
            java.security.cert.CertificateException failure = null;
            for (X509Certificate acceptedIssuer : acceptedIssuers) {
                try {
                    verifyChain(chain, acceptedIssuer);
                    verifyServerUsage(chain[0]);
                    return;
                } catch (java.security.cert.CertificateException exception) {
                    failure = exception;
                }
            }
            throw new java.security.cert.CertificateException(
                    "Agones allocator certificate was not signed by a pinned CA",
                    failure);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return acceptedIssuers.clone();
        }

        private static void verifyChain(
                X509Certificate[] chain,
                X509Certificate acceptedIssuer) throws java.security.cert.CertificateException {
            try {
                for (X509Certificate certificate : chain) {
                    certificate.checkValidity();
                }
                acceptedIssuer.checkValidity();
                for (int index = 0; index < chain.length - 1; index++) {
                    verifyIssuedBy(chain[index], chain[index + 1]);
                }
                verifyIssuedBy(chain[chain.length - 1], acceptedIssuer);
            } catch (GeneralSecurityException exception) {
                throw new java.security.cert.CertificateException(
                        "Agones allocator certificate chain failed pinned-CA validation",
                        exception);
            }
        }

        private static void verifyIssuedBy(
                X509Certificate certificate,
                X509Certificate issuer) throws GeneralSecurityException {
            if (!certificate.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())) {
                throw new java.security.cert.CertificateException(
                        "Certificate issuer does not match pinned issuer subject");
            }
            certificate.verify(issuer.getPublicKey());
        }

        private static void verifyServerUsage(X509Certificate certificate) throws java.security.cert.CertificateException {
            List<String> extendedKeyUsage = certificate.getExtendedKeyUsage();
            if (extendedKeyUsage != null && !extendedKeyUsage.contains(SERVER_AUTH_EXTENDED_KEY_USAGE)) {
                throw new java.security.cert.CertificateException(
                        "Agones allocator certificate is not valid for TLS server authentication");
            }
        }
    }
}
