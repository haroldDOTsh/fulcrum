package sh.harold.fulcrum.registry.punishment;

import sh.harold.fulcrum.api.punishment.PunishmentLadder;
import sh.harold.fulcrum.api.punishment.PunishmentReason;
import sh.harold.fulcrum.api.punishment.PunishmentStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

final class PunishmentRecord {

    private final UUID punishmentId;
    private final UUID playerId;
    private final PunishmentReason reason;
    private final PunishmentLadder ladder;
    private final UUID staffId;
    private final String staffName;
    private final String playerName;
    private final int rungBefore;
    private final int rungAfter;
    private final Instant issuedAt;
    private final List<PunishmentRecordEffect> effects;
    private PunishmentStatus status;
    private Instant updatedAt;

    PunishmentRecord(UUID punishmentId,
                     UUID playerId,
                     String playerName,
                     PunishmentReason reason,
                     PunishmentLadder ladder,
                     int rungBefore,
                     int rungAfter,
                     UUID staffId,
                     String staffName,
                     Instant issuedAt,
                     List<PunishmentRecordEffect> effects) {
        this.punishmentId = punishmentId;
        this.playerId = playerId;
        this.playerName = playerName;
        this.reason = reason;
        this.ladder = ladder;
        this.rungBefore = rungBefore;
        this.rungAfter = rungAfter;
        this.staffId = staffId;
        this.staffName = staffName;
        this.issuedAt = issuedAt;
        this.status = PunishmentStatus.ACTIVE;
        this.updatedAt = issuedAt;
        this.effects = Collections.unmodifiableList(new ArrayList<>(effects));
    }

    UUID getPunishmentId() {
        return punishmentId;
    }

    UUID getPlayerId() {
        return playerId;
    }

    String getPlayerName() {
        return playerName;
    }

    PunishmentReason getReason() {
        return reason;
    }

    PunishmentLadder getLadder() {
        return ladder;
    }

    UUID getStaffId() {
        return staffId;
    }

    String getStaffName() {
        return staffName;
    }

    int getRungBefore() {
        return rungBefore;
    }

    int getRungAfter() {
        return rungAfter;
    }

    Instant getIssuedAt() {
        return issuedAt;
    }

    PunishmentStatus getStatus() {
        return status;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    void setStatus(PunishmentStatus status, Instant updatedAt) {
        this.status = status;
        this.updatedAt = updatedAt;
    }

    List<PunishmentRecordEffect> getEffects() {
        return effects;
    }
}
