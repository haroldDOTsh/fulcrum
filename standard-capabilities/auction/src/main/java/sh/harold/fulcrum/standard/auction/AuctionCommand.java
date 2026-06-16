package sh.harold.fulcrum.standard.auction;

import sh.harold.fulcrum.api.contract.CommandPayload;

public sealed interface AuctionCommand extends CommandPayload permits OpenAuction, PlaceAuctionBid {
    AuctionId auctionId();

    long expectedRevision();
}
