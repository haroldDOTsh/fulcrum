package sh.harold.fulcrum.validation.auctionexperience;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record AuctionMenuView(
        String title,
        Optional<String> auctionId,
        List<AuctionMenuAction> actions) {
    public AuctionMenuView {
        title = Names.requireNonBlank(title, "title");
        auctionId = auctionId == null ? Optional.empty() : auctionId.map(value -> Names.requireNonBlank(value, "auctionId"));
        actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
    }

    public static AuctionMenuView listingConfirmation(String auctionId, String itemRef, String currency) {
        return new AuctionMenuView(
                "Confirm Auction Listing",
                Optional.of(Names.requireNonBlank(auctionId, "auctionId")),
                List.of(
                        AuctionMenuAction.enabled(AuctionMenuActionType.CONFIRM_LISTING, "List " + Names.requireNonBlank(itemRef, "itemRef") + " for " + Names.requireNonBlank(currency, "currency")),
                        AuctionMenuAction.blocked(AuctionMenuActionType.SETTLE, "Settle", "listing is not open yet")));
    }

    public static AuctionMenuView auctionBoard(String auctionId) {
        return new AuctionMenuView(
                "Auction Board",
                Optional.of(Names.requireNonBlank(auctionId, "auctionId")),
                List.of(
                        AuctionMenuAction.enabled(AuctionMenuActionType.PLACE_BID, "Place Bid"),
                        AuctionMenuAction.enabled(AuctionMenuActionType.SETTLE, "Settle"),
                        AuctionMenuAction.enabled(AuctionMenuActionType.CANCEL, "Cancel")));
    }

    public static AuctionMenuView blocked(String reason) {
        return new AuctionMenuView(
                "Auction Blocked",
                Optional.empty(),
                List.of(AuctionMenuAction.blocked(AuctionMenuActionType.PLACE_BID, "Unavailable", reason)));
    }
}
