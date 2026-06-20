package sh.harold.fulcrum.validation.auctionescrow;

import sh.harold.fulcrum.api.contract.CommandPayload;

public sealed interface AuctionEscrowCommand extends CommandPayload
        permits OpenEscrow, PlaceHold, SettleEscrow, CancelEscrow {
    String auctionId();
}
