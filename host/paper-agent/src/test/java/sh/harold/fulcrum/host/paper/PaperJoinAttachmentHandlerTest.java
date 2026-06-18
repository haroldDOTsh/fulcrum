package sh.harold.fulcrum.host.paper;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostObservation;
import sh.harold.fulcrum.host.api.HostObservationTypes;
import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.host.tick.HostMainThread;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PaperJoinAttachmentHandlerTest {
    private static final UUID PLAYER_UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");

    @Test
    void joinPublishesAttachObservationForAssignedSessionAndBedrockRoute() {
        RecordingObservationSink sink = new RecordingObservationSink();
        PaperJoinAttachmentHandler handler = handler(sink);

        HostObservation observation = handler.attach(new PaperJoiningSubject(PLAYER_UUID, "ExamplePlayer"));

        assertEquals(HostObservationTypes.SESSION_ATTACHED, observation.observationType());
        assertEquals("route-paper-" + compact(PLAYER_UUID), observation.attributes().get("routeId"));
        assertEquals(PLAYER_UUID.toString(), observation.attributes().get("subjectId"));
        assertEquals("session-lobby", observation.attributes().get("sessionId"));
        assertTrue(handler.state().attachedSubjects().contains(observationSubject()));
        assertEquals(List.of(observation), sink.observations());
    }

    @Test
    void quitPublishesDetachObservationForSameRouteSubjectAndSession() {
        RecordingObservationSink sink = new RecordingObservationSink();
        PaperJoinAttachmentHandler handler = handler(sink);

        HostObservation observation = handler.detach(new PaperJoiningSubject(PLAYER_UUID, "ExamplePlayer"));

        assertEquals(HostObservationTypes.SESSION_DETACHED, observation.observationType());
        assertEquals("route-paper-" + compact(PLAYER_UUID), observation.attributes().get("routeId"));
        assertEquals(PLAYER_UUID.toString(), observation.attributes().get("subjectId"));
        assertEquals("session-lobby", observation.attributes().get("sessionId"));
        assertFalse(handler.state().attachedSubjects().contains(observationSubject()));
        assertEquals(List.of(observation), sink.observations());
    }

    @Test
    void attachAndDetachFlowThroughPaperSessionRuntimeState() {
        RecordingObservationSink sink = new RecordingObservationSink();
        PaperJoinAttachmentHandler handler = handler(sink);

        handler.attach(new PaperJoiningSubject(PLAYER_UUID, "ExamplePlayer"));
        assertTrue(handler.state().attachedSubjects().contains(observationSubject()));

        handler.detach(new PaperJoiningSubject(PLAYER_UUID, "ExamplePlayer"));

        assertFalse(handler.state().attachedSubjects().contains(observationSubject()));
        assertEquals(
                List.of(HostObservationTypes.SESSION_ATTACHED, HostObservationTypes.SESSION_DETACHED),
                sink.observations().stream().map(HostObservation::observationType).toList());
    }

    @Test
    void joinUsesAllocatedSessionResolvedBeforeFirstAttach() {
        RecordingObservationSink sink = new RecordingObservationSink();
        PaperJoinAttachmentHandler handler = new PaperJoinAttachmentHandler(
                securityContext(),
                () -> new SessionId("session-lobby-allocated"),
                "route-paper-",
                sink,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new InlineHostMainThread());

        HostObservation observation = handler.attach(new PaperJoiningSubject(PLAYER_UUID, "ExamplePlayer"));

        assertEquals("session-lobby-allocated", observation.attributes().get("sessionId"));
        assertEquals(new SessionId("session-lobby-allocated"), handler.sessionId());
        assertTrue(handler.state().attachedSubjects().contains(observationSubject()));
    }

    private static PaperJoinAttachmentHandler handler(RecordingObservationSink sink) {
        return new PaperJoinAttachmentHandler(
                securityContext(),
                new SessionId("session-lobby"),
                "route-paper-",
                sink,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static HostSecurityContext securityContext() {
        return new HostSecurityContext(
                new HostInstanceIdentity(
                        new InstanceId("instance-paper-join"),
                        HostInstanceKinds.PAPER,
                        new PoolId("pool-lobby"),
                        new MachineRef("machine-a"),
                        new PrincipalId("principal-paper")),
                "service-account:paper",
                HostCredentialScope.of());
    }

    private static SubjectId observationSubject() {
        return new SubjectId(PLAYER_UUID);
    }

    private static String compact(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    private static final class RecordingObservationSink implements PaperObservationSink {
        private final List<HostObservation> observations = new ArrayList<>();

        @Override
        public void publish(HostObservation observation) {
            observations.add(observation);
        }

        private List<HostObservation> observations() {
            return List.copyOf(observations);
        }
    }

    private static final class InlineHostMainThread implements HostMainThread {
        @Override
        public boolean isMainThread() {
            return true;
        }

        @Override
        public void execute(Runnable task) {
            task.run();
        }
    }
}
