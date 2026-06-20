package sh.harold.fulcrum.validation.auctionexperience;

import java.util.Objects;
import java.util.Optional;

public record AuctionMenuAction(
        AuctionMenuActionType type,
        String label,
        boolean enabled,
        Optional<String> refusalReason) {
    public AuctionMenuAction {
        type = Objects.requireNonNull(type, "type");
        label = Names.requireNonBlank(label, "label");
        refusalReason = refusalReason == null ? Optional.empty() : refusalReason;
    }

    public static AuctionMenuAction enabled(AuctionMenuActionType type, String label) {
        return new AuctionMenuAction(type, label, true, Optional.empty());
    }

    public static AuctionMenuAction blocked(AuctionMenuActionType type, String label, String reason) {
        return new AuctionMenuAction(type, label, false, Optional.of(Names.requireNonBlank(reason, "reason")));
    }
}
