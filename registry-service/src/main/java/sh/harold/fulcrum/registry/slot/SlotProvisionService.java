package sh.harold.fulcrum.registry.slot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.messages.SlotProvisionCommand;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.server.ServerRegistry;

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

    private final ServerRegistry serverRegistry;
    private final MessageBus messageBus;

    public SlotProvisionService(ServerRegistry serverRegistry, MessageBus messageBus) {
        this.serverRegistry = serverRegistry;
        this.messageBus = messageBus;
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
            if (!candidate.reserveFamilySlot(familyId)) {
                LOGGER.debug("Failed to reserve budget on server {} for family {} (race)",
                    candidate.getServerId(), familyId);
                continue;
            }

            SlotProvisionCommand command = new SlotProvisionCommand(candidate.getServerId(), familyId);
            if (metadata != null && !metadata.isEmpty()) {
                command.setMetadata(metadata);
            }

            try {
                messageBus.broadcast(ChannelConstants.getSlotProvisionChannel(candidate.getServerId()), command);
                int remaining = candidate.getAvailableFamilySlots(familyId);
                LOGGER.info("Provisioning {} on {} ({} slots remaining)",
                    familyId, candidate.getServerId(), remaining);
                return Optional.of(new ProvisionResult(candidate.getServerId(), familyId, remaining, command));
            } catch (Exception ex) {
                candidate.releaseFamilySlot(familyId);
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

    public record ProvisionResult(String serverId, String familyId, int remainingSlots, SlotProvisionCommand command) {}
}
