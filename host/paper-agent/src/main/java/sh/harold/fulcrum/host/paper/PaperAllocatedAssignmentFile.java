package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class PaperAllocatedAssignmentFile {
    public static final String FILE_NAME = "fulcrum-allocated-assignment.properties";

    private PaperAllocatedAssignmentFile() {
    }

    public static Path defaultPath(Path paperServerRoot) {
        return Objects.requireNonNull(paperServerRoot, "paperServerRoot").resolve(FILE_NAME);
    }

    public static void write(Path file, PaperGameServerAssignment assignment, String traceId) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(assignment, "assignment");
        String checkedTraceId = PaperArtifactNames.requireNonBlank(traceId, "traceId");
        String content = "sessionId=" + assignment.sessionId().value() + "\n"
                + "slotId=" + assignment.slotId().value() + "\n"
                + "resolvedManifestId=" + assignment.resolvedManifestId().value() + "\n"
                + "traceId=" + checkedTraceId + "\n";
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            moveIntoPlace(temp, file);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not write Paper allocated assignment file " + file, exception);
        }
    }

    public static Optional<AllocatedAssignment> read(Path file) {
        Objects.requireNonNull(file, "file");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            Map<String, String> fields = fields(Files.readString(file, StandardCharsets.UTF_8));
            return Optional.of(new AllocatedAssignment(
                    new SessionId(required(fields, "sessionId")),
                    new SlotId(required(fields, "slotId")),
                    new ResolvedManifestId(required(fields, "resolvedManifestId")),
                    required(fields, "traceId")));
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read Paper allocated assignment file " + file, exception);
        }
    }

    public static SessionId sessionIdOrFallback(Path file, SessionId fallback) {
        Objects.requireNonNull(fallback, "fallback");
        return read(file).map(AllocatedAssignment::sessionId).orElse(fallback);
    }

    public static SessionId requireSessionId(Path file) {
        return read(file)
                .map(AllocatedAssignment::sessionId)
                .orElseThrow(() -> new IllegalStateException("Paper allocated assignment file is not available: " + file));
    }

    public static SlotId requireSlotId(Path file) {
        return read(file)
                .map(AllocatedAssignment::slotId)
                .orElseThrow(() -> new IllegalStateException("Paper allocated assignment file is not available: " + file));
    }

    public static ResolvedManifestId requireResolvedManifestId(Path file) {
        return read(file)
                .map(AllocatedAssignment::resolvedManifestId)
                .orElseThrow(() -> new IllegalStateException("Paper allocated assignment file is not available: " + file));
    }

    public static String requireTraceId(Path file) {
        return read(file)
                .map(AllocatedAssignment::traceId)
                .orElseThrow(() -> new IllegalStateException("Paper allocated assignment file is not available: " + file));
    }

    private static void moveIntoPlace(Path temp, Path file) throws IOException {
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Map<String, String> fields(String content) {
        Map<String, String> fields = new LinkedHashMap<>();
        String[] lines = Objects.requireNonNull(content, "content").split("\\R");
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("Malformed Paper allocated assignment line: " + line);
            }
            fields.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
        }
        return fields;
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Paper allocated assignment file missing " + key);
        }
        return value;
    }

    public record AllocatedAssignment(
            SessionId sessionId,
            SlotId slotId,
            ResolvedManifestId resolvedManifestId,
            String traceId) {
        public AllocatedAssignment {
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            slotId = Objects.requireNonNull(slotId, "slotId");
            resolvedManifestId = Objects.requireNonNull(resolvedManifestId, "resolvedManifestId");
            traceId = PaperArtifactNames.requireNonBlank(traceId, "traceId");
        }
    }
}
