package sh.harold.fulcrum.validation.escrowe2e;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KubernetesEscrowPodDisruptorTest {
    private static final Instant NOW = Instant.parse("2026-06-20T12:00:00Z");
    private static final KubernetesEscrowPodDisruptor.Config CONFIG = new KubernetesEscrowPodDisruptor.Config(
            URI.create("https://kubernetes.default.svc"),
            "fulcrum-lobby",
            "sh.harold.fulcrum/role=auction-escrow-backend",
            "token",
            Path.of("ca.crt"),
            Duration.ofSeconds(1));

    @Test
    void deletesExactReadyPodUidAndWaitsForReadyReplacementUid() {
        FakeKubernetesApi api = new FakeKubernetesApi(
                podList(pod("escrow-before", "uid-before", "2026-06-20T11:59:00Z", true, false)),
                podList(),
                podList(pod("escrow-after", "uid-after", "2026-06-20T12:00:01Z", true, false)));
        KubernetesEscrowPodDisruptor disruptor = new KubernetesEscrowPodDisruptor(
                CONFIG,
                api,
                Clock.fixed(NOW, ZoneOffset.UTC));

        KubernetesEscrowPodDisruptor.PodReplacementEvidence evidence =
                disruptor.deleteReadyPodAndAwaitReplacement(Duration.ofSeconds(2));

        assertEquals("fulcrum-lobby", api.deletedNamespace);
        assertEquals("escrow-before", api.deletedPodName);
        assertEquals("uid-before", api.deletedUid);
        assertEquals("escrow-before", evidence.deletedPodName());
        assertEquals("uid-before", evidence.deletedPodUid());
        assertEquals(NOW, evidence.deletionRequestedAt());
        assertEquals("escrow-after", evidence.replacementPodName());
        assertEquals("uid-after", evidence.replacementPodUid());
        assertTrue(evidence.transcriptResult().contains("replacementUid=uid-after"));
    }

    @Test
    void refusesMultipleReadyPodsForSelector() {
        FakeKubernetesApi api = new FakeKubernetesApi(podList(
                pod("escrow-a", "uid-a", "2026-06-20T11:59:00Z", true, false),
                pod("escrow-b", "uid-b", "2026-06-20T11:59:01Z", true, false)));
        KubernetesEscrowPodDisruptor disruptor = new KubernetesEscrowPodDisruptor(
                CONFIG,
                api,
                Clock.fixed(NOW, ZoneOffset.UTC));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> disruptor.deleteReadyPodAndAwaitReplacement(Duration.ofSeconds(1)));

        assertTrue(exception.getMessage().contains("multiple Ready pods"));
        assertEquals(null, api.deletedPodName);
    }

    @Test
    void acceptsReplacementPodCreatedInSameSecondAsDeletionRequest() {
        FakeKubernetesApi api = new FakeKubernetesApi(
                podList(pod("escrow-before", "uid-before", "2026-06-20T11:59:00Z", true, false)),
                podList(pod("escrow-after", "uid-after", "2026-06-20T12:00:00Z", true, false)));
        KubernetesEscrowPodDisruptor disruptor = new KubernetesEscrowPodDisruptor(
                CONFIG,
                api,
                Clock.fixed(Instant.parse("2026-06-20T12:00:00.500Z"), ZoneOffset.UTC));

        KubernetesEscrowPodDisruptor.PodReplacementEvidence evidence =
                disruptor.deleteReadyPodAndAwaitReplacement(Duration.ofSeconds(1));

        assertEquals("uid-before", evidence.deletedPodUid());
        assertEquals("uid-after", evidence.replacementPodUid());
        assertEquals(Instant.parse("2026-06-20T12:00:00Z"), evidence.replacementPodCreatedAt());
    }

    @Test
    void ignoresNotReadyAndTerminatingPodsWhenSelectingTarget() {
        List<KubernetesEscrowPodDisruptor.PodSnapshot> pods = KubernetesEscrowPodDisruptor.pods(podList(
                pod("not-ready", "uid-not-ready", "2026-06-20T11:59:00Z", false, false),
                pod("terminating", "uid-terminating", "2026-06-20T11:59:01Z", true, true),
                pod("ready", "uid-ready", "2026-06-20T11:59:02Z", true, false)));

        assertEquals(3, pods.size());
        assertEquals("ready", pods.stream()
                .filter(KubernetesEscrowPodDisruptor.PodSnapshot::ready)
                .filter(KubernetesEscrowPodDisruptor.PodSnapshot::running)
                .filter(pod -> pod.deletionTimestamp().isEmpty())
                .findFirst()
                .orElseThrow()
                .name());
    }

    @Test
    void readsTopLevelPodStatusWhenNestedStatusFieldAppearsEarlier() {
        String pod = "{"
                + "\"metadata\":{"
                + "\"name\":\"ready\","
                + "\"uid\":\"uid-ready\","
                + "\"creationTimestamp\":\"2026-06-20T11:59:02Z\","
                + "\"annotations\":{\"example\":\"annotation-value\"},"
                + "\"status\":{\"phase\":\"Succeeded\",\"conditions\":[{\"type\":\"Ready\",\"status\":\"False\"}]}"
                + "},"
                + "\"status\":{"
                + "\"phase\":\"Running\","
                + "\"conditions\":[{\"type\":\"Ready\",\"status\":\"True\"}]"
                + "}"
                + "}";

        List<KubernetesEscrowPodDisruptor.PodSnapshot> pods =
                KubernetesEscrowPodDisruptor.pods(podList(pod));

        assertEquals(1, pods.size());
        assertEquals("ready", pods.getFirst().name());
        assertTrue(pods.getFirst().running());
        assertTrue(pods.getFirst().ready());
    }

    private static String podList(String... pods) {
        return "{\"kind\":\"PodList\",\"items\":[" + String.join(",", pods) + "]}";
    }

    private static String pod(
            String name,
            String uid,
            String createdAt,
            boolean ready,
            boolean terminating) {
        return "{"
                + "\"metadata\":{"
                + "\"name\":\"" + name + "\","
                + "\"uid\":\"" + uid + "\","
                + "\"creationTimestamp\":\"" + createdAt + "\""
                + (terminating ? ",\"deletionTimestamp\":\"2026-06-20T12:00:00Z\"" : "")
                + "},"
                + "\"status\":{"
                + "\"phase\":\"Running\","
                + "\"conditions\":[{\"type\":\"Ready\",\"status\":\"" + (ready ? "True" : "False") + "\"}]"
                + "}"
                + "}";
    }

    private static final class FakeKubernetesApi implements KubernetesEscrowPodDisruptor.KubernetesApi {
        private final List<String> listResponses;
        private int listCalls;
        private String deletedNamespace;
        private String deletedPodName;
        private String deletedUid;

        private FakeKubernetesApi(String... listResponses) {
            this.listResponses = new ArrayList<>(List.of(listResponses));
        }

        @Override
        public String listPods(String namespace, String selector) {
            assertEquals(CONFIG.namespace(), namespace);
            assertEquals(CONFIG.podSelector(), selector);
            if (listCalls >= listResponses.size()) {
                return listResponses.getLast();
            }
            return listResponses.get(listCalls++);
        }

        @Override
        public void deletePod(String namespace, String podName, String uid) {
            deletedNamespace = namespace;
            deletedPodName = podName;
            deletedUid = uid;
        }
    }
}
