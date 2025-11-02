package sh.harold.fulcrum.registry.punishment;

import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.Collection;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.punishment.PunishmentEffectType;

import java.time.Instant;
import java.util.*;

public final class PunishmentSnapshotWriter implements AutoCloseable {

    private final DataAPI dataAPI;
    private final Collection playersCollection;
    private final Logger logger;

    public PunishmentSnapshotWriter(DataAPI dataAPI, Logger logger) {
        this.dataAPI = dataAPI;
        this.playersCollection = dataAPI.players();
        this.logger = logger;
    }

    void writeSnapshot(UUID playerId, List<PunishmentRecord> activeRecords, List<UUID> historyIds) {
        try {
            Document document = playersCollection.document(playerId.toString());
            List<Map<String, Object>> active = toActiveList(activeRecords);
            List<String> history = toStringList(historyIds);
            document.set("punishments.activePunishments", active);
            document.set("punishments.punishmentHistory", history);
            document.set("punishments.lastSyncedAt", Instant.now().toString());
        } catch (Exception ex) {
            logger.warn("Failed to update punishment snapshot for {}", playerId, ex);
        }
    }

    private List<Map<String, Object>> toActiveList(List<PunishmentRecord> records) {
        List<Map<String, Object>> list = new ArrayList<>(records.size());
        for (PunishmentRecord record : records) {
            for (PunishmentRecordEffect effect : record.getEffects()) {
                if (!isEnforcement(effect.type())) {
                    continue;
                }
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("punishmentId", record.getPunishmentId().toString());
                map.put("type", effect.type().name());
                map.put("reason", record.getReason().getId());
                map.put("ladder", record.getLadder().name());
                map.put("issuedAt", record.getIssuedAt().toString());
                map.put("expiresAt", effect.expiresAt() != null ? effect.expiresAt().toString() : null);
                list.add(map);
            }
        }
        return list;
    }

    private boolean isEnforcement(PunishmentEffectType type) {
        return type == PunishmentEffectType.BAN
                || type == PunishmentEffectType.BLACKLIST
                || type == PunishmentEffectType.MUTE;
    }

    private List<String> toStringList(Iterable<UUID> ids) {
        List<String> list = new ArrayList<>();
        for (UUID id : ids) {
            list.add(id.toString());
        }
        return list;
    }

    @Override
    public void close() {
        // Mongo adapter lifecycle managed by registry service; no-op
    }
}
