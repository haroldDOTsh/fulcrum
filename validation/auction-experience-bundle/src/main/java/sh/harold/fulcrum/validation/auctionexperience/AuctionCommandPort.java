package sh.harold.fulcrum.validation.auctionexperience;

import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.validation.auctionescrow.AuctionEscrowCommand;

@FunctionalInterface
public interface AuctionCommandPort {
    AuctionExperienceReceipt append(AuthorityCommand<AuctionEscrowCommand> command);
}
