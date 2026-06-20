package sh.harold.fulcrum.validation.escrowe2e;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class KubernetesEscrowPodDisruptor {
    private static final Path SERVICE_ACCOUNT_DIR = Path.of("/var/run/secrets/kubernetes.io/serviceaccount");
    private static final String DEFAULT_TOKEN_FILE = "token";
    private static final String DEFAULT_CA_FILE = "ca.crt";

    private final Config config;
    private final KubernetesApi api;
    private final Clock clock;

    KubernetesEscrowPodDisruptor(Config config, KubernetesApi api, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.api = Objects.requireNonNull(api, "api");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    static KubernetesEscrowPodDisruptor fromEnvironment(Map<String, String> environment) throws IOException {
        Config config = Config.fromEnvironment(environment, SERVICE_ACCOUNT_DIR);
        return new KubernetesEscrowPodDisruptor(config, JdkKubernetesApi.open(config), Clock.systemUTC());
    }

    PodReplacementEvidence deleteReadyPodAndAwaitReplacement() {
        return deleteReadyPodAndAwaitReplacement(config.requestTimeout());
    }

    PodReplacementEvidence deleteReadyPodAndAwaitReplacement(Duration timeout) {
        Duration checkedTimeout = positive(timeout, "timeout");
        PodSnapshot target = awaitSingleReadyPod(checkedTimeout, Optional.empty());
        Instant deletionRequestedAt = clock.instant();
        api.deletePod(config.namespace(), target.name(), target.uid());
        PodSnapshot replacement = awaitSingleReadyPod(checkedTimeout, Optional.of(new ReplacementTarget(target.uid())));
        return new PodReplacementEvidence(
                target.name(),
                target.uid(),
                target.creationTimestamp(),
                deletionRequestedAt,
                replacement.name(),
                replacement.uid(),
                replacement.creationTimestamp());
    }

    private PodSnapshot awaitSingleReadyPod(Duration timeout, Optional<ReplacementTarget> replacementTarget) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<PodSnapshot> lastObservedPods = List.of();
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            lastObservedPods = pods(api.listPods(config.namespace(), config.podSelector()));
            List<PodSnapshot> readyPods = readyPods(lastObservedPods);
            if (readyPods.size() > 1) {
                throw new IllegalStateException("refusing escrow pod deletion because selector matched multiple Ready pods: "
                        + readyPods.stream().map(pod -> pod.name() + "/" + pod.uid()).toList());
            }
            if (readyPods.size() == 1) {
                PodSnapshot pod = readyPods.getFirst();
                if (replacementTarget.isEmpty()) {
                    return pod;
                }
                ReplacementTarget target = replacementTarget.orElseThrow();
                if (!pod.uid().equals(target.deletedUid())) {
                    return pod;
                }
            }
            sleep();
        }
        throw new IllegalStateException("timed out after " + timeout + " waiting for a single Ready escrow pod"
                + replacementTarget.map(target -> " with a UID different from " + target.deletedUid()).orElse("")
                + "; lastObservedPods=" + podSummaries(lastObservedPods));
    }

    private static List<PodSnapshot> readyPods(List<PodSnapshot> pods) {
        return pods.stream()
                .filter(PodSnapshot::ready)
                .filter(PodSnapshot::running)
                .filter(pod -> pod.deletionTimestamp().isEmpty())
                .toList();
    }

    static List<PodSnapshot> pods(String json) {
        return itemObjects(Objects.requireNonNull(json, "json")).stream()
                .map(KubernetesEscrowPodDisruptor::pod)
                .toList();
    }

    private static PodSnapshot pod(String item) {
        String metadata = objectField(item, "metadata")
                .orElseThrow(() -> new IllegalArgumentException("pod item missing metadata"));
        String status = objectField(item, "status").orElse("");
        return new PodSnapshot(
                stringField(metadata, "name").orElseThrow(() -> new IllegalArgumentException("pod metadata missing name")),
                stringField(metadata, "uid").orElseThrow(() -> new IllegalArgumentException("pod metadata missing uid")),
                stringField(metadata, "creationTimestamp").map(Instant::parse).orElse(Instant.EPOCH),
                stringField(metadata, "deletionTimestamp").map(Instant::parse),
                "Running".equals(stringField(status, "phase").orElse("")),
                readyCondition(status));
    }

    private static boolean readyCondition(String status) {
        String conditions = arrayField(status, "conditions").orElse("");
        return itemObjects(conditions).stream()
                .anyMatch(condition -> "Ready".equals(stringField(condition, "type").orElse(""))
                        && "True".equals(stringField(condition, "status").orElse("")));
    }

    private static List<String> itemObjects(String json) {
        String array = arrayField(json, "items").orElse(json);
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < array.length(); index++) {
            char current = array.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (current == '{') {
                if (depth == 0) {
                    start = index;
                }
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(array.substring(start, index + 1));
                    start = -1;
                }
            }
        }
        return objects;
    }

    private static Optional<String> objectField(String json, String fieldName) {
        int valueStart = structuredValueStart(json, fieldName, '{');
        if (valueStart < 0) {
            return Optional.empty();
        }
        return Optional.of(json.substring(valueStart, matchingEnd(json, valueStart, '{', '}') + 1));
    }

    private static Optional<String> arrayField(String json, String fieldName) {
        int valueStart = structuredValueStart(json, fieldName, '[');
        if (valueStart < 0) {
            return Optional.empty();
        }
        return Optional.of(json.substring(valueStart, matchingEnd(json, valueStart, '[', ']') + 1));
    }

    private static int structuredValueStart(String json, String fieldName, char expectedStart) {
        int valueStart = directFieldValueStart(json, fieldName);
        if (valueStart < 0 || valueStart >= json.length()) {
            return -1;
        }
        return json.charAt(valueStart) == expectedStart ? valueStart : -1;
    }

    private static int directFieldValueStart(String json, String fieldName) {
        int index = skipWhitespace(json, 0);
        if (index >= json.length() || json.charAt(index) != '{') {
            return -1;
        }
        index++;
        while (index < json.length()) {
            index = skipWhitespace(json, index);
            if (index >= json.length() || json.charAt(index) == '}') {
                return -1;
            }
            if (json.charAt(index) == ',') {
                index++;
                continue;
            }
            if (json.charAt(index) != '"') {
                return -1;
            }
            int keyEnd = stringEnd(json, index);
            String key = unescape(json.substring(index + 1, keyEnd));
            index = skipWhitespace(json, keyEnd + 1);
            if (index >= json.length() || json.charAt(index) != ':') {
                return -1;
            }
            index = skipWhitespace(json, index + 1);
            if (key.equals(fieldName)) {
                return index;
            }
            index = valueEnd(json, index);
        }
        return -1;
    }

    private static int matchingEnd(String json, int start, char open, char close) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < json.length(); index++) {
            char current = json.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (current == open) {
                depth++;
            } else if (current == close) {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        throw new IllegalArgumentException("unterminated JSON value");
    }

    private static Optional<String> stringField(String json, String fieldName) {
        int valueStart = directFieldValueStart(json, fieldName);
        if (valueStart < 0 || valueStart >= json.length() || json.charAt(valueStart) != '"') {
            return Optional.empty();
        }
        int valueEnd = stringEnd(json, valueStart);
        return Optional.of(unescape(json.substring(valueStart + 1, valueEnd)));
    }

    private static int valueEnd(String json, int start) {
        int index = skipWhitespace(json, start);
        if (index >= json.length()) {
            return index;
        }
        char current = json.charAt(index);
        if (current == '{') {
            return matchingEnd(json, index, '{', '}') + 1;
        }
        if (current == '[') {
            return matchingEnd(json, index, '[', ']') + 1;
        }
        if (current == '"') {
            return stringEnd(json, index) + 1;
        }
        while (index < json.length()) {
            current = json.charAt(index);
            if (current == ',' || current == '}' || current == ']') {
                return index;
            }
            index++;
        }
        return index;
    }

    private static int stringEnd(String json, int start) {
        boolean escaped = false;
        for (int index = start + 1; index < json.length(); index++) {
            char current = json.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '"') {
                return index;
            }
        }
        throw new IllegalArgumentException("unterminated JSON string");
    }

    private static int skipWhitespace(String json, int start) {
        int index = start;
        while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
            index++;
        }
        return index;
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static List<String> podSummaries(List<PodSnapshot> pods) {
        return pods.stream()
                .map(pod -> pod.name()
                        + "/" + pod.uid()
                        + "{running=" + pod.running()
                        + ",ready=" + pod.ready()
                        + ",terminating=" + pod.deletionTimestamp().isPresent()
                        + ",createdAt=" + pod.creationTimestamp()
                        + "}")
                .toList();
    }

    private static void sleep() {
        try {
            Thread.sleep(250L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for escrow pod replacement", exception);
        }
    }

    private static Duration positive(Duration duration, String label) {
        Duration checked = Objects.requireNonNull(duration, label);
        if (checked.isZero() || checked.isNegative()) {
            throw new IllegalArgumentException(label + " must be positive");
        }
        return checked;
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    interface KubernetesApi {
        String listPods(String namespace, String selector);

        void deletePod(String namespace, String podName, String uid);
    }

    record Config(
            URI apiServer,
            String namespace,
            String podSelector,
            String bearerToken,
            Path caCertificatePath,
            Duration requestTimeout) {
        Config {
            apiServer = Objects.requireNonNull(apiServer, "apiServer");
            namespace = requireNonBlank(namespace, "namespace");
            podSelector = requireNonBlank(podSelector, "podSelector");
            bearerToken = requireNonBlank(bearerToken, "bearerToken");
            caCertificatePath = Objects.requireNonNull(caCertificatePath, "caCertificatePath");
            requestTimeout = positive(requestTimeout, "requestTimeout");
        }

        static Config fromEnvironment(Map<String, String> environment, Path serviceAccountDir) throws IOException {
            Objects.requireNonNull(environment, "environment");
            Path checkedServiceAccountDir = Objects.requireNonNull(serviceAccountDir, "serviceAccountDir");
            String host = requireNonBlank(environment.get("KUBERNETES_SERVICE_HOST"), "KUBERNETES_SERVICE_HOST");
            String port = environment.getOrDefault("KUBERNETES_SERVICE_PORT", "443");
            String namespace = Optional.ofNullable(environment.get("FULCRUM_ESCROW_NAMESPACE"))
                    .filter(value -> !value.isBlank())
                    .or(() -> Optional.ofNullable(environment.get("FULCRUM_WITNESS_NAMESPACE"))
                            .filter(value -> !value.isBlank()))
                    .orElseGet(() -> readRequired(checkedServiceAccountDir.resolve("namespace"), "service account namespace"));
            Path tokenPath = Path.of(environment.getOrDefault(
                    "FULCRUM_WITNESS_SERVICE_ACCOUNT_TOKEN_FILE",
                    checkedServiceAccountDir.resolve(DEFAULT_TOKEN_FILE).toString()));
            Path caPath = Path.of(environment.getOrDefault(
                    "FULCRUM_WITNESS_SERVICE_ACCOUNT_CA_FILE",
                    checkedServiceAccountDir.resolve(DEFAULT_CA_FILE).toString()));
            return new Config(
                    URI.create("https://" + host + ":" + port),
                    namespace,
                    requireNonBlank(environment.get("FULCRUM_ESCROW_POD_SELECTOR"), "FULCRUM_ESCROW_POD_SELECTOR"),
                    readRequired(tokenPath, "service account token"),
                    caPath,
                    Duration.ofSeconds(Long.parseLong(environment.getOrDefault("FULCRUM_WITNESS_KUBERNETES_TIMEOUT_SECONDS", "60"))));
        }

        private static String readRequired(Path path, String label) {
            try {
                return requireNonBlank(Files.readString(path, StandardCharsets.UTF_8), label);
            } catch (IOException exception) {
                throw new IllegalStateException("failed to read " + label + " from " + path, exception);
            }
        }
    }

    record PodSnapshot(
            String name,
            String uid,
            Instant creationTimestamp,
            Optional<Instant> deletionTimestamp,
            boolean running,
            boolean ready) {
        PodSnapshot {
            name = requireNonBlank(name, "name");
            uid = requireNonBlank(uid, "uid");
            creationTimestamp = Objects.requireNonNull(creationTimestamp, "creationTimestamp");
            deletionTimestamp = deletionTimestamp == null ? Optional.empty() : deletionTimestamp;
        }
    }

    record PodReplacementEvidence(
            String deletedPodName,
            String deletedPodUid,
            Instant deletedPodCreatedAt,
            Instant deletionRequestedAt,
            String replacementPodName,
            String replacementPodUid,
            Instant replacementPodCreatedAt) {
        PodReplacementEvidence {
            deletedPodName = requireNonBlank(deletedPodName, "deletedPodName");
            deletedPodUid = requireNonBlank(deletedPodUid, "deletedPodUid");
            deletedPodCreatedAt = Objects.requireNonNull(deletedPodCreatedAt, "deletedPodCreatedAt");
            deletionRequestedAt = Objects.requireNonNull(deletionRequestedAt, "deletionRequestedAt");
            replacementPodName = requireNonBlank(replacementPodName, "replacementPodName");
            replacementPodUid = requireNonBlank(replacementPodUid, "replacementPodUid");
            replacementPodCreatedAt = Objects.requireNonNull(replacementPodCreatedAt, "replacementPodCreatedAt");
            if (deletedPodUid.equals(replacementPodUid)) {
                throw new IllegalArgumentException("replacement pod UID must differ from deleted pod UID");
            }
        }

        String transcriptResult() {
            return "deletedPod=" + deletedPodName
                    + "|deletedUid=" + deletedPodUid
                    + "|deletedCreatedAt=" + deletedPodCreatedAt
                    + "|deletionRequestedAt=" + deletionRequestedAt
                    + "|replacementPod=" + replacementPodName
                    + "|replacementUid=" + replacementPodUid
                    + "|replacementCreatedAt=" + replacementPodCreatedAt;
        }
    }

    private record ReplacementTarget(String deletedUid) {
        private ReplacementTarget {
            deletedUid = requireNonBlank(deletedUid, "deletedUid");
        }
    }

    private static final class JdkKubernetesApi implements KubernetesApi {
        private final Config config;
        private final HttpClient client;

        private JdkKubernetesApi(Config config, HttpClient client) {
            this.config = Objects.requireNonNull(config, "config");
            this.client = Objects.requireNonNull(client, "client");
        }

        static JdkKubernetesApi open(Config config) throws IOException {
            Config checked = Objects.requireNonNull(config, "config");
            return new JdkKubernetesApi(
                    checked,
                    HttpClient.newBuilder()
                            .sslContext(sslContext(checked.caCertificatePath()))
                            .connectTimeout(checked.requestTimeout())
                            .build());
        }

        @Override
        public String listPods(String namespace, String selector) {
            URI uri = config.apiServer().resolve("/api/v1/namespaces/"
                    + encoded(namespace)
                    + "/pods?labelSelector="
                    + encoded(selector));
            return send(HttpRequest.newBuilder(uri).GET());
        }

        @Override
        public void deletePod(String namespace, String podName, String uid) {
            URI uri = config.apiServer().resolve("/api/v1/namespaces/"
                    + encoded(namespace)
                    + "/pods/"
                    + encoded(podName));
            String body = "{\"kind\":\"DeleteOptions\",\"apiVersion\":\"v1\",\"preconditions\":{\"uid\":\""
                    + json(uid)
                    + "\"}}";
            send(HttpRequest.newBuilder(uri)
                    .header("Content-Type", "application/json")
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
        }

        private String send(HttpRequest.Builder builder) {
            HttpRequest request = builder
                    .timeout(config.requestTimeout())
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + config.bearerToken())
                    .build();
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("Kubernetes API request failed with HTTP "
                            + response.statusCode()
                            + ": "
                            + response.body());
                }
                return response.body();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while calling Kubernetes API", exception);
            } catch (IOException exception) {
                throw new IllegalStateException("failed to call Kubernetes API", exception);
            }
        }

        private static SSLContext sslContext(Path caCertificatePath) throws IOException {
            try {
                CertificateFactory certificates = CertificateFactory.getInstance("X.509");
                X509Certificate ca = (X509Certificate) certificates.generateCertificate(
                        new ByteArrayInputStream(Files.readAllBytes(caCertificatePath)));
                KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
                trustStore.load(null, null);
                trustStore.setCertificateEntry("kubernetes", ca);
                TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trustManagers.init(trustStore);
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, trustManagers.getTrustManagers(), null);
                return context;
            } catch (Exception exception) {
                throw new IllegalStateException("failed to load Kubernetes CA certificate from " + caCertificatePath, exception);
            }
        }

        private static String encoded(String value) {
            return URLEncoder.encode(requireNonBlank(value, "value"), StandardCharsets.UTF_8);
        }

        private static String json(String value) {
            return requireNonBlank(value, "value").replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
