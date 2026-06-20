package sh.harold.fulcrum.validation.auctionescrow;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;
import java.util.Optional;

public record AuctionEscrowReceipt(
        AuctionEscrowReceiptStatus status,
        Optional<String> auctionId,
        Optional<EscrowStatus> escrowStatus,
        Optional<Revision> revision,
        Optional<Long> fencingEpoch,
        Optional<Long> totalHeldMinor,
        Optional<Long> totalReleasedMinor,
        Optional<String> releasePlanFingerprint,
        Optional<String> rejectionReason) {
    public AuctionEscrowReceipt {
        status = Objects.requireNonNull(status, "status");
        auctionId = auctionId == null ? Optional.empty() : auctionId;
        escrowStatus = escrowStatus == null ? Optional.empty() : escrowStatus;
        revision = revision == null ? Optional.empty() : revision;
        fencingEpoch = fencingEpoch == null ? Optional.empty() : fencingEpoch;
        totalHeldMinor = totalHeldMinor == null ? Optional.empty() : totalHeldMinor;
        totalReleasedMinor = totalReleasedMinor == null ? Optional.empty() : totalReleasedMinor;
        releasePlanFingerprint = releasePlanFingerprint == null ? Optional.empty() : releasePlanFingerprint;
        rejectionReason = rejectionReason == null ? Optional.empty() : rejectionReason;
    }

    static AuctionEscrowReceipt accepted(EscrowSnapshot snapshot, Revision revision, long fencingEpoch) {
        Optional<ReleasePlan> releasePlan = snapshot.releasePlan();
        long totalReleased = releasePlan
                .map(plan -> plan.totalPayoutMinor() + plan.totalRefundedMinor())
                .orElse(0L);
        return new AuctionEscrowReceipt(
                AuctionEscrowReceiptStatus.ACCEPTED,
                Optional.of(snapshot.auctionId()),
                Optional.of(snapshot.status()),
                Optional.of(revision),
                Optional.of(fencingEpoch),
                Optional.of(snapshot.holds().stream().mapToLong(EscrowHold::amountMinor).sum()),
                Optional.of(totalReleased),
                releasePlan.map(ReleasePlan::fingerprint),
                Optional.empty());
    }

    static AuctionEscrowReceipt rejected(String reason) {
        return new AuctionEscrowReceipt(
                AuctionEscrowReceiptStatus.REJECTED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(EscrowNames.requireNonBlank(reason, "reason")));
    }

    String wireValue() {
        return "status=" + status
                + "|auctionId=" + auctionId.orElse("none")
                + "|escrowStatus=" + escrowStatus.map(Enum::name).orElse("none")
                + "|revision=" + revision.map(value -> Long.toString(value.value())).orElse("none")
                + "|fencingEpoch=" + fencingEpoch.map(Object::toString).orElse("none")
                + "|totalHeld=" + totalHeldMinor.map(Object::toString).orElse("none")
                + "|totalReleased=" + totalReleasedMinor.map(Object::toString).orElse("none")
                + "|releasePlan=" + releasePlanFingerprint.orElse("none")
                + "|rejection=" + rejectionReason.orElse("none");
    }
}
