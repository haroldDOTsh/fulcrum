package sh.harold.fulcrum.host.api;

import java.time.Instant;
import java.util.Objects;

public record HostMenuOpenRequest(
        String viewerId,
        String sessionId,
        String command,
        String correlationId,
        Instant occurredAt) {
    public HostMenuOpenRequest {
        viewerId = HostNames.requireNonBlank(viewerId, "viewerId");
        sessionId = HostNames.requireNonBlank(sessionId, "sessionId");
        command = HostNames.requireNonBlank(command, "command");
        correlationId = HostNames.requireNonBlank(correlationId, "correlationId");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
