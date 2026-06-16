package sh.harold.fulcrum.standard.punishment;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;
import java.util.Optional;

public record PunishmentState(Optional<ActivePunishmentSnapshot> active) {
    public PunishmentState(ActivePunishmentSnapshot active) {
        this(Optional.of(Objects.requireNonNull(active, "active")));
    }

    public PunishmentState {
        active = active == null ? Optional.empty() : active;
    }

    public static PunishmentState empty() {
        return new PunishmentState(Optional.empty());
    }

    public String wireValue(Revision revision) {
        Objects.requireNonNull(revision, "revision");
        return active.map(snapshot -> snapshot.wireValue(revision.value()))
                .orElse("empty=true\nrevision=" + revision.value());
    }
}
