package sh.harold.fulcrum.registry.route.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.api.messagebus.messages.SlotLifecycleStatus;
import sh.harold.fulcrum.api.messagebus.messages.party.PartyReservationClaimedMessage;
import sh.harold.fulcrum.api.messagebus.messages.party.PartyReservationCreatedMessage;
import sh.harold.fulcrum.api.party.PartyReservationSnapshot;
import sh.harold.fulcrum.api.party.PartyReservationToken;
import sh.harold.fulcrum.registry.route.model.PartyReservationAllocation;
import sh.harold.fulcrum.registry.route.model.PlayerRequestContext;
import sh.harold.fulcrum.registry.route.store.RedisRoutingStore;
import sh.harold.fulcrum.registry.route.util.SlotSelectionRules;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.server.ServerRegistry;
import sh.harold.fulcrum.registry.slot.LogicalSlotRecord;
import sh.harold.fulcrum.registry.slot.SlotProvisionService;

import java.util.*;

/**
 * Encapsulates all party-reservation specific logic so that {@link sh.harold.fulcrum.registry.route.PlayerRoutingService}
 * can focus on orchestration.
 */
public final class PartyReservationCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(PartyReservationCoordinator.class);
    private final RedisRoutingStore routingStore;
    private final SlotProvisionService slotProvisionService;
    private final ServerRegistry serverRegistry;
    private final Callbacks callbacks;
    public PartyReservationCoordinator(RedisRoutingStore routingStore,
                                       SlotProvisionService slotProvisionService,
                                       ServerRegistry serverRegistry,
                                       Callbacks callbacks) {
        this.routingStore = Objects.requireNonNull(routingStore, "routingStore");
        this.slotProvisionService = Objects.requireNonNull(slotProvisionService, "slotProvisionService");
        this.serverRegistry = Objects.requireNonNull(serverRegistry, "serverRegistry");
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
    }

    public void handleReservationCreated(PartyReservationCreatedMessage message) {
        try {
            if (message == null) {
                return;
            }
            PartyReservationSnapshot reservation = message.getReservation();
            if (reservation == null) {
                LOGGER.warn("Received PartyReservationCreatedMessage without snapshot");
                return;
            }

            String reservationId = reservation.getReservationId();
            if (reservationId == null || reservationId.isBlank()) {
                LOGGER.warn("Party reservation snapshot missing reservationId");
                return;
            }

            if (getAllocation(reservationId) != null) {
                LOGGER.debug("Reservation {} already active; ignoring duplicate message", reservationId);
                return;
            }

            String familyId = message.getFamilyId();
            if (familyId == null || familyId.isBlank()) {
                LOGGER.warn("Party reservation {} missing family id", reservationId);
                return;
            }
            String variantId = message.getVariantId();

            Map<UUID, PartyReservationToken> tokens = reservation.getTokens();
            int partySize = tokens != null ? tokens.size() : 0;
            if (partySize <= 0) {
                LOGGER.warn("Party reservation {} has no participants", reservationId);
                return;
            }

            String targetServerId = reservation.getTargetServerId();
            if (targetServerId != null && !targetServerId.isBlank()) {
                RegisteredServerData targetServer = serverRegistry.getServer(targetServerId);
                if (targetServer == null) {
                    LOGGER.warn("Target server {} not found for party reservation {}", targetServerId, reservationId);
                } else {
                    LogicalSlotRecord targetSlot = findSlotOnServer(targetServer, familyId, variantId, partySize);
                    if (targetSlot != null) {
                        allocatePartyReservation(reservation, targetSlot, familyId, variantId);
                        return;
                    }
                    LOGGER.info("Target server {} cannot satisfy reservation {}; falling back to family queue",
                            targetServerId, reservationId);
                }
            }

            Optional<LogicalSlotRecord> slotOpt = findAvailableSlotForParty(familyId, variantId, partySize);
            if (slotOpt.isPresent()) {
                allocatePartyReservation(reservation, slotOpt.get(), familyId, variantId);
            } else {
                LOGGER.info("Queuing party reservation {} for family {} (variant {})", reservationId, familyId, variantId);
                routingStore.enqueuePartyReservation(
                        familyId,
                        new RedisRoutingStore.PartyReservationEntry(reservation, familyId, variantId, partySize, System.currentTimeMillis())
                );
                Map<String, String> provisionMetadata = new java.util.HashMap<>();
                provisionMetadata.put("partyReservationId", reservationId);
                if (variantId != null && !variantId.isBlank()) {
                    provisionMetadata.put("variant", variantId);
                }
                provisionMetadata.put("partySize", Integer.toString(partySize));
                callbacks.triggerProvision(familyId, provisionMetadata);
            }
        } catch (Exception exception) {
            LOGGER.error("Failed to handle party reservation message", exception);
        }
    }

    public boolean handlePartyPlayerRequest(PlayerRequestContext context, String reservationId) {
        PartyReservationAllocation allocation = getAllocation(reservationId);
        if (allocation == null || allocation.isReleased()) {
            routingStore.enqueuePendingReservationPlayer(reservationId, toQueueEntry(context));
            return false;
        }

        PartyReservationToken token = allocation.getTokenForPlayer(context.request().getPlayerId());
        if (token == null) {
            callbacks.sendDisconnect(context.request(), "party-token-missing");
            return true;
        }

        Map<String, String> metadata = context.request().getMetadata();
        String providedToken = metadata != null ? metadata.get("partyTokenId") : null;
        if (providedToken != null && !providedToken.equals(token.getTokenId())) {
            callbacks.sendDisconnect(context.request(), "party-token-mismatch");
            return true;
        }

        RegisteredServerData server = serverRegistry.getServer(allocation.serverId());
        if (server == null) {
            LOGGER.warn("Assigned server {} for reservation {} no longer available", allocation.serverId(), reservationId);
            routingStore.enqueuePendingReservationPlayer(reservationId, toQueueEntry(context));
            requeueAllocation(allocation);
            return true;
        }

        LogicalSlotRecord slot = server.getSlot(allocation.slotSuffix());
        if (slot == null || SlotLifecycleStatus.AVAILABLE != slot.getStatus()) {
            LOGGER.warn("Assigned slot {} for reservation {} unavailable; re-queuing", allocation.slotId(), reservationId);
            routingStore.enqueuePendingReservationPlayer(reservationId, toQueueEntry(context));
            requeueAllocation(allocation);
            return true;
        }

        if (!allocation.markDispatched(context.request().getPlayerId())) {
            LOGGER.debug("Player {} already dispatched for reservation {}", context.request().getPlayerName(), reservationId);
            return true;
        }

        savePartyAllocation(allocation);
        callbacks.dispatchWithReservation(context, slot, token.getTokenId(), true);
        return true;
    }

    public void handleRouteAck(String reservationId, UUID playerId) {
        PartyReservationAllocation allocation = getAllocation(reservationId);
        if (allocation == null) {
            return;
        }
        boolean completed = allocation.onRouteCompleted(playerId);
        if (completed) {
            LOGGER.debug("Party reservation {} fully routed", reservationId);
            releasePartyReservation(reservationId, allocation, true, "route-ack", Map.of(), Set.of());
        } else {
            savePartyAllocation(allocation);
        }
    }

    public void handleReservationClaimed(PartyReservationClaimedMessage message) {
        if (message == null) {
            return;
        }
        String reservationId = message.getReservationId();
        if (reservationId == null || reservationId.isBlank()) {
            return;
        }

        PartyReservationAllocation allocation = getAllocation(reservationId);
        if (allocation == null) {
            return;
        }

        PartyReservationAllocation.ClaimProgress progress = allocation.recordClaim(
                message.getPlayerId(),
                message.isSuccess(),
                message.getReason()
        );
        if (!message.isSuccess()) {
            LOGGER.warn("Reservation {} claim failed for player {} (reason: {})",
                    reservationId, message.getPlayerId(), message.getReason());
        }
        if (progress.complete()) {
            releasePartyReservation(reservationId, allocation, progress.success(), "claim",
                    progress.failures(), progress.missingPlayers());
        } else {
            savePartyAllocation(allocation);
        }
    }

    public void processPendingReservations(String familyId, LogicalSlotRecord slot) {
        if (familyId == null) {
            return;
        }

        List<RedisRoutingStore.PartyReservationEntry> deferred = new ArrayList<>();

        while (true) {
            Optional<RedisRoutingStore.PartyReservationEntry> entryOpt = routingStore.pollPartyReservation(familyId);
            if (entryOpt.isEmpty()) {
                break;
            }
            RedisRoutingStore.PartyReservationEntry entry = entryOpt.get();
            PartyReservationSnapshot reservation = entry.getReservation();
            Map<UUID, PartyReservationToken> tokens = reservation != null ? reservation.getTokens() : Map.of();
            int partySize = tokens != null ? tokens.size() : 0;
            if (partySize <= 0) {
                continue;
            }
            String variantId = entry.getVariantId();
            if (!SlotSelectionRules.variantMatches(slot, variantId)) {
                deferred.add(entry);
                continue;
            }
            if (!canSlotFitParty(slot, partySize)) {
                deferred.add(entry);
                continue;
            }
            allocatePartyReservation(reservation, slot, familyId, variantId);
            break;
        }

        for (int index = deferred.size() - 1; index >= 0; index--) {
            routingStore.enqueuePartyReservationFront(familyId, deferred.get(index));
        }
    }

    private void adjustPendingOccupancy(String slotId, int delta) {
        if (slotId == null || delta == 0) {
            return;
        }
        if (delta > 0) {
            for (int index = 0; index < delta; index++) {
                routingStore.incrementOccupancy(slotId);
            }
        } else {
            for (int index = 0; index < Math.abs(delta); index++) {
                routingStore.decrementOccupancy(slotId);
            }
        }
    }

    public List<PartyReservationAllocation> getActiveAllocations() {
        return routingStore.getPartyAllocations().stream()
                .map(record -> PartyReservationAllocation.fromEntry(record.entry()))
                .toList();
    }

    public PartyReservationAllocation getAllocation(String reservationId) {
        if (reservationId == null || reservationId.isBlank()) {
            return null;
        }
        return routingStore.getPartyAllocation(reservationId)
                .map(PartyReservationAllocation::fromEntry)
                .orElse(null);
    }

    public PartyReservationAllocation removeAllocation(String reservationId) {
        if (reservationId == null || reservationId.isBlank()) {
            return null;
        }
        return routingStore.removePartyAllocation(reservationId)
                .map(PartyReservationAllocation::fromEntry)
                .orElse(null);
    }

    public void savePartyAllocation(PartyReservationAllocation allocation) {
        if (allocation == null) {
            return;
        }
        routingStore.savePartyAllocation(allocation.reservationId(), allocation.toEntry());
    }

    public void releasePartyReservation(String reservationId,
                                        PartyReservationAllocation allocation,
                                        boolean success,
                                        String context,
                                        Map<UUID, String> failures,
                                        Set<UUID> missingPlayers) {
        if (allocation == null) {
            return;
        }
        PartyReservationAllocation stored = removeAllocation(reservationId);
        PartyReservationAllocation target = stored != null ? stored : allocation;
        adjustPendingOccupancy(target.slotId(), -target.partySize());
        callbacks.triggerProvision(target.familyId(), Map.of()); // ensure upstream can reconsider capacity
        routingStore.drainPendingReservationPlayers(reservationId);

        if (success) {
            LOGGER.info("Party reservation {} completed (context: {}) on server {}", reservationId, context, target.serverId());
        } else {
            LOGGER.warn("Party reservation {} released (context: {}) failures={} missing={}",
                    reservationId, context, failures, missingPlayers);
        }
    }

    private void allocatePartyReservation(PartyReservationSnapshot reservation,
                                          LogicalSlotRecord slot,
                                          String familyId,
                                          String variantId) {
        if (reservation == null) {
            return;
        }

        PartyReservationAllocation existing = getAllocation(reservation.getReservationId());
        if (existing != null && !existing.isReleased()) {
            LOGGER.debug("Reservation {} already allocated to slot {}", reservation.getReservationId(), existing.slotId());
            return;
        }

        int teamCount = SlotSelectionRules.resolveTeamCount(slot);
        int teamIndex = nextAvailableTeamIndex(slot, teamCount);
        if (teamCount > 0 && teamIndex < 0) {
            LOGGER.warn("Unable to assign party {} to slot {} because all {} teams are occupied",
                    reservation.getReservationId(), slot.getSlotId(), teamCount);
            reservation.setTargetServerId(null);
            reservation.setAssignedTeamIndex(null);
            routingStore.enqueuePartyReservationFront(
                    familyId,
                    new RedisRoutingStore.PartyReservationEntry(
                            reservation,
                            familyId,
                            variantId,
                            reservation.getTokens() != null ? reservation.getTokens().size() : 0,
                            System.currentTimeMillis()
                    )
            );
            Map<String, String> metadata = new java.util.HashMap<>();
            metadata.put("partyReservationId", reservation.getReservationId());
            if (variantId != null && !variantId.isBlank()) {
                metadata.put("variant", variantId);
            }
            metadata.put("partySize", Integer.toString(reservation.getTokens() != null ? reservation.getTokens().size() : 0));
            callbacks.triggerProvision(familyId, metadata);
            return;
        }

        reservation.setTargetServerId(slot.getServerId());
        reservation.setAssignedTeamIndex(teamIndex >= 0 ? teamIndex : null);

        PartyReservationAllocation allocation = new PartyReservationAllocation(reservation, slot, familyId, variantId, teamIndex);
        allocation.setAllocatedAt(System.currentTimeMillis());
        savePartyAllocation(allocation);
        adjustPendingOccupancy(slot.getSlotId(), allocation.partySize());
        callbacks.triggerProvision(familyId, Map.of()); // keep provisioning informed of new demand
        LOGGER.info("Allocated party reservation {} to slot {} on server {}",
                reservation.getReservationId(), slot.getSlotId(), slot.getServerId());
        processPendingPartyPlayerContexts(reservation.getReservationId(), allocation);
    }

    private void processPendingPartyPlayerContexts(String reservationId, PartyReservationAllocation allocation) {
        List<RedisRoutingStore.PlayerQueueEntry> pending = routingStore.drainPendingReservationPlayers(reservationId);
        for (RedisRoutingStore.PlayerQueueEntry entry : pending) {
            PlayerRequestContext context = fromQueueEntry(entry);
            if (context == null) {
                continue;
            }
            handlePartyPlayerRequest(context, reservationId);
        }
    }

    public void requeueAllocation(PartyReservationAllocation allocation) {
        if (allocation == null || allocation.isReleased()) {
            return;
        }

        PartyReservationAllocation stored = removeAllocation(allocation.reservationId());
        PartyReservationAllocation target = stored != null ? stored : allocation;
        target.release();
        adjustPendingOccupancy(target.slotId(), -target.partySize());
        routingStore.enqueuePartyReservationFront(
                target.familyId(),
                new RedisRoutingStore.PartyReservationEntry(
                        target.snapshot(),
                        target.familyId(),
                        target.variantId(),
                        target.partySize(),
                        System.currentTimeMillis()
                )
        );
        Map<String, String> metadata = new java.util.HashMap<>();
        metadata.put("partyReservationId", target.reservationId());
        if (target.variantId() != null && !target.variantId().isBlank()) {
            metadata.put("variant", target.variantId());
        }
        metadata.put("partySize", Integer.toString(target.partySize()));
        callbacks.triggerProvision(target.familyId(), metadata);
    }

    private Optional<LogicalSlotRecord> findAvailableSlotForParty(String familyId, String variantId, int partySize) {
        if (familyId == null) {
            return Optional.empty();
        }

        List<LogicalSlotRecord> candidates = new ArrayList<>();

        for (RegisteredServerData server : serverRegistry.getAllServers()) {
            for (LogicalSlotRecord slot : server.getSlots()) {
                if (!SlotSelectionRules.isSlotEligible(slot)) {
                    continue;
                }
                String slotFamily = slot.getMetadata().get("family");
                if (!familyId.equalsIgnoreCase(slotFamily)) {
                    continue;
                }
                if (!SlotSelectionRules.variantMatches(slot, variantId)) {
                    continue;
                }
                if (!canSlotFitParty(slot, partySize)) {
                    continue;
                }
                candidates.add(slot);
            }
        }

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        candidates.sort((a, b) -> Double.compare(fillRatio(b), fillRatio(a)));
        return Optional.of(candidates.get(0));
    }

    private LogicalSlotRecord findSlotOnServer(RegisteredServerData server,
                                               String familyId,
                                               String variantId,
                                               int partySize) {
        if (server == null) {
            return null;
        }

        return server.getSlots().stream()
                .filter(slot -> slot.getStatus() == SlotLifecycleStatus.AVAILABLE)
                .filter(slot -> familyId == null || familyId.equalsIgnoreCase(slot.getMetadata().get("family")))
                .filter(slot -> SlotSelectionRules.variantMatches(slot, variantId))
                .filter(slot -> canSlotFitParty(slot, partySize))
                .sorted((a, b) -> Double.compare(fillRatio(b), fillRatio(a)))
                .findFirst()
                .orElse(null);
    }

    private boolean canSlotFitParty(LogicalSlotRecord slot, int partySize) {
        if (SlotSelectionRules.remainingCapacity(slot, routingStore) < partySize) {
            return false;
        }
        int maxTeamSize = SlotSelectionRules.parsePositiveInt(slot.getMetadata(), "team.max");
        if (maxTeamSize > 0 && partySize > maxTeamSize) {
            return false;
        }
        int teamCount = SlotSelectionRules.resolveTeamCount(slot);
        if (teamCount > 0) {
            long existingParties = getActiveAllocations().stream()
                    .filter(allocation -> allocation != null
                            && allocation.slotId().equals(slot.getSlotId())
                            && !allocation.isReleased()
                            && allocation.teamIndex() >= 0)
                    .count();
            return existingParties < teamCount;
        }
        return true;
    }

    private int nextAvailableTeamIndex(LogicalSlotRecord slot, int teamCount) {
        if (teamCount <= 0) {
            return -1;
        }
        Set<Integer> used = new LinkedHashSet<>();
        for (PartyReservationAllocation allocation : getActiveAllocations()) {
            if (allocation != null
                    && slot.getSlotId().equals(allocation.slotId())
                    && allocation.teamIndex() >= 0) {
                used.add(allocation.teamIndex());
            }
        }
        for (int index = 0; index < teamCount; index++) {
            if (!used.contains(index)) {
                return index;
            }
        }
        return -1;
    }

    private RedisRoutingStore.PlayerQueueEntry toQueueEntry(PlayerRequestContext context) {
        return new RedisRoutingStore.PlayerQueueEntry(
                context.request(),
                context.createdAt(),
                context.lastEnqueuedAt(),
                context.currentSlotId(),
                context.blockedSlots() != null ? List.copyOf(context.blockedSlots()) : List.of(),
                context.variantId(),
                context.preferredSlotId(),
                context.isRejoin(),
                context.retries()
        );
    }

    private PlayerRequestContext fromQueueEntry(RedisRoutingStore.PlayerQueueEntry entry) {
        if (entry == null || entry.getRequest() == null) {
            return null;
        }
        Set<String> blocked = entry.getBlockedSlotIds() != null
                ? new LinkedHashSet<>(entry.getBlockedSlotIds())
                : Set.of();
        return new PlayerRequestContext(
                entry.getRequest(),
                new sh.harold.fulcrum.registry.route.model.BlockedSlotContext(entry.getCurrentSlotId(), blocked),
                entry.getVariantId(),
                entry.getPreferredSlotId(),
                entry.isRejoin(),
                entry.getCreatedAt(),
                entry.getLastEnqueuedAt(),
                entry.getRetries()
        );
    }

    private void releasePartyReservation(String reservationId,
                                         PartyReservationAllocation allocation,
                                         boolean success,
                                         String context,
                                         Map<UUID, String> failures) {
        releasePartyReservation(reservationId, allocation, success, context, failures, Set.of());
    }

    private double fillRatio(LogicalSlotRecord slot) {
        int max = slot.getMaxPlayers();
        if (max <= 0) {
            return 0D;
        }
        return (double) (slot.getOnlinePlayers() + routingStore.getOccupancy(slot.getSlotId())) / max;
    }

    public interface Callbacks {
        void dispatchWithReservation(PlayerRequestContext context,
                                     LogicalSlotRecord slot,
                                     String reservationToken,
                                     boolean preReserved);

        void sendDisconnect(sh.harold.fulcrum.api.messagebus.messages.PlayerSlotRequest request, String reason);

        void triggerProvision(String familyId, Map<String, String> metadata);

        void enqueueContext(PlayerRequestContext context);

        void retryRequest(PlayerRequestContext context, String reason);
    }

}
