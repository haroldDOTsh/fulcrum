package sh.harold.fulcrum.registry.slot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.messages.SlotProvisionCommand;
import sh.harold.fulcrum.registry.coordination.InMemoryRegistryCoordinationStore;
import sh.harold.fulcrum.registry.coordination.RegistryCoordinationStore;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.server.ServerRegistry;

import java.time.Duration;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Computes which backend should host a new logical slot and sends the provision command
 * (see docs/slot-family-discovery-notes.md for capacity math).
 */
public class SlotProvisionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SlotProvisionService.class);
    private static final Duration CAPACITY_TICKET_TTL = Duration.ofSeconds(60);

    private final ServerRegistry serverRegistry;
    private final MessageBus messageBus;
    private final RegistryCoordinationStore coordinationStore;

    public SlotProvisionService(ServerRegistry serverRegistry, MessageBus messageBus) {
        this(serverRegistry, messageBus, new InMemoryRegistryCoordinationStore());
    }

    public SlotProvisionService(ServerRegistry serverRegistry,
                                MessageBus messageBus,
                                RegistryCoordinationStore coordinationStore) {
        this.serverRegistry = serverRegistry;
        this.messageBus = messageBus;
        this.coordinationStore = coordinationStore;
    }

    /**
     * Attempt to provision a slot for the given family, returning the command that was dispatched.
     */
    public Optional<ProvisionResult> requestProvision(String familyId, Map<String, String> metadata) {
        if (familyId == null || familyId.isBlank()) {
            return Optional.empty();
        }

        List<RegisteredServerData> candidates = selectCandidates(familyId);
        if (candidates.isEmpty()) {
            LOGGER.debug("No provision candidate available for family {}", familyId);
            return Optional.empty();
        }

        for (RegisteredServerData candidate : candidates) {
            Optional<RegistryCoordinationStore.CapacityLease> lease = coordinationStore.reserveCapacity(
                candidate.getServerId(),
                familyId,
                candidate.getFamilyCapacity(familyId),
                CAPACITY_TICKET_TTL,
                metadata
            );
            if (lease.isEmpty()) {
                LOGGER.debug("Failed to acquire capacity lease on server {} for family {}",
                    candidate.getServerId(), familyId);
                continue;
            }

            if (!candidate.reserveFamilySlot(familyId)) {
                coordinationStore.releaseCapacity(lease.get());
                LOGGER.debug("Failed to reserve budget on server {} for family {} (race)",
                    candidate.getServerId(), familyId);
                continue;
            }

            SlotProvisionCommand command = new SlotProvisionCommand(candidate.getServerId(), familyId);
            Map<String, String> commandMetadata = new HashMap<>();
            if (metadata != null) {
                commandMetadata.putAll(metadata);
            }
            commandMetadata.put("capacityTicketId", lease.get().ticketId());
            commandMetadata.put("capacityTicketExpiresAt", String.valueOf(lease.get().expiresAtEpochMillis()));
            command.setMetadata(commandMetadata);

            try {
                messageBus.broadcast(ChannelConstants.getSlotProvisionChannel(candidate.getServerId()), command);
                int remaining = candidate.getAvailableFamilySlots(familyId);
                LOGGER.info("Provisioning {} on {} ({} slots remaining)",
                    familyId, candidate.getServerId(), remaining);
                return Optional.of(new ProvisionResult(candidate.getServerId(), familyId, remaining, command,
                    lease.get().ticketId()));
            } catch (Exception ex) {
                candidate.releaseFamilySlot(familyId);
                coordinationStore.releaseCapacity(lease.get());
                LOGGER.error("Failed to dispatch SlotProvisionCommand to {} for family {}", candidate.getServerId(), familyId, ex);
            }
        }

        LOGGER.warn("Failed to provision family {} on any candidate backend", familyId);
        return Optional.empty();
    }

    private List<RegisteredServerData> selectCandidates(String familyId) {
        return serverRegistry.getAllServers().stream()
            .filter(server -> isProvisionable(server, familyId))
            .sorted(Comparator
                .comparingInt((RegisteredServerData server) -> server.getAvailableFamilySlots(familyId))
                .thenComparing((a, b) -> Integer.compare(b.getPlayerCount(), a.getPlayerCount()))
                .thenComparing(RegisteredServerData::getServerId)
                .reversed())
            .toList();
    }

    private boolean isProvisionable(RegisteredServerData server, String familyId) {
        switch (server.getStatus()) {
            case RUNNING:
            case AVAILABLE:
                return server.supportsFamily(familyId) && server.getAvailableFamilySlots(familyId) > 0;
            default:
                return false;
        }
    }

    public record ProvisionResult(String serverId,
                                  String familyId,
                                  int remainingSlots,
                                  SlotProvisionCommand command,
                                  String capacityTicketId) {}
}
