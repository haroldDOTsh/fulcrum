package sh.harold.fulcrum.core.session;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.EffectId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.SessionId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SessionReducerDeterminismTest {
    @Test
    void sameEventStreamProducesSameStateAndEffects() {
        List<ScoreChanged> events = List.of(
                event("event.score.changed", 3, 1),
                event("event.score.changed", 4, 2),
                event("event.score.changed", -1, 3));

        List<SessionReduction<ScoreState>> firstRun = apply(events);
        List<SessionReduction<ScoreState>> secondRun = apply(events);

        assertEquals(firstRun, secondRun);
        assertEquals(new ScoreState(6), firstRun.getLast().state());
    }

    private static List<SessionReduction<ScoreState>> apply(List<ScoreChanged> events) {
        SessionReducer<ScoreState, ScoreChanged> reducer = (state, event) -> {
            ScoreState nextState = new ScoreState(state.score() + event.delta());
            EffectEnvelope<ScoreboardPayload> effect = EffectEnvelope.issue(
                    new EffectId("effect-score-" + event.sequence()),
                    new IdempotencyKey("idem-score-" + event.sequence()),
                    EffectOrigin.session(event.sessionId()),
                    event.traceEnvelope(),
                    Optional.empty(),
                    new EffectTargetScope("session:" + event.sessionId().value()),
                    EffectClass.HOST_LOCAL,
                    new ScoreboardPayload("score:" + nextState.score()),
                    event.occurredAt(),
                    Optional.empty(),
                    EffectSettlementMode.HOST_INLINE);
            return SessionReduction.withEffects(nextState, List.of(effect));
        };

        ScoreState state = new ScoreState(0);
        List<SessionReduction<ScoreState>> reductions = new java.util.ArrayList<>();
        for (ScoreChanged event : events) {
            SessionReduction<ScoreState> reduction = reducer.reduce(state, event);
            reductions.add(reduction);
            state = reduction.state();
        }
        return List.copyOf(reductions);
    }

    private static ScoreChanged event(String eventType, int delta, int sequence) {
        return new ScoreChanged(
                eventType,
                new SessionId("session-determinism"),
                trace(),
                Instant.parse("2026-06-16T12:00:0" + sequence + "Z"),
                delta,
                sequence);
    }

    private static TraceEnvelope trace() {
        return new TraceEnvelope(
                "trace-determinism",
                "span-determinism",
                Optional.empty(),
                Instant.parse("2026-06-16T12:00:00Z"),
                "session-runtime-test",
                new InstanceId("instance-runtime-test"));
    }

    private record ScoreState(int score) {
    }

    private record ScoreChanged(
            String eventType,
            SessionId sessionId,
            TraceEnvelope traceEnvelope,
            Instant occurredAt,
            int delta,
            int sequence) implements SessionDomainEvent {
    }

    private record ScoreboardPayload(String value) implements EffectPayload {
        @Override
        public String payloadType() {
            return "host.scoreboard";
        }
    }
}
