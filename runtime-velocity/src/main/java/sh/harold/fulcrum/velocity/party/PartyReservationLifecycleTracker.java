package sh.harold.fulcrum.velocity.party;

import sh.harold.fulcrum.api.messagebus.messages.party.PartyReservationClaimedMessage;
import sh.harold.fulcrum.api.party.PartyReservationSnapshot;
import sh.harold.fulcrum.api.party.PartyReservationToken;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Tracks outstanding party reservations so the proxy can determine when every token has been
 * consumed by a runtime server. This allows us to clear the active reservation metadata on the
 * party once the reservation lifecycle completes.
 */
final class PartyReservationLifecycleTracker {
    private final ConcurrentMap<String, ReservationState> reservations = new ConcurrentHashMap<>();

    void trackReservation(PartyReservationSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        String reservationId = snapshot.getReservationId();
        if (reservationId == null || reservationId.isBlank()) {
            return;
        }

        UUID partyId = snapshot.getPartyId();
        Map<UUID, PartyReservationToken> tokens = snapshot.getTokens();
        Set<UUID> expected = tokens != null ? new HashSet<>(tokens.keySet()) : Set.of();
        String serverId = snapshot.getTargetServerId();

        reservations.compute(reservationId, (id, existing) -> {
            if (existing == null) {
                return new ReservationState(id, partyId, expected, serverId);
            }
            existing.refresh(partyId, expected, serverId);
            return existing;
        });
    }

    Optional<ReservationCompletion> recordClaim(PartyReservationClaimedMessage message) {
        if (message == null) {
            return Optional.empty();
        }
        String reservationId = message.getReservationId();
        if (reservationId == null || reservationId.isBlank()) {
            return Optional.empty();
        }
        UUID partyId = message.getPartyId();
        UUID playerId = message.getPlayerId();
        boolean success = message.isSuccess();
        String serverId = message.getServerId();
        String reason = message.getReason();

        ReservationState state = reservations.computeIfAbsent(reservationId,
                id -> new ReservationState(id, partyId, Set.of(), serverId));
        ReservationCompletion completion = state.recordClaim(playerId, success, reason, serverId);
        if (completion != null) {
            reservations.remove(reservationId, state);
            return Optional.of(completion);
        }
        return Optional.empty();
    }

    void forget(String reservationId) {
        if (reservationId == null || reservationId.isBlank()) {
            return;
        }
        reservations.remove(reservationId);
    }

    record ReservationCompletion(String reservationId, UUID partyId, String serverId, Set<UUID> expectedPlayers,
                                 Set<UUID> successfulPlayers, Map<UUID, String> failedPlayers) {
            ReservationCompletion(String reservationId,
                                  UUID partyId,
                                  String serverId,
                                  Set<UUID> expectedPlayers,
                                  Set<UUID> successfulPlayers,
                                  Map<UUID, String> failedPlayers) {
                this.reservationId = reservationId;
                this.partyId = partyId;
                this.serverId = serverId;
                this.expectedPlayers = Collections.unmodifiableSet(new HashSet<>(expectedPlayers));
                this.successfulPlayers = Collections.unmodifiableSet(new HashSet<>(successfulPlayers));
                this.failedPlayers = Collections.unmodifiableMap(new HashMap<>(failedPlayers));
            }

            boolean allSuccessful() {
                return failedPlayers.isEmpty()
                        && !expectedPlayers.isEmpty()
                        && successfulPlayers.containsAll(expectedPlayers);
            }

            Set<UUID> missingPlayers() {
                Set<UUID> missing = new HashSet<>(expectedPlayers);
                missing.removeAll(successfulPlayers);
                missing.removeAll(failedPlayers.keySet());
                return missing;
            }
        }

    private static final class ReservationState {
        private final String reservationId;
        private final Set<UUID> expectedPlayers;
        private final Set<UUID> successfulPlayers = ConcurrentHashMap.newKeySet();
        private final ConcurrentMap<UUID, String> failedPlayers = new ConcurrentHashMap<>();
        private UUID partyId;
        private volatile String serverId;

        ReservationState(String reservationId,
                         UUID partyId,
                         Collection<UUID> expectedPlayers,
                         String serverId) {
            this.reservationId = reservationId;
            this.partyId = partyId;
            this.expectedPlayers = ConcurrentHashMap.newKeySet();
            refresh(partyId, expectedPlayers, serverId);
        }

        void refresh(UUID newPartyId, Collection<UUID> expected, String serverId) {
            if (newPartyId != null) {
                this.partyId = newPartyId;
            }
            if (serverId != null && !serverId.isBlank()) {
                this.serverId = serverId;
            }
            if (expected != null && !expected.isEmpty()) {
                expectedPlayers.clear();
                expected.forEach(playerId -> {
                    if (playerId != null) {
                        expectedPlayers.add(playerId);
                    }
                });
            }
        }

        ReservationCompletion recordClaim(UUID playerId,
                                          boolean success,
                                          String reason,
                                          String serverId) {
            if (serverId != null && !serverId.isBlank()) {
                this.serverId = serverId;
            }
            if (playerId != null) {
                expectedPlayers.add(playerId);
                if (success) {
                    successfulPlayers.add(playerId);
                    failedPlayers.remove(playerId);
                } else {
                    successfulPlayers.remove(playerId);
                    failedPlayers.put(playerId, reason != null ? reason : "unknown");
                }
            }

            if (partyId == null) {
                return null;
            }

            if (isComplete()) {
                return new ReservationCompletion(
                        reservationId,
                        partyId,
                        serverId != null ? serverId : this.serverId,
                        expectedPlayers,
                        successfulPlayers,
                        failedPlayers
                );
            }
            return null;
        }

        private boolean isComplete() {
            if (expectedPlayers.isEmpty()) {
                return false;
            }
            int processed = successfulPlayers.size() + failedPlayers.size();
            return processed >= expectedPlayers.size();
        }
    }
}
