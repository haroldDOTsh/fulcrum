package sh.harold.fulcrum.host.paper;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostObservation;
import sh.harold.fulcrum.host.api.HostObservationFactory;
import sh.harold.fulcrum.host.api.HostSessionAttachment;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PaperSessionRewardReportCodecTest {
    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");
    private static final SubjectId SUBJECT = new SubjectId(UUID.fromString("11111111-1111-1111-1111-111111111111"));

    @Test
    void derivesRewardReportFromSessionAttachmentObservation() {
        HostObservation observation = HostObservationFactory.sessionAttached(new HostSessionAttachment(
                identity(),
                new RouteId("route-paper-reward"),
                SUBJECT,
                new SessionId("session-paper-reward"),
                trace(),
                NOW));

        PaperSessionRewardReport report = PaperSessionRewardReport.fromAttachmentObservation(observation);

        assertEquals(new InstanceId("instance-paper-reward"), report.instanceId());
        assertEquals(new SessionId("session-paper-reward"), report.sessionId());
        assertEquals(new RouteId("route-paper-reward"), report.routeId());
        assertEquals(SUBJECT, report.subjectId());
        assertEquals(trace().traceId(), report.traceEnvelope().traceId());
        assertEquals(NOW, report.occurredAt());
    }

    @Test
    void rewardReportRoundTripsThroughWireCodec() {
        PaperSessionRewardReport report = new PaperSessionRewardReport(
                new InstanceId("instance-paper-reward"),
                new SessionId("session-paper-reward"),
                new RouteId("route-paper-reward"),
                SUBJECT,
                trace(),
                NOW);

        PaperSessionRewardReport decoded = PaperSessionRewardReportCodec.decode(
                PaperSessionRewardReportCodec.encode(report));

        assertEquals(report, decoded);
    }

    private static HostInstanceIdentity identity() {
        return new HostInstanceIdentity(
                new InstanceId("instance-paper-reward"),
                HostInstanceKinds.PAPER,
                new PoolId("pool-paper-reward"),
                new MachineRef("machine-paper-reward"),
                new PrincipalId("principal-paper-reward"));
    }

    private static TraceEnvelope trace() {
        return new TraceEnvelope(
                "trace-paper-reward",
                "span-paper-reward",
                Optional.empty(),
                NOW,
                "paper-agent",
                new InstanceId("instance-paper-reward"));
    }
}
