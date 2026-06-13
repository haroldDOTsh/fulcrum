package sh.harold.fulcrum.registry.slot;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.api.messagebus.messages.SlotLifecycleStatus;
import sh.harold.fulcrum.api.messagebus.messages.SlotProvisionCommand;
import sh.harold.fulcrum.api.messagebus.messages.SlotStatusUpdateMessage;
import sh.harold.fulcrum.registry.allocation.IdAllocator;
import sh.harold.fulcrum.registry.coordination.InMemoryRegistryCoordinationStore;
import sh.harold.fulcrum.registry.messages.RegistrationRequest;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.server.ServerRegistry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotProvisionServiceTest {

    @Test
    void provisionCommandCarriesCapacityTicket() {
        ServerRegistry serverRegistry = new ServerRegistry(new IdAllocator(false));
        String serverId = serverRegistry.registerServer(registration("temp-mini-1"));
        RegisteredServerData server = serverRegistry.getServer(serverId);
        server.setStatus(RegisteredServerData.Status.AVAILABLE);
        server.updateSlotFamilyCapacities(Map.of("duels", 1));

        CapturingMessageBus messageBus = new CapturingMessageBus();
        SlotProvisionService service = new SlotProvisionService(
            serverRegistry,
            messageBus,
            new InMemoryRegistryCoordinationStore()
        );

        var result = service.requestProvision("duels", Map.of("mode", "classic"));

        assertTrue(result.isPresent());
        assertEquals(0, result.get().remainingSlots());
        assertEquals(1, messageBus.broadcasts.size());

        SlotProvisionCommand command = (SlotProvisionCommand) messageBus.broadcasts.get(0).payload();
        assertEquals("classic", command.getMetadata().get("mode"));
        assertTrue(command.getMetadata().containsKey("capacityTicketId"));
        assertTrue(command.getMetadata().containsKey("capacityTicketExpiresAt"));
    }

    @Test
    void terminalSlotStatusReleasesLocalCapacityReservation() {
        ServerRegistry serverRegistry = new ServerRegistry(new IdAllocator(false));
        String serverId = serverRegistry.registerServer(registration("temp-mini-1"));
        RegisteredServerData server = serverRegistry.getServer(serverId);
        server.setStatus(RegisteredServerData.Status.AVAILABLE);
        server.updateSlotFamilyCapacities(Map.of("duels", 1));

        SlotProvisionService service = new SlotProvisionService(
            serverRegistry,
            new CapturingMessageBus(),
            new InMemoryRegistryCoordinationStore()
        );

        assertTrue(service.requestProvision("duels", Map.of()).isPresent());
        assertEquals(0, server.getAvailableFamilySlots("duels"));

        SlotStatusUpdateMessage cooldown = new SlotStatusUpdateMessage(serverId, serverId + "-slot-1");
        cooldown.setSlotSuffix("slot-1");
        cooldown.setStatus(SlotLifecycleStatus.COOLDOWN);
        cooldown.setMetadata(Map.of("family", "duels"));

        serverRegistry.updateSlot(serverId, cooldown);
        serverRegistry.updateSlot(serverId, cooldown);

        assertEquals(1, server.getAvailableFamilySlots("duels"));
    }

    private RegistrationRequest registration(String tempId) {
        RegistrationRequest request = new RegistrationRequest();
        request.setTempId(tempId);
        request.setServerType("mini");
        request.setAddress("127.0.0.1");
        request.setPort(25565);
        request.setMaxCapacity(20);
        return request;
    }

    private static final class CapturingMessageBus implements MessageBus {
        private final List<Broadcast> broadcasts = new ArrayList<>();

        @Override
        public void broadcast(String type, Object payload) {
            broadcasts.add(new Broadcast(type, payload));
        }

        @Override
        public void send(String targetServerId, String type, Object payload) {
        }

        @Override
        public CompletableFuture<Object> request(String targetServerId, String type, Object payload, Duration timeout) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public void subscribe(String type, MessageHandler handler) {
        }

        @Override
        public void unsubscribe(String type, MessageHandler handler) {
        }
    }

    private record Broadcast(String type, Object payload) {
    }
}
