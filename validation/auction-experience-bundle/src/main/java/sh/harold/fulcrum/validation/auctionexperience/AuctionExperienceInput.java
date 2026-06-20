package sh.harold.fulcrum.validation.auctionexperience;

import java.time.Instant;

public sealed interface AuctionExperienceInput permits AhProxyCommand, AuctionMenuClick {
    String playerId();

    String correlationId();

    Instant occurredAt();
}
