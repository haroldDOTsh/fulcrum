package sh.harold.fulcrum.host.velocity;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class VelocityLoginGateBridgeServerTest {
    private static final SubjectId SUBJECT_ID = new SubjectId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
    private static final Instant NOW = Instant.parse("2026-06-17T23:00:00Z");

    @Test
    void localBridgeEvaluatesLoginGateRequestAndReturnsDecisionPayload() {
        AtomicReference<VelocityLoginGateRequest> observedRequest = new AtomicReference<>();
        try (VelocityLoginGateBridgeServer server = new VelocityLoginGateBridgeServer(
                URI.create("http://127.0.0.1:0/login-gate"),
                request -> {
                    observedRequest.set(request);
                    return VelocityLoginGateDecision.denied(request.subjectId(), "ban=evasion\nappeal at example.test");
                })) {
            server.start();
            VelocityLoginGateBridgeClient client = new VelocityLoginGateBridgeClient(server.uri());

            VelocityLoginGateDecision decision = client.evaluate(request());

            assertFalse(decision.allowed());
            assertEquals(SUBJECT_ID, decision.subjectId());
            assertEquals("ban=evasion\nappeal at example.test", decision.denialReason().orElseThrow());
            assertEquals(request(), observedRequest.get());
        }
    }

    @Test
    void admissionHandlerFailsClosedWhenBridgeIsUnavailable() {
        VelocityLoginAdmissionHandler handler = new VelocityLoginAdmissionHandler(
                request -> {
                    throw new IllegalStateException("bridge down");
                },
                "standard.punishment",
                Clock.fixed(NOW, ZoneOffset.UTC));

        VelocityLoginGateDecision decision = handler.evaluate(SUBJECT_ID, "Rich");

        assertFalse(decision.allowed());
        assertEquals(VelocityLoginAdmissionHandler.BRIDGE_UNAVAILABLE_REASON, decision.denialReason().orElseThrow());
    }

    @Test
    void bridgeCodecRoundTripsRequestAndDecision() {
        VelocityLoginGateRequest request = request();
        assertEquals(request, VelocityLoginGateBridgeCodec.decodeRequest(
                VelocityLoginGateBridgeCodec.encodeRequest(request)));

        VelocityLoginGateDecision decision = VelocityLoginGateDecision.denied(SUBJECT_ID, "deny=reason\nline2");
        assertEquals(decision, VelocityLoginGateBridgeCodec.decodeDecision(
                VelocityLoginGateBridgeCodec.encodeDecision(decision)));
    }

    private static VelocityLoginGateRequest request() {
        return new VelocityLoginGateRequest(
                SUBJECT_ID,
                "Rich=Player",
                "standard.punishment",
                NOW);
    }
}
