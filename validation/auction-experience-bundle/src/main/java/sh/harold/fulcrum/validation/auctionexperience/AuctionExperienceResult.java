package sh.harold.fulcrum.validation.auctionexperience;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record AuctionExperienceResult(
        AuctionMenuView menuView,
        List<String> messages,
        List<AuctionExperienceReceipt> receipts,
        Optional<String> refusalReason) {
    public AuctionExperienceResult {
        menuView = Objects.requireNonNull(menuView, "menuView");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        receipts = List.copyOf(Objects.requireNonNull(receipts, "receipts"));
        refusalReason = refusalReason == null ? Optional.empty() : refusalReason;
    }

    public static AuctionExperienceResult rendered(AuctionMenuView view, String message) {
        return new AuctionExperienceResult(view, List.of(message), List.of(), Optional.empty());
    }

    public static AuctionExperienceResult submitted(AuctionMenuView view, String message, AuctionExperienceReceipt receipt) {
        return new AuctionExperienceResult(view, List.of(message), List.of(receipt), Optional.empty());
    }

    public static AuctionExperienceResult blocked(AuctionMenuView view, String reason) {
        return new AuctionExperienceResult(view, List.of(reason), List.of(), Optional.of(reason));
    }
}
