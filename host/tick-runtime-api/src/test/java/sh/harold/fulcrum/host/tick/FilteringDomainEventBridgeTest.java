package sh.harold.fulcrum.host.tick;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.core.session.SessionDomainEvent;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FilteringDomainEventBridgeTest {
    @Test
    void bridgeOnlyMapsMeaningfulHostEvents() {
        FilteringDomainEventBridge<HostSignal, DomainSignal> bridge = new FilteringDomainEventBridge<>(
                signal -> signal.meaningful,
                signal -> new DomainSignal(
                        "fixture.meaningful",
                        new SessionId("session-bridge"),
                        trace(),
                        Instant.parse("2026-06-16T12:00:00Z"),
                        signal.value));

        assertTrue(bridge.translate(new HostSignal(false, 1)).isEmpty());

        DomainSignal signal = bridge.translate(new HostSignal(true, 7)).orElseThrow();
        assertEquals(7, signal.value());
    }

    private static TraceEnvelope trace() {
        return new TraceEnvelope(
                "trace-bridge",
                "span-bridge",
                Optional.empty(),
                Instant.parse("2026-06-16T12:00:00Z"),
                "tick-runtime-test",
                new InstanceId("instance-bridge"));
    }

    private record HostSignal(boolean meaningful, int value) {
    }

    private record DomainSignal(
            String eventType,
            SessionId sessionId,
            TraceEnvelope traceEnvelope,
            Instant occurredAt,
            int value) implements SessionDomainEvent {
    }
}
