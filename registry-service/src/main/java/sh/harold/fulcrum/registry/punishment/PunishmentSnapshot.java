package sh.harold.fulcrum.registry.punishment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record PunishmentSnapshot(Instant lastSyncedAt, List<UUID> activePunishments, List<UUID> history) {
}
