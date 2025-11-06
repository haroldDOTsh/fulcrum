package sh.harold.fulcrum.registry.shutdown;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.api.messagebus.messages.ShutdownIntentMessage;
import sh.harold.fulcrum.api.messagebus.messages.ShutdownIntentUpdateMessage;
import sh.harold.fulcrum.registry.proxy.ProxyRegistry;
import sh.harold.fulcrum.registry.proxy.RegisteredProxyData;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.server.ServerRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Coordinates registry-issued shutdown intents, service acknowledgements, and evacuation tickets.
 */
public final class ShutdownIntentManager implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShutdownIntentManager.class);
    private static final Duration DEFAULT_EVICT_BUFFER = Duration.ofSeconds(3);
    private static final Duration DEFAULT_TICKET_BUFFER = Duration.ofSeconds(10);

    private final MessageBus messageBus;
    private final ServerRegistry serverRegistry;
    private final ProxyRegistry proxyRegistry;
    private final Map<String, IntentState> intents = new ConcurrentHashMap<>();
    private final Map<String, String> serviceToIntent = new ConcurrentHashMap<>();
    private final Set<String> evacuatingServers = ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final MessageHandler updateHandler = this::handleUpdate;

    public ShutdownIntentManager(MessageBus messageBus,
                                 ServerRegistry serverRegistry,
                                 ProxyRegistry proxyRegistry) {
        this.messageBus = Objects.requireNonNull(messageBus, "messageBus");
        this.serverRegistry = Objects.requireNonNull(serverRegistry, "serverRegistry");
        this.proxyRegistry = Objects.requireNonNull(proxyRegistry, "proxyRegistry");
        this.messageBus.subscribe(ChannelConstants.REGISTRY_SHUTDOWN_UPDATE, updateHandler);
    }

    public ShutdownIntent createIntent(Collection<ShutdownTarget> targets,
                                       int countdownSeconds,
                                       String reason,
                                       String backendTransferHint,
                                       boolean force) {
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("Shutdown intent requires at least one target");
        }

        String intentId = UUID.randomUUID().toString();
        ShutdownIntentMessage payload = new ShutdownIntentMessage();
        payload.setId(intentId);
        payload.setServices(targets.stream().map(ShutdownTarget::serviceId).toList());
        payload.setCountdownSeconds(countdownSeconds);
        payload.setReason(reason != null ? reason : "Unspecified");
        payload.setBackendTransferHint(backendTransferHint != null && !backendTransferHint.isBlank()
                ? backendTransferHint : "lobby");
        payload.setForce(force);
        payload.validate();

        IntentState state = new IntentState(payload, targets);
        intents.put(intentId, state);
        targets.forEach(target -> serviceToIntent.put(target.serviceId(), intentId));

        // Mark backend servers as evacuating immediately so routing stops using them.
        targets.forEach(target -> {
            if (target.type() == ServiceType.BACKEND) {
                evacuatingServers.add(target.serviceId());
                serverRegistry.updateStatus(target.serviceId(), RegisteredServerData.Status.EVACUATING.name());
            } else if (target.type() == ServiceType.PROXY) {
                proxyRegistry.updateProxyStatus(target.serviceId(), RegisteredProxyData.Status.EVACUATING);
            }
        });

        LOGGER.info("Broadcasting shutdown intent {} targeting {} service(s) (reason: {})",
                intentId, targets.size(), payload.getReason());
        messageBus.broadcast(ChannelConstants.REGISTRY_SHUTDOWN_INTENT, payload);
        return new ShutdownIntent(intentId, targets, countdownSeconds, reason, backendTransferHint, force, payload.getCreatedAt());
    }

    public boolean cancelIntent(String intentId, String operator) {
        IntentState state = intents.remove(intentId);
        if (state == null) {
            return false;
        }

        state.targets.values().forEach(target -> serviceToIntent.remove(target.serviceId));
        releaseEvacuatingServers(state);
        restoreProxyStatuses(state);

        ShutdownIntentMessage cancel = new ShutdownIntentMessage();
        cancel.setId(intentId);
        cancel.setServices(new ArrayList<>(state.targets.keySet()));
        cancel.setCountdownSeconds(0);
        cancel.setReason("Cancelled by " + (operator != null ? operator : "registry"));
        cancel.setBackendTransferHint(state.payload.getBackendTransferHint());
        cancel.setForce(state.payload.isForce());
        cancel.setCancelled(true);
        messageBus.broadcast(ChannelConstants.REGISTRY_SHUTDOWN_INTENT, cancel);

        LOGGER.info("Shutdown intent {} cancelled by {}", intentId, operator);
        return true;
    }

    public boolean isServerEvacuating(String serverId) {
        if (serverId == null) {
            return false;
        }
        return evacuatingServers.contains(serverId);
    }

    public Optional<ShutdownTicket> consumeTicket(UUID playerId, String intentId) {
        if (playerId == null || intentId == null) {
            return Optional.empty();
        }
        IntentState state = intents.get(intentId);
        if (state == null) {
            return Optional.empty();
        }
        ShutdownTicket ticket = state.tickets.remove(playerId);
        if (ticket == null) {
            return Optional.empty();
        }
        if (ticket.expiresAt() < System.currentTimeMillis()) {
            return Optional.empty();
        }
        return Optional.of(ticket);
    }

    public Collection<ShutdownIntent> getActiveIntents() {
        return intents.values().stream()
                .map(IntentState::toSnapshot)
                .collect(Collectors.toUnmodifiableList());
    }

    private void handleUpdate(MessageEnvelope envelope) {
        try {
            ShutdownIntentUpdateMessage message = objectMapper.treeToValue(envelope.payload(), ShutdownIntentUpdateMessage.class);
            message.validate();
            IntentState state = intents.get(message.getIntentId());
            if (state == null) {
                LOGGER.debug("Received shutdown update for unknown intent {}", message.getIntentId());
                return;
            }

            ServiceStatus service = state.targets.get(message.getServiceId());
            if (service == null) {
                LOGGER.debug("Service {} is not part of intent {}", message.getServiceId(), message.getIntentId());
                return;
            }

            service.phase = message.getPhase();
            service.lastUpdate = message.getTimestamp();

            if (message.getPhase() == ShutdownIntentUpdateMessage.Phase.EVACUATE
                    && !message.getPlayerIds().isEmpty()) {
                registerTickets(state, message.getServiceId(), message.getPlayerIds());
            }

            if (message.getPhase() == ShutdownIntentUpdateMessage.Phase.SHUTDOWN) {
                finalizeService(state, service.serviceId);
            }
        } catch (Exception exception) {
            LOGGER.warn("Failed to handle shutdown update message", exception);
        }
    }

    private void registerTickets(IntentState state, String serviceId, List<UUID> players) {
        if (players == null || players.isEmpty()) {
            return;
        }
        long expiresAt = state.ticketExpiryEpochMillis.get();
        for (UUID playerId : players) {
            if (playerId == null) {
                continue;
            }
            state.tickets.put(playerId, new ShutdownTicket(playerId, serviceId,
                    state.payload.getBackendTransferHint(), state.payload.isForce(), expiresAt));
        }
    }

    private void finalizeService(IntentState state, String serviceId) {
        serviceToIntent.remove(serviceId);
        ServiceStatus status = state.targets.get(serviceId);
        if (status != null) {
            if (status.type == ServiceType.BACKEND) {
                evacuatingServers.remove(serviceId);
                serverRegistry.updateStatus(serviceId, RegisteredServerData.Status.STOPPING.name());
            } else if (status.type == ServiceType.PROXY) {
                proxyRegistry.updateProxyStatus(serviceId, RegisteredProxyData.Status.UNAVAILABLE);
            }
        }

        boolean allComplete = state.targets.values().stream()
                .allMatch(target -> target.phase == ShutdownIntentUpdateMessage.Phase.SHUTDOWN);
        if (allComplete) {
            intents.remove(state.payload.getId());
            LOGGER.info("Shutdown intent {} completed", state.payload.getId());
        }
    }

    private void releaseEvacuatingServers(IntentState state) {
        state.targets.values().stream()
                .filter(target -> target.type == ServiceType.BACKEND)
                .forEach(target -> {
                    evacuatingServers.remove(target.serviceId);
                    serverRegistry.updateStatus(target.serviceId, RegisteredServerData.Status.AVAILABLE.name());
                });
    }

    private void restoreProxyStatuses(IntentState state) {
        state.targets.values().stream()
                .filter(target -> target.type == ServiceType.PROXY)
                .forEach(target -> proxyRegistry.updateProxyStatus(target.serviceId, RegisteredProxyData.Status.AVAILABLE));
    }

    @Override
    public void close() {
        messageBus.unsubscribe(ChannelConstants.REGISTRY_SHUTDOWN_UPDATE, updateHandler);
        intents.clear();
        serviceToIntent.clear();
        evacuatingServers.clear();
    }

    public enum ServiceType {
        PROXY,
        BACKEND
    }

    public record ShutdownTarget(String serviceId, ServiceType type) {
        public ShutdownTarget {
            Objects.requireNonNull(serviceId, "serviceId");
            Objects.requireNonNull(type, "type");
        }
    }

    public record ShutdownIntent(String id,
                                 Collection<ShutdownTarget> targets,
                                 int countdownSeconds,
                                 String reason,
                                 String backendTransferHint,
                                 boolean force,
                                 long createdAt) {
    }

    public record ShutdownTicket(UUID playerId, String serviceId, String fallbackFamily,
                                 boolean force, long expiresAt) {
    }

    private static final class IntentState {
        private final ShutdownIntentMessage payload;
        private final Map<String, ServiceStatus> targets;
        private final Map<UUID, ShutdownTicket> tickets = new ConcurrentHashMap<>();
        private final AtomicLong ticketExpiryEpochMillis;

        private IntentState(ShutdownIntentMessage payload, Collection<ShutdownTarget> targets) {
            this.payload = payload;
            Map<String, ServiceStatus> map = new LinkedHashMap<>();
            for (ShutdownTarget target : targets) {
                map.put(target.serviceId(), new ServiceStatus(target.serviceId(), target.type()));
            }
            this.targets = map;
            long expireAt = payload.getCreatedAt()
                    + Duration.ofSeconds(payload.getCountdownSeconds()).toMillis()
                    + DEFAULT_EVICT_BUFFER.toMillis()
                    + DEFAULT_TICKET_BUFFER.toMillis();
            this.ticketExpiryEpochMillis = new AtomicLong(expireAt);
        }

        private ShutdownIntent toSnapshot() {
            return new ShutdownIntent(payload.getId(),
                    targets.values().stream()
                            .map(status -> new ShutdownTarget(status.serviceId, status.type))
                            .toList(),
                    payload.getCountdownSeconds(),
                    payload.getReason(),
                    payload.getBackendTransferHint(),
                    payload.isForce(),
                    payload.getCreatedAt());
        }
    }

    private static final class ServiceStatus {
        private final String serviceId;
        private final ServiceType type;
        private volatile ShutdownIntentUpdateMessage.Phase phase = ShutdownIntentUpdateMessage.Phase.EVACUATE;
        private volatile long lastUpdate = Instant.now().toEpochMilli();

        private ServiceStatus(String serviceId, ServiceType type) {
            this.serviceId = serviceId;
            this.type = type;
        }
    }
}
