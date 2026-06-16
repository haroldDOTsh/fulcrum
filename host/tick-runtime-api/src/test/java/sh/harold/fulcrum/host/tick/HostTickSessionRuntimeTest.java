package sh.harold.fulcrum.host.tick;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.core.session.EffectClass;
import sh.harold.fulcrum.core.session.EffectClassifier;
import sh.harold.fulcrum.core.session.EffectEnvelope;
import sh.harold.fulcrum.core.session.EffectPayload;
import sh.harold.fulcrum.core.session.EffectSettlementMode;
import sh.harold.fulcrum.core.session.SessionDomainEvent;
import sh.harold.fulcrum.core.session.SessionReduction;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HostTickSessionRuntimeTest {
    @Test
    void hostRuntimeFiltersHostEventsReducesMeaningfulEventsAndClassifiesEffects() {
        RecordingMainThread mainThread = new RecordingMainThread();
        List<EffectEnvelope<? extends EffectPayload>> hostHandled = new ArrayList<>();
        List<EffectEnvelope<? extends EffectPayload>> platformEmitted = new ArrayList<>();

        HostTickSessionRuntime<CounterState, HostSignal, RuntimeEvent> runtime = new HostTickSessionRuntime<>(
                new HostTickRuntimeContext(TickRuntimeFixtures.attachment()),
                new CounterState(0),
                new FilteringDomainEventBridge<>(
                        signal -> signal.meaningful,
                        signal -> new RuntimeEvent(
                                "fixture.runtime",
                                TickRuntimeFixtures.SESSION_ID,
                                TickRuntimeFixtures.trace(),
                                TickRuntimeFixtures.NOW,
                                signal.delta,
                                signal.platform)),
                (state, event) -> {
                    CounterState next = new CounterState(state.value() + event.delta());
                    EffectClass effectClass = event.platform ? EffectClass.CONTROL_PLANE : EffectClass.HOST_LOCAL;
                    EffectSettlementMode mode = event.platform ? EffectSettlementMode.ACCEPTED_ASYNC : EffectSettlementMode.HOST_INLINE;
                    return SessionReduction.withEffects(next, List.of(TickRuntimeFixtures.effect(effectClass, mode)));
                },
                new EffectClassifier(),
                new HostLocalEffectDispatcher(mainThread),
                hostHandled::add,
                platformEmitted::add);

        assertTrue(runtime.acceptHostEvent(new HostSignal(false, 10, false)).isEmpty());
        assertEquals(new CounterState(0), runtime.state());

        runtime.acceptHostEvent(new HostSignal(true, 2, false)).orElseThrow();
        assertEquals(new CounterState(2), runtime.state());
        assertEquals(1, hostHandled.size());
        assertEquals(0, platformEmitted.size());

        runtime.acceptHostEvent(new HostSignal(true, 3, true)).orElseThrow();
        assertEquals(new CounterState(5), runtime.state());
        assertEquals(1, hostHandled.size());
        assertEquals(1, platformEmitted.size());
    }

    @Test
    void runtimeRejectsDomainEventsForOtherSessions() {
        HostTickSessionRuntime<CounterState, HostSignal, RuntimeEvent> runtime = new HostTickSessionRuntime<>(
                new HostTickRuntimeContext(TickRuntimeFixtures.attachment()),
                new CounterState(0),
                ignored -> Optional.empty(),
                (state, event) -> {
                    throw new AssertionError("reducer must not run for a different Session");
                },
                new EffectClassifier(),
                new HostLocalEffectDispatcher(new RecordingMainThread()),
                ignored -> {
                },
                ignored -> {
                });

        RuntimeEvent event = new RuntimeEvent(
                "fixture.runtime",
                new sh.harold.fulcrum.api.kernel.SessionId("session-other"),
                TickRuntimeFixtures.trace(),
                TickRuntimeFixtures.NOW,
                1,
                false);

        assertThrows(IllegalArgumentException.class, () -> runtime.applyDomainEvent(event));
    }

    private record CounterState(int value) {
    }

    private record HostSignal(boolean meaningful, int delta, boolean platform) {
    }

    private record RuntimeEvent(
            String eventType,
            sh.harold.fulcrum.api.kernel.SessionId sessionId,
            sh.harold.fulcrum.api.contract.TraceEnvelope traceEnvelope,
            java.time.Instant occurredAt,
            int delta,
            boolean platform) implements SessionDomainEvent {
    }

    private static final class RecordingMainThread implements HostMainThread {
        @Override
        public boolean isMainThread() {
            return true;
        }

        @Override
        public void execute(Runnable task) {
            throw new AssertionError("main-thread test should execute inline");
        }
    }
}
