package sh.harold.fulcrum.standard.profile;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;
import java.util.Optional;

public record PlayerProfileReceipt(
        boolean accepted,
        Optional<PlayerProfileSnapshot> snapshot,
        Optional<Revision> revision,
        long fencingEpoch,
        String idempotencyKey,
        String commandId,
        Optional<String> rejectionReason) {
    public PlayerProfileReceipt {
        snapshot = snapshot == null ? Optional.empty() : snapshot;
        revision = revision == null ? Optional.empty() : revision;
        idempotencyKey = idempotencyKey == null ? "" : idempotencyKey.trim();
        commandId = commandId == null ? "" : commandId.trim();
        rejectionReason = rejectionReason == null ? Optional.empty() : rejectionReason;
        if (accepted && (snapshot.isEmpty() || revision.isEmpty())) {
            throw new IllegalArgumentException("accepted profile receipt requires snapshot and revision");
        }
        if (!accepted && rejectionReason.isEmpty()) {
            throw new IllegalArgumentException("rejected profile receipt requires reason");
        }
    }

    public static PlayerProfileReceipt accepted(
            PlayerProfileSnapshot snapshot,
            Revision revision,
            long fencingEpoch,
            String idempotencyKey,
            String commandId) {
        return new PlayerProfileReceipt(
                true,
                Optional.of(Objects.requireNonNull(snapshot, "snapshot")),
                Optional.of(Objects.requireNonNull(revision, "revision")),
                fencingEpoch,
                requireNonBlank(idempotencyKey, "idempotencyKey"),
                requireNonBlank(commandId, "commandId"),
                Optional.empty());
    }

    public static PlayerProfileReceipt rejected(String reason) {
        return new PlayerProfileReceipt(false, Optional.empty(), Optional.empty(), -1, "", "", Optional.of(requireNonBlank(reason, "reason")));
    }

    public String wireValue() {
        return accepted
                ? "accepted|%s|%s|%d|%s|%s".formatted(
                        snapshot.orElseThrow().subjectId().value(),
                        revision.orElseThrow().value(),
                        fencingEpoch,
                        idempotencyKey,
                        commandId)
                : "rejected|" + rejectionReason.orElseThrow();
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
