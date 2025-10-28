package sh.harold.fulcrum.registry.punishment;

import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.Collection;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.data.impl.mongodb.MongoConnectionAdapter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PunishmentSnapshotWriter implements AutoCloseable {

    private final MongoConnectionAdapter mongoAdapter;
    private final DataAPI dataAPI;
    private final Collection playersCollection;
    private final Logger logger;

    public PunishmentSnapshotWriter(MongoConnectionAdapter adapter, DataAPI dataAPI, Logger logger) {
        this.mongoAdapter = adapter;
        this.dataAPI = dataAPI;
        this.playersCollection = dataAPI.players();
        this.logger = logger;
    }

    void writeSnapshot(PlayerPunishmentState state) {
        try {
            Document document = playersCollection.document(state.getPlayerId().toString());
            List<String> active = toStringList(state.getActivePunishments());
            List<String> history = toStringList(state.getPunishmentHistory());
            document.set("punishments.activePunishments", active);
            document.set("punishments.punishmentHistory", history);
            document.set("punishments.lastSyncedAt", Instant.now().toString());
        } catch (Exception ex) {
            logger.warn("Failed to update punishment snapshot for {}", state.getPlayerId(), ex);
        }
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
        try {
            mongoAdapter.close();
        } catch (Exception ignored) {
        }
    }
}
