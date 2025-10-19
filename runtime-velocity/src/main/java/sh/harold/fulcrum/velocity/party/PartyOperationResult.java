package sh.harold.fulcrum.velocity.party;

import sh.harold.fulcrum.api.party.PartyInvite;
import sh.harold.fulcrum.api.party.PartySnapshot;

import java.util.Optional;

final class PartyOperationResult {
    private final boolean success;
    private final PartySnapshot party;
    private final PartyInvite invite;
    private final PartyErrorCode errorCode;
    private final String message;

    private PartyOperationResult(boolean success,
                                 PartySnapshot party,
                                 PartyInvite invite,
                                 PartyErrorCode errorCode,
                                 String message) {
        this.success = success;
        this.party = party;
        this.invite = invite;
        this.errorCode = errorCode;
        this.message = message;
    }

    static PartyOperationResult success(PartySnapshot party) {
        return new PartyOperationResult(true, party, null, PartyErrorCode.NONE, null);
    }

    static PartyOperationResult success(PartySnapshot party, PartyInvite invite) {
        return new PartyOperationResult(true, party, invite, PartyErrorCode.NONE, null);
    }

    static PartyOperationResult success() {
        return new PartyOperationResult(true, null, null, PartyErrorCode.NONE, null);
    }

    static PartyOperationResult failure(PartyErrorCode code, String message) {
        return new PartyOperationResult(false, null, null, code, message);
    }

    boolean isSuccess() {
        return success;
    }

    Optional<PartySnapshot> party() {
        return Optional.ofNullable(party);
    }

    Optional<PartyInvite> invite() {
        return Optional.ofNullable(invite);
    }

    PartyErrorCode errorCode() {
        return errorCode;
    }

    String message() {
        return message;
    }
}
