package sh.harold.fulcrum.registry.punishment;

import sh.harold.fulcrum.api.punishment.PunishmentLadder;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class PlayerPunishmentState {

    private final UUID playerId;
    private final Map<PunishmentLadder, Integer> ladderRungs = new EnumMap<>(PunishmentLadder.class);
    private final LinkedList<UUID> punishmentHistory = new LinkedList<>();
    private final Set<UUID> activePunishments = ConcurrentHashMap.newKeySet();

    PlayerPunishmentState(UUID playerId) {
        this.playerId = playerId;
        for (PunishmentLadder ladder : PunishmentLadder.values()) {
            ladderRungs.put(ladder, 0);
        }
    }

    UUID getPlayerId() {
        return playerId;
    }

    int getRung(PunishmentLadder ladder) {
        return ladderRungs.getOrDefault(ladder, 0);
    }

    void setRung(PunishmentLadder ladder, int rung) {
        ladderRungs.put(ladder, Math.max(0, rung));
    }

    Map<PunishmentLadder, Integer> getRungSnapshot() {
        return new EnumMap<>(ladderRungs);
    }

    void appendHistory(UUID punishmentId) {
        punishmentHistory.add(punishmentId);
    }

    List<UUID> getPunishmentHistory() {
        return List.copyOf(punishmentHistory);
    }

    void addActive(UUID punishmentId) {
        activePunishments.add(punishmentId);
    }

    void removeActive(UUID punishmentId) {
        activePunishments.remove(punishmentId);
    }

    Set<UUID> getActivePunishments() {
        return Set.copyOf(activePunishments);
    }
}
