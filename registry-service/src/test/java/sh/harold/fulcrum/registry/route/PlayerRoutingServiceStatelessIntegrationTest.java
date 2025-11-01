package sh.harold.fulcrum.registry.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.api.messagebus.messages.*;
import sh.harold.fulcrum.registry.proxy.ProxyRegistry;
import sh.harold.fulcrum.registry.proxy.RegisteredProxyData;
import sh.harold.fulcrum.registry.redis.RedisIntegrationTestSupport;
import sh.harold.fulcrum.registry.redis.RedisManager;
import sh.harold.fulcrum.registry.route.store.RedisRoutingStore;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.server.ServerRegistry;
import sh.harold.fulcrum.registry.slot.LogicalSlotRecord;
import sh.harold.fulcrum.registry.slot.SlotProvisionService;
import sh.harold.fulcrum.registry.slot.store.RedisSlotStore;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.when;

class PlayerRoutingServiceStatelessIntegrationTest extends RedisIntegrationTestSupport {

    @Test
    @DisplayName("Queued players survive service restart when backed by Redis")
    void queuedPlayerSurvivesRestart() {
        try (RedisManager manager = newRedisManager()) {
            RedisSlotStore slotStore = new RedisSlotStore(manager);
            RedisRoutingStore routingStore = new RedisRoutingStore(manager);

            ServerRegistry serverRegistry = Mockito.mock(ServerRegistry.class);
            ProxyRegistry proxyRegistry = Mockito.mock(ProxyRegistry.class);
            SlotProvisionService slotProvisionService = Mockito.mock(SlotProvisionService.class);
            when(slotProvisionService.requestProvision(anyString(), any())).thenReturn(java.util.Optional.empty());

            AtomicReference<List<RegisteredServerData>> currentServers = new AtomicReference<>(List.of());
            when(serverRegistry.getAllServers()).thenAnswer(invocation -> currentServers.get());
            when(serverRegistry.getServer(anyString())).thenAnswer(invocation ->
                    currentServers.get().stream()
                            .filter(server -> server.getServerId().equals(invocation.getArgument(0)))
                            .findFirst()
                            .orElse(null));

            TestMessageBus firstBus = new TestMessageBus();
            when(proxyRegistry.getProxy("proxy-1")).thenReturn(new RegisteredProxyData("proxy-1", "127.0.0.1", 25565));

            PlayerRoutingService firstInstance = new PlayerRoutingService(firstBus, slotProvisionService, serverRegistry, proxyRegistry, slotStore, routingStore);
            firstInstance.initialize();

            PlayerSlotRequest request = new PlayerSlotRequest();
            request.setRequestId(UUID.randomUUID());
            request.setPlayerId(UUID.randomUUID());
            request.setPlayerName("QueueMe");
            request.setProxyId("proxy-1");
            request.setFamilyId("mini");
            request.setMetadata(Map.of());

            firstBus.publish(ChannelConstants.REGISTRY_PLAYER_REQUEST, request);

            assertThat(firstBus.broadcasts()).isEmpty();

            firstInstance.shutdown();

            RegisteredServerData serverData = new RegisteredServerData("server-1", "temp-server-1", "mini", "127.0.0.1", 25565, 100);
            serverData.setStatus(RegisteredServerData.Status.RUNNING);
            LogicalSlotRecord slotRecord = new LogicalSlotRecord("server-1A", "A", "server-1");
            slotRecord.setStatus(SlotLifecycleStatus.AVAILABLE);
            slotRecord.setMaxPlayers(16);
            slotRecord.setOnlinePlayers(0);
            slotRecord.replaceMetadata(Map.of("family", "mini"));
            serverData.putSlot(slotRecord);
            currentServers.set(List.of(serverData));

            when(serverRegistry.updateSlot(eq("server-1"), any(SlotStatusUpdateMessage.class))).thenAnswer(invocation -> {
                SlotStatusUpdateMessage update = invocation.getArgument(1);
                slotRecord.applyUpdate(update);
                return slotRecord;
            });

            TestMessageBus secondBus = new TestMessageBus();
            PlayerRoutingService secondInstance = new PlayerRoutingService(secondBus, slotProvisionService, serverRegistry, proxyRegistry, slotStore, routingStore);
            secondInstance.initialize();

            secondBus.broadcasts().stream()
                    .filter(b -> b.payload() instanceof PlayerReservationRequest)
                    .map(b -> (PlayerReservationRequest) b.payload())
                    .findFirst()
                    .ifPresent(reservation -> {
                        PlayerReservationResponse response = new PlayerReservationResponse();
                        response.setRequestId(reservation.getRequestId());
                        String serverId = reservation.getServerId() != null ? reservation.getServerId() : "server-1";
                        response.setServerId(serverId);
                        response.setAccepted(true);
                        response.setReservationToken(UUID.randomUUID().toString());
                        secondBus.publish(ChannelConstants.PLAYER_RESERVATION_RESPONSE, response);
                    });
            assertThat(waitForRoute(secondBus, request.getPlayerId()))
                    .as("player should be routed after service restart")
                    .isTrue();

            secondInstance.shutdown();
        }
    }

    private boolean waitForRoute(TestMessageBus bus, UUID playerId) {
        for (int attempt = 0; attempt < 20; attempt++) {
            boolean routed = bus.broadcasts().stream()
                    .anyMatch(b -> b.type().equals(ChannelConstants.getPlayerRouteChannel("proxy-1"))
                            && b.payload() instanceof PlayerRouteCommand command
                            && command.getPlayerId().equals(playerId));
            if (routed) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static final class TestMessageBus implements MessageBus {
        private final Map<String, MessageHandler> handlers = new ConcurrentHashMap<>();
        private final CopyOnWriteArrayList<Broadcast> broadcasts = new CopyOnWriteArrayList<>();
        private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        void publish(String type, Object payload) {
            MessageHandler handler = handlers.get(type);
            if (handler == null) {
                return;
            }
            MessageEnvelope envelope = new MessageEnvelope(
                    type,
                    "test",
                    null,
                    UUID.randomUUID(),
                    System.currentTimeMillis(),
                    1,
                    mapper.valueToTree(payload)
            );
            handler.handle(envelope);
        }

        @Override
        public void broadcast(String type, Object payload) {
            broadcasts.add(new Broadcast(type, payload));
        }

        @Override
        public void send(String targetServerId, String type, Object payload) {
            broadcasts.add(new Broadcast(type, payload));
        }

        @Override
        public CompletableFuture<Object> request(String targetServerId, String type, Object payload, Duration timeout) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void subscribe(String type, MessageHandler handler) {
            handlers.put(type, handler);
        }

        @Override
        public void unsubscribe(String type, MessageHandler handler) {
            handlers.remove(type, handler);
        }

        List<Broadcast> broadcasts() {
            return broadcasts;
        }

        record Broadcast(String type, Object payload) {
        }
    }
}
