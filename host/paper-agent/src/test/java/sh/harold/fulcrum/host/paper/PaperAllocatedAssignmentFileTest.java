package sh.harold.fulcrum.host.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;
import sh.harold.fulcrum.core.manifest.ArtifactPin;
import sh.harold.fulcrum.core.manifest.ResolvedManifest;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PaperAllocatedAssignmentFileTest {
    @TempDir
    private Path tempDir;

    @Test
    void writesAndReadsAllocatedSessionSlotManifestAndTrace() {
        Path file = PaperAllocatedAssignmentFile.defaultPath(tempDir);

        PaperAllocatedAssignmentFile.write(file, assignment(), "trace-paper-session-lobby-shared");

        PaperAllocatedAssignmentFile.AllocatedAssignment read =
                PaperAllocatedAssignmentFile.read(file).orElseThrow();
        assertEquals(new SessionId("session-lobby-allocated"), read.sessionId());
        assertEquals(new SlotId("slot-lobby-allocated"), read.slotId());
        assertEquals(new ResolvedManifestId("manifest-lobby"), read.resolvedManifestId());
        assertEquals("trace-paper-session-lobby-shared", read.traceId());
    }

    @Test
    void missingFileFallsBackToConfiguredSession() {
        SessionId fallback = new SessionId("session-lobby-template");

        assertEquals(
                fallback,
                PaperAllocatedAssignmentFile.sessionIdOrFallback(tempDir.resolve("missing.properties"), fallback));
        assertTrue(PaperAllocatedAssignmentFile.read(tempDir.resolve("missing.properties")).isEmpty());
    }

    @Test
    void requireSessionIdFailsWhenAllocationFileIsMissing() {
        Path missing = tempDir.resolve("missing.properties");
        PaperAllocatedAssignmentFile.AssignmentUnavailableException exception = assertThrows(
                PaperAllocatedAssignmentFile.AssignmentUnavailableException.class,
                () -> PaperAllocatedAssignmentFile.requireSessionId(missing));

        assertTrue(exception.getMessage().contains("allocated assignment file"));
        assertEquals(missing, exception.file());
    }

    @Test
    void requireSlotIdFailsWhenAllocationFileIsMissing() {
        Path missing = tempDir.resolve("missing.properties");
        PaperAllocatedAssignmentFile.AssignmentUnavailableException exception = assertThrows(
                PaperAllocatedAssignmentFile.AssignmentUnavailableException.class,
                () -> PaperAllocatedAssignmentFile.requireSlotId(missing));

        assertTrue(exception.getMessage().contains("allocated assignment file"));
        assertEquals(missing, exception.file());
    }

    @Test
    void requireResolvedManifestIdFailsWhenAllocationFileIsMissing() {
        Path missing = tempDir.resolve("missing.properties");
        PaperAllocatedAssignmentFile.AssignmentUnavailableException exception = assertThrows(
                PaperAllocatedAssignmentFile.AssignmentUnavailableException.class,
                () -> PaperAllocatedAssignmentFile.requireResolvedManifestId(missing));

        assertTrue(exception.getMessage().contains("allocated assignment file"));
        assertEquals(missing, exception.file());
    }

    @Test
    void requireTraceIdFailsWhenAllocationFileIsMissing() {
        Path missing = tempDir.resolve("missing.properties");
        PaperAllocatedAssignmentFile.AssignmentUnavailableException exception = assertThrows(
                PaperAllocatedAssignmentFile.AssignmentUnavailableException.class,
                () -> PaperAllocatedAssignmentFile.requireTraceId(missing));

        assertTrue(exception.getMessage().contains("allocated assignment file"));
        assertEquals(missing, exception.file());
    }

    private static PaperGameServerAssignment assignment() {
        ArtifactPin artifact = new ArtifactPin(
                new ArtifactId("artifact-lobby-world"),
                "abc123",
                "lobby-world-v1");
        return new PaperGameServerAssignment(
                new ExperienceId("experience-lobby"),
                new SessionId("session-lobby-allocated"),
                new SlotId("slot-lobby-allocated"),
                new ResolvedManifest(
                        new ResolvedManifestId("manifest-lobby"),
                        new ArtifactId("artifact-paper-code"),
                        List.of(artifact),
                        List.of(),
                        "paper-host-runtime-v1"),
                artifact,
                "owner-token",
                Duration.ofMinutes(5));
    }
}
