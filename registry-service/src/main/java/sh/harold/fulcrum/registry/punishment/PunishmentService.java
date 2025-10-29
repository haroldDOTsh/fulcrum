package sh.harold.fulcrum.registry.punishment;

import org.slf4j.Logger;
import sh.harold.fulcrum.api.messagebus.*;
import sh.harold.fulcrum.api.messagebus.messages.punishment.PunishmentAppliedMessage;
import sh.harold.fulcrum.api.messagebus.messages.punishment.PunishmentCommandMessage;
import sh.harold.fulcrum.api.messagebus.messages.punishment.PunishmentExpireRequestMessage;
import sh.harold.fulcrum.api.messagebus.messages.punishment.PunishmentStatusMessage;
import sh.harold.fulcrum.api.punishment.*;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public final class PunishmentService implements AutoCloseable {

    private final MessageBus messageBus;
    private final Logger logger;
    private final ScheduledExecutorService scheduler;
    private final PunishmentRepository repository;
    private final PunishmentSnapshotWriter snapshotWriter;
    private final ConcurrentMap<UUID, PunishmentRecord> records = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, PlayerPunishmentState> playerStates = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ScheduledFuture<?>> scheduledExpiryTasks = new ConcurrentHashMap<>();
    private final MessageHandler commandHandler;
    private final MessageHandler expiryHandler;

    public PunishmentService(MessageBus messageBus,
                             Logger logger,
                             ScheduledExecutorService scheduler,
                             PunishmentRepository repository,
                             PunishmentSnapshotWriter snapshotWriter) {
        this.messageBus = messageBus;
        this.logger = logger;
        this.scheduler = scheduler;
        this.repository = repository;
        this.snapshotWriter = snapshotWriter;
        this.commandHandler = this::handleCommand;
        this.expiryHandler = this::handleExpiry;
        this.messageBus.subscribe(ChannelConstants.REGISTRY_PUNISHMENT_COMMAND, commandHandler);
        this.messageBus.subscribe(ChannelConstants.REGISTRY_PUNISHMENT_EXPIRE_REQUEST, expiryHandler);
        logger.info("PunishmentService subscribed to {}", ChannelConstants.REGISTRY_PUNISHMENT_COMMAND);
        loadExistingData();
    }

    private void loadExistingData() {
        List<PunishmentRecord> existing = repository.loadAllPunishments();
        for (PunishmentRecord record : existing) {
            records.put(record.getPunishmentId(), record);
            PlayerPunishmentState state = playerStates.computeIfAbsent(record.getPlayerId(), PlayerPunishmentState::new);
            synchronized (state) {
                state.appendHistory(record.getPunishmentId());
                if (record.getStatus() == PunishmentStatus.ACTIVE && record.getEffects().stream().anyMatch(effect -> isEnforcementType(effect.type()))) {
                    state.addActive(record.getPunishmentId());
                }
            }
        }

        playerStates.values().forEach(this::recomputeRungs);

        existing.stream()
                .filter(record -> record.getStatus() == PunishmentStatus.ACTIVE)
                .forEach(this::scheduleExpiryTasks);

        playerStates.values().forEach(state -> snapshotWriter.writeSnapshot(state, collectActiveRecords(state)));
    }

    private void handleCommand(MessageEnvelope envelope) {
        PunishmentCommandMessage command;
        try {
            command = MessageTypeRegistry.getInstance()
                    .deserializeToClass(envelope.payload(), PunishmentCommandMessage.class);
        } catch (Exception ex) {
            logger.warn("Failed to deserialize punishment command", ex);
            return;
        }
        if (command == null) {
            return;
        }

        try {
            processCommand(command);
        } catch (Exception ex) {
            logger.error("Failed to process punishment command {}", command.getCommandId(), ex);
        }
    }

    private void processCommand(PunishmentCommandMessage command) {
        PunishmentReason reason = command.getReason();
        if (reason == null) {
            throw new IllegalStateException("Punishment reason missing");
        }

        UUID playerId = command.getPlayerId();
        PlayerPunishmentState state = playerStates.computeIfAbsent(playerId, PlayerPunishmentState::new);

        PunishmentOutcome outcome;
        PunishmentRecord record;
        synchronized (state) {
            int currentRung = state.getRung(reason.getLadder());
            outcome = reason.evaluate(currentRung);

            UUID punishmentId = UUID.randomUUID();
            List<PunishmentRecordEffect> effects = convertEffects(outcome.effects(), command.getIssuedAt());

            record = new PunishmentRecord(
                    punishmentId,
                    playerId,
                    command.getPlayerName(),
                    reason,
                    reason.getLadder(),
                    outcome.rungBefore(),
                    outcome.rungAfter(),
                    command.getStaffId(),
                    command.getStaffName(),
                    command.getIssuedAt(),
                    effects
            );

            try {
                repository.savePunishment(record);
            } catch (SQLException ex) {
                logger.error("Failed to persist punishment {}", punishmentId, ex);
                return;
            }

            records.put(punishmentId, record);
            state.setRung(reason.getLadder(), outcome.rungAfter());
            state.appendHistory(punishmentId);

            if (effects.stream().anyMatch(e -> isEnforcementType(e.type()))) {
                state.addActive(punishmentId);
            }
            snapshotWriter.writeSnapshot(state, collectActiveRecords(state));
        }

        broadcastApplied(record);
        scheduleExpiryTasks(record);
    }

    private void handleExpiry(MessageEnvelope envelope) {
        PunishmentExpireRequestMessage request;
        try {
            request = MessageTypeRegistry.getInstance()
                    .deserializeToClass(envelope.payload(), PunishmentExpireRequestMessage.class);
        } catch (Exception ex) {
            logger.warn("Failed to deserialize punishment expiry request", ex);
            return;
        }
        if (request == null) {
            return;
        }

        try {
            processExpiry(request);
        } catch (Exception ex) {
            logger.error("Failed to process punishment expiry request {}", request.getRequestId(), ex);
        }
    }

    private void processExpiry(PunishmentExpireRequestMessage message) {
        UUID punishmentId = message.getPunishmentId();
        if (punishmentId == null) {
            return;
        }
        Instant observedAt = message.getObservedAt() != null ? message.getObservedAt() : Instant.now();
        markInactive(punishmentId, PunishmentStatus.INACTIVE, observedAt);
    }

    private List<PunishmentRecordEffect> convertEffects(List<PunishmentEffect> effects, Instant issuedAt) {
        List<PunishmentRecordEffect> result = new ArrayList<>(effects.size());
        for (PunishmentEffect effect : effects) {
            Duration duration = effect.getDuration();
            Instant expiresAt = duration != null ? issuedAt.plus(duration) : null;
            result.add(new PunishmentRecordEffect(effect.getType(), duration, expiresAt, effect.getMessage()));
        }
        return result;
    }

    private void broadcastApplied(PunishmentRecord record) {
        PunishmentAppliedMessage message = new PunishmentAppliedMessage();
        message.setPunishmentId(record.getPunishmentId());
        message.setPlayerId(record.getPlayerId());
        message.setPlayerName(record.getPlayerName());
        message.setStaffId(record.getStaffId());
        message.setStaffName(record.getStaffName());
        message.setReason(record.getReason());
        message.setLadder(record.getLadder());
        message.setRungBefore(record.getRungBefore());
        message.setRungAfter(record.getRungAfter());
        message.setIssuedAt(record.getIssuedAt());

        List<PunishmentAppliedMessage.Effect> effectPayloads = new ArrayList<>();
        for (PunishmentRecordEffect effect : record.getEffects()) {
            long durationSeconds = effect.duration() != null ? effect.duration().getSeconds() : -1L;
            effectPayloads.add(new PunishmentAppliedMessage.Effect(
                    effect.type(),
                    durationSeconds,
                    effect.expiresAt(),
                    effect.message()
            ));
        }
        message.setEffects(effectPayloads);

        messageBus.broadcast(ChannelConstants.REGISTRY_PUNISHMENT_APPLIED, message);
    }

    private boolean isEnforcementType(PunishmentEffectType type) {
        return type == PunishmentEffectType.MUTE
                || type == PunishmentEffectType.BAN
                || type == PunishmentEffectType.BLACKLIST;
    }

    private void scheduleExpiryTasks(PunishmentRecord record) {
        Instant latest = null;
        for (PunishmentRecordEffect effect : record.getEffects()) {
            if (effect.expiresAt() == null) {
                return; // at least one permanent effect keeps this record active indefinitely
            }
            if (effect.type() == PunishmentEffectType.MUTE
                    || effect.type() == PunishmentEffectType.BAN
                    || effect.type() == PunishmentEffectType.BLACKLIST) {
                if (latest == null || effect.expiresAt().isAfter(latest)) {
                    latest = effect.expiresAt();
                }
            }
        }
        if (latest != null) {
            cancelScheduled(record.getPunishmentId());
            long delay = Math.max(0, Duration.between(Instant.now(), latest).toMillis());
            ScheduledFuture<?> future = scheduler.schedule(() -> markInactive(record.getPunishmentId(), PunishmentStatus.INACTIVE),
                    delay,
                    TimeUnit.MILLISECONDS);
            scheduledExpiryTasks.put(record.getPunishmentId(), future);
        }
    }

    public void markInactive(UUID punishmentId, PunishmentStatus status) {
        markInactive(punishmentId, status, Instant.now());
    }

    public void markInactive(UUID punishmentId, PunishmentStatus status, Instant updatedAt) {
        PunishmentRecord record = records.get(punishmentId);
        if (record == null) {
            record = repository.loadPunishment(punishmentId).orElse(null);
            if (record == null) {
                logger.warn("Received status update for unknown punishment {}", punishmentId);
                return;
            }
            records.putIfAbsent(record.getPunishmentId(), record);
            PlayerPunishmentState state = playerStates.computeIfAbsent(record.getPlayerId(), PlayerPunishmentState::new);
            synchronized (state) {
                List<UUID> history = state.getPunishmentHistory();
                if (!history.contains(record.getPunishmentId())) {
                    state.appendHistory(record.getPunishmentId());
                }
                if (record.getStatus() == PunishmentStatus.ACTIVE
                        && record.getEffects().stream().anyMatch(effect -> isEnforcementType(effect.type()))) {
                    state.addActive(record.getPunishmentId());
                }
            }
        }
        if (record.getStatus() == status) {
            return;
        }
        record.setStatus(status, updatedAt);
        PlayerPunishmentState state = playerStates.get(record.getPlayerId());
        if (state != null) {
            synchronized (state) {
                state.removeActive(punishmentId);
                recomputeRungs(state);
                snapshotWriter.writeSnapshot(state, collectActiveRecords(state));
            }
        }
        repository.updateStatus(record.getPunishmentId(), status, record.getUpdatedAt(), record.getPlayerId(), record.getLadder());
        cancelScheduled(punishmentId);
        messageBus.broadcast(ChannelConstants.REGISTRY_PUNISHMENT_STATUS, createStatusMessage(record));
    }

    private PunishmentStatusMessage createStatusMessage(PunishmentRecord record) {
        PunishmentStatusMessage msg = new PunishmentStatusMessage();
        msg.setPunishmentId(record.getPunishmentId());
        msg.setPlayerId(record.getPlayerId());
        msg.setStatus(record.getStatus());
        msg.setUpdatedAt(record.getUpdatedAt());
        return msg;
    }

    public Optional<PunishmentSnapshot> snapshot(UUID playerId) {
        PlayerPunishmentState state = playerStates.get(playerId);
        if (state == null) {
            return Optional.empty();
        }
        synchronized (state) {
            return Optional.of(new PunishmentSnapshot(
                    Instant.now(),
                    new ArrayList<>(state.getActivePunishmentIds()),
                    List.copyOf(state.getPunishmentHistory())
            ));
        }
    }

    public Optional<PunishmentRecord> getRecord(UUID punishmentId) {
        return Optional.ofNullable(records.get(punishmentId));
    }

    public Map<PunishmentLadder, Integer> ladderSnapshot(UUID playerId) {
        PlayerPunishmentState state = playerStates.get(playerId);
        if (state == null) {
            return Map.of();
        }
        synchronized (state) {
            return state.getRungSnapshot();
        }
    }

    public List<PunishmentRecord> getHistory(UUID playerId) {
        PlayerPunishmentState state = playerStates.get(playerId);
        if (state == null) {
            return List.of();
        }
        List<PunishmentRecord> results = new ArrayList<>();
        synchronized (state) {
            for (UUID id : state.getPunishmentHistory()) {
                PunishmentRecord record = records.get(id);
                if (record != null) {
                    results.add(record);
                }
            }
        }
        return results;
    }

    @Override
    public void close() {
        messageBus.unsubscribe(ChannelConstants.REGISTRY_PUNISHMENT_COMMAND, commandHandler);
        messageBus.unsubscribe(ChannelConstants.REGISTRY_PUNISHMENT_EXPIRE_REQUEST, expiryHandler);
        scheduledExpiryTasks.values().forEach(future -> future.cancel(false));
        scheduledExpiryTasks.clear();
        try {
            repository.close();
        } catch (Exception ignored) {
        }
        try {
            snapshotWriter.close();
        } catch (Exception ignored) {
        }
    }

    private void cancelScheduled(UUID punishmentId) {
        ScheduledFuture<?> future = scheduledExpiryTasks.remove(punishmentId);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void recomputeRungs(PlayerPunishmentState state) {
        Map<PunishmentLadder, Integer> counts = new EnumMap<>(PunishmentLadder.class);
        for (PunishmentRecord record : collectActiveRecords(state)) {
            counts.merge(record.getLadder(), 1, Integer::sum);
        }
        for (PunishmentLadder ladder : PunishmentLadder.values()) {
            state.setRung(ladder, counts.getOrDefault(ladder, 0));
        }
    }

    private List<PunishmentRecord> collectActiveRecords(PlayerPunishmentState state) {
        List<PunishmentRecord> list = new ArrayList<>();
        for (UUID id : state.getActivePunishmentIds()) {
            PunishmentRecord record = records.get(id);
            if (record != null && record.getStatus() == PunishmentStatus.ACTIVE) {
                list.add(record);
            }
        }
        return list;
    }
}
