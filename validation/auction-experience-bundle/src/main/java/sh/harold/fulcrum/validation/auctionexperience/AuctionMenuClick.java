package sh.harold.fulcrum.validation.auctionexperience;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

public record AuctionMenuClick(
        String playerId,
        AuctionMenuActionType action,
        String auctionId,
        Optional<String> itemRef,
        Optional<String> currency,
        OptionalLong amountMinor,
        Optional<String> reason,
        String correlationId,
        Instant occurredAt) implements AuctionExperienceInput {
    public AuctionMenuClick {
        playerId = Names.requireNonBlank(playerId, "playerId");
        action = Objects.requireNonNull(action, "action");
        auctionId = Names.requireNonBlank(auctionId, "auctionId");
        itemRef = itemRef == null ? Optional.empty() : itemRef.map(value -> Names.requireNonBlank(value, "itemRef"));
        currency = currency == null ? Optional.empty() : currency.map(value -> Names.requireNonBlank(value, "currency"));
        amountMinor = amountMinor == null ? OptionalLong.empty() : amountMinor;
        reason = reason == null ? Optional.empty() : reason.map(value -> Names.requireNonBlank(value, "reason"));
        correlationId = Names.requireNonBlank(correlationId, "correlationId");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static AuctionMenuClick confirmListing(
            String playerId,
            String auctionId,
            String itemRef,
            String currency,
            String correlationId,
            Instant occurredAt) {
        return new AuctionMenuClick(
                playerId,
                AuctionMenuActionType.CONFIRM_LISTING,
                auctionId,
                Optional.of(itemRef),
                Optional.of(currency),
                OptionalLong.empty(),
                Optional.empty(),
                correlationId,
                occurredAt);
    }

    public static AuctionMenuClick placeBid(
            String playerId,
            String auctionId,
            long amountMinor,
            String currency,
            String correlationId,
            Instant occurredAt) {
        return new AuctionMenuClick(
                playerId,
                AuctionMenuActionType.PLACE_BID,
                auctionId,
                Optional.empty(),
                Optional.of(currency),
                OptionalLong.of(amountMinor),
                Optional.empty(),
                correlationId,
                occurredAt);
    }

    public static AuctionMenuClick settle(
            String playerId,
            String auctionId,
            String correlationId,
            Instant occurredAt) {
        return new AuctionMenuClick(
                playerId,
                AuctionMenuActionType.SETTLE,
                auctionId,
                Optional.empty(),
                Optional.empty(),
                OptionalLong.empty(),
                Optional.empty(),
                correlationId,
                occurredAt);
    }

    public static AuctionMenuClick cancel(
            String playerId,
            String auctionId,
            String reason,
            String correlationId,
            Instant occurredAt) {
        return new AuctionMenuClick(
                playerId,
                AuctionMenuActionType.CANCEL,
                auctionId,
                Optional.empty(),
                Optional.empty(),
                OptionalLong.empty(),
                Optional.of(reason),
                correlationId,
                occurredAt);
    }
}
