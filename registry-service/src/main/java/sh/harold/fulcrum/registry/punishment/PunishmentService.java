package sh.harold.fulcrum.registry.punishment;

import org.slf4j.Logger;
import sh.harold.fulcrum.api.messagebus.*;
import sh.harold.fulcrum.api.messagebus.messages.punishment.PunishmentAppliedMessage;
import sh.harold.fulcrum.api.messagebus.messages.punishment.PunishmentCommandMessage;
import sh.harold.fulcrum.api.messagebus.messages.punishment.PunishmentStatusCommandMessage;
import sh.harold.fulcrum.api.messagebus.messages.punishment.PunishmentStatusMessage;
import sh.harold.fulcrum.api.punishment.*;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

public final class PunishmentService implements AutoCloseable {

    private final MessageBus messageBus;
    private final Logger logger;
    private final PunishmentRepository repository;
    private final PunishmentSnapshotWriter snapshotWriter;
    private final MessageHandler commandHandler;
    private final MessageHandler statusCommandHandler;

    public PunishmentService(MessageBus messageBus,
                             Logger logger,
                             PunishmentRepository repository,
                             PunishmentSnapshotWriter snapshotWriter) {
        this.messageBus = messageBus;
        this.logger = logger;
        this.repository = repository;
        this.snapshotWriter = snapshotWriter;
        this.commandHandler = this::handleCommand;
        this.statusCommandHandler = this::handleStatusCommand;
        this.messageBus.subscribe(ChannelConstants.REGISTRY_PUNISHMENT_COMMAND, commandHandler);
        this.messageBus.subscribe(ChannelConstants.REGISTRY_PUNISHMENT_STATUS_COMMAND, statusCommandHandler);
        logger.info("PunishmentService subscribed to {}", ChannelConstants.REGISTRY_PUNISHMENT_COMMAND);
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

    private void handleStatusCommand(MessageEnvelope envelope) {
        PunishmentStatusCommandMessage command;
        try {
            command = MessageTypeRegistry.getInstance()
                    .deserializeToClass(envelope.payload(), PunishmentStatusCommandMessage.class);
        } catch (Exception ex) {
            logger.warn("Failed to deserialize punishment status command", ex);
            return;
        }
        if (command == null) {
            return;
        }

        try {
            processStatusCommand(command);
        } catch (Exception ex) {
            logger.error("Failed to process punishment status command {}", command.getCommandId(), ex);
        }
    }

    private void processCommand(PunishmentCommandMessage command) {
        PunishmentReason reason = command.getReason();
        if (reason == null) {
            throw new IllegalStateException("Punishment reason missing");
        }

        UUID playerId = command.getPlayerId();
        PunishmentLadder ladder = reason.getLadder();

        int currentRung = repository.countRelevantRung(playerId, ladder);
        PunishmentOutcome outcome = reason.evaluate(currentRung);

        UUID punishmentId = UUID.randomUUID();
        List<PunishmentRecordEffect> effects = convertEffects(outcome.effects(), command.getIssuedAt());

        PunishmentRecord record = new PunishmentRecord(
                punishmentId,
                playerId,
                command.getPlayerName(),
                reason,
                ladder,
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

        broadcastApplied(record);
        refreshSnapshot(playerId);
    }

    private void processStatusCommand(PunishmentStatusCommandMessage command) {
        UUID punishmentId = command.getPunishmentId();
        PunishmentStatus status = command.getStatus();
        if (punishmentId == null || status == null) {
            throw new IllegalStateException("Punishment status command missing required fields");
        }

        Optional<PunishmentRecord> maybeRecord = repository.loadPunishment(punishmentId);
        if (maybeRecord.isEmpty()) {
            logger.warn("Ignoring status command for unknown punishment {}", punishmentId);
            return;
        }

        PunishmentRecord record = maybeRecord.get();
        Instant effectiveAt = command.getEffectiveAt() != null ? command.getEffectiveAt() : Instant.now();
        repository.updateStatus(punishmentId, status, effectiveAt, record.getPlayerId(), record.getLadder());

        refreshSnapshot(record.getPlayerId());

        PunishmentStatusMessage msg = new PunishmentStatusMessage();
        msg.setPunishmentId(punishmentId);
        msg.setPlayerId(record.getPlayerId());
        msg.setPlayerName(record.getPlayerName());
        msg.setStatus(status);
        msg.setUpdatedAt(effectiveAt);
        messageBus.broadcast(ChannelConstants.REGISTRY_PUNISHMENT_STATUS, msg);
    }

    private void refreshSnapshot(UUID playerId) {
        List<PunishmentRecord> active = repository.loadActivePunishments(playerId);
        List<UUID> history = repository.loadPunishmentHistoryIds(playerId);
        snapshotWriter.writeSnapshot(playerId, active, history);
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

    public void markInactive(UUID punishmentId, PunishmentStatus status) {
        Optional<PunishmentRecord> maybeRecord = repository.loadPunishment(punishmentId);
        if (maybeRecord.isEmpty()) {
            return;
        }
        PunishmentRecord record = maybeRecord.get();
        if (record.getStatus() == status) {
            return;
        }
        Instant now = Instant.now();
        repository.updateStatus(punishmentId, status, now, record.getPlayerId(), record.getLadder());
        refreshSnapshot(record.getPlayerId());

        PunishmentStatusMessage msg = new PunishmentStatusMessage();
        msg.setPunishmentId(punishmentId);
        msg.setPlayerId(record.getPlayerId());
        msg.setPlayerName(record.getPlayerName());
        msg.setStatus(status);
        msg.setUpdatedAt(now);
        messageBus.broadcast(ChannelConstants.REGISTRY_PUNISHMENT_STATUS, msg);
    }

    public Optional<PunishmentSnapshot> snapshot(UUID playerId) {
        List<PunishmentRecord> active = repository.loadActivePunishments(playerId);
        List<UUID> history = repository.loadPunishmentHistoryIds(playerId);
        if (active.isEmpty() && history.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PunishmentSnapshot(
                Instant.now(),
                active.stream().map(PunishmentRecord::getPunishmentId).toList(),
                history
        ));
    }

    public Optional<PunishmentRecord> getRecord(UUID punishmentId) {
        return repository.loadPunishment(punishmentId);
    }

    public Map<PunishmentLadder, Integer> ladderSnapshot(UUID playerId) {
        return repository.loadLadderSnapshot(playerId);
    }

    public List<PunishmentRecord> getHistory(UUID playerId) {
        return repository.loadPunishmentHistory(playerId);
    }

    @Override
    public void close() {
        messageBus.unsubscribe(ChannelConstants.REGISTRY_PUNISHMENT_COMMAND, commandHandler);
        messageBus.unsubscribe(ChannelConstants.REGISTRY_PUNISHMENT_STATUS_COMMAND, statusCommandHandler);
        try {
            repository.close();
        } catch (Exception ignored) {
        }
        try {
            snapshotWriter.close();
        } catch (Exception ignored) {
        }
    }
}
