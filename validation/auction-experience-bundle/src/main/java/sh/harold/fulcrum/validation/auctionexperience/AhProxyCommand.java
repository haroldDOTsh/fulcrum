package sh.harold.fulcrum.validation.auctionexperience;

import java.time.Instant;
import java.util.Objects;

public record AhProxyCommand(
        String playerId,
        String rawCommand,
        String correlationId,
        Instant occurredAt) implements AuctionExperienceInput {
    public AhProxyCommand {
        playerId = Names.requireNonBlank(playerId, "playerId");
        rawCommand = Names.requireNonBlank(rawCommand, "rawCommand");
        correlationId = Names.requireNonBlank(correlationId, "correlationId");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
