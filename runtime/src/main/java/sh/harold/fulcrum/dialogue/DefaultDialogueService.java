package sh.harold.fulcrum.dialogue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.common.cooldown.CooldownAcquisition;
import sh.harold.fulcrum.common.cooldown.CooldownKey;
import sh.harold.fulcrum.common.cooldown.CooldownKeys;
import sh.harold.fulcrum.common.cooldown.CooldownRegistry;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default Paper-bound implementation that enforces cooldowns and formatting.
 */
public final class DefaultDialogueService implements DialogueService {
    private static final String COOLDOWN_NAMESPACE = "conversation";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final Component NPC_PREFIX = LEGACY.deserialize("&e[NPC]");
    private static final Component SPACE = Component.space();
    private static final Component COLON = Component.text(": ", NamedTextColor.WHITE);

    private final CooldownRegistry registry;
    private final Logger logger;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public DefaultDialogueService(CooldownRegistry registry, Logger logger) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public CompletionStage<DialogueStartResult> startConversation(DialogueStartRequest request) {
        Objects.requireNonNull(request, "request");
        Dialogue dialogue = request.dialogue();
        Player player = request.player();
        UUID playerId = player.getUniqueId();
        DialogueContext context = new DialogueContext(
                player,
                playerId,
                dialogue,
                request.displayName(),
                request.npcId(),
                request.attributes()
        );
        List<DialogueLine> resolvedLines = resolveLines(dialogue.lines(), context);
        if (resolvedLines.isEmpty()) {
            logger.fine(() -> "Dialogue " + dialogue.id() + " resolved to zero lines for player " + playerId);
        }
        CooldownKey key = toCooldownKey(request, context);
        CompletableFuture<DialogueStartResult> promise = new CompletableFuture<>();
        registry.acquire(key, dialogue.cooldownSpec())
                .whenComplete((acquisition, throwable) -> {
                    if (throwable != null) {
                        promise.completeExceptionally(throwable);
                        return;
                    }
                    promise.complete(handleAcquisition(acquisition, context, resolvedLines));
                });
        return promise;
    }

    private DialogueStartResult handleAcquisition(CooldownAcquisition acquisition,
                                                  DialogueContext context,
                                                  List<DialogueLine> resolvedLines) {
        if (acquisition instanceof CooldownAcquisition.Rejected(java.time.Duration remaining)) {
            return new DialogueStartResult.CooldownRejected(remaining);
        }
        Session session = new Session(context, resolvedLines);
        sessions.compute(context.playerId(), (id, existing) -> {
            if (existing != null) {
                existing.cancel(DialogueCancelReason.REPLACED);
            }
            return session;
        });
        session.start();
        return new DialogueStartResult.Started(session);
    }

    @Override
    public Optional<DialogueSession> activeSession(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(playerId));
    }

    @Override
    public Optional<DialogueSession> advance(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        Session session = sessions.get(playerId);
        if (session == null) {
            return Optional.empty();
        }
        session.advance();
        return Optional.of(session);
    }

    @Override
    public Optional<DialogueSession> cancel(UUID playerId, DialogueCancelReason reason) {
        if (playerId == null) {
            return Optional.empty();
        }
        Session session = sessions.remove(playerId);
        if (session == null) {
            return Optional.empty();
        }
        session.cancel(reason == null ? DialogueCancelReason.UNKNOWN : reason);
        return Optional.of(session);
    }

    private CooldownKey toCooldownKey(DialogueStartRequest request, DialogueContext context) {
        Dialogue dialogue = request.dialogue();
        String groupId = Optional.ofNullable(request.groupIdOverride())
                .filter(grp -> !grp.isBlank())
                .orElse(dialogue.cooldownGroup());
        return CooldownKeys.of(
                COOLDOWN_NAMESPACE,
                groupId,
                context.playerId(),
                context.npcId().orElse(null)
        );
    }

    private List<DialogueLine> resolveLines(List<DialogueLine> lines, DialogueContext context) {
        List<DialogueLine> resolved = new ArrayList<>(lines.size());
        for (DialogueLine line : lines) {
            try {
                if (line.shouldRender(context)) {
                    resolved.add(line);
                }
            } catch (Throwable throwable) {
                logger.log(Level.WARNING, "Dialogue predicate failure for " + context.dialogue().id(), throwable);
            }
        }
        return resolved;
    }

    private void onSessionTerminated(Session session) {
        sessions.compute(session.playerId(), (id, existing) -> existing == session ? null : existing);
    }

    private void sendLine(Session session, DialogueLine line, DialogueProgress progress) {
        Component displayNameComponent = session.displayNameComponent;
        Component stepComponent = LEGACY.deserialize("&8[" + progress.stepNumber() + "/" + progress.totalSteps() + "]");
        Component messageComponent = Component.empty()
                .color(NamedTextColor.WHITE)
                .append(renderLine(line, progress.context()));
        Component formatted = NPC_PREFIX
                .append(SPACE)
                .append(displayNameComponent)
                .append(SPACE)
                .append(stepComponent)
                .append(COLON)
                .append(messageComponent);
        session.context().player().sendMessage(formatted);
    }

    private Component renderLine(DialogueLine line, DialogueContext context) {
        try {
            return line.render(context);
        } catch (Throwable throwable) {
            logger.log(Level.WARNING, "Failed to render dialogue line for " + context.dialogue().id(), throwable);
            return LEGACY.deserialize("&c<dialogue error>");
        }
    }

    private final class Session implements DialogueSession {
        private final UUID sessionId = UUID.randomUUID();
        private final DialogueContext context;
        private final List<DialogueLine> lines;
        private final DialogueCallbacks callbacks;
        private final Component displayNameComponent;
        private int index;
        private boolean complete;

        private Session(DialogueContext context, List<DialogueLine> lines) {
            this.context = context;
            this.lines = lines.isEmpty() ? List.of() : List.copyOf(lines);
            this.callbacks = context.dialogue().callbacks();
            this.displayNameComponent = LEGACY.deserialize(context.displayName());
        }

        private void start() {
            DialogueProgress startProgress = new DialogueProgress(context, 0, totalSteps());
            callbacks.fireStart(startProgress);
            if (lines.isEmpty()) {
                complete();
                return;
            }
            advance();
        }

        @Override
        public boolean advance() {
            if (complete) {
                return false;
            }
            if (index >= lines.size()) {
                complete();
                return false;
            }
            DialogueLine line = lines.get(index);
            DialogueProgress progress = new DialogueProgress(context, index, lines.size());
            sendLine(this, line, progress);
            callbacks.fireAdvance(progress);
            try {
                line.runAction(progress);
            } catch (Throwable throwable) {
                logger.log(Level.WARNING, "Dialogue action failure for " + context.dialogue().id(), throwable);
            }
            index++;
            if (index >= lines.size()) {
                complete();
            }
            return true;
        }

        @Override
        public void cancel(DialogueCancelReason reason) {
            if (complete) {
                return;
            }
            complete = true;
            onSessionTerminated(this);
            callbacks.fireComplete(new DialogueProgress(context, Math.min(index, Math.max(0, totalSteps() - 1)), totalSteps()));
        }

        private void complete() {
            if (!complete) {
                complete = true;
                callbacks.fireComplete(new DialogueProgress(context, Math.max(0, totalSteps() - 1), totalSteps()));
                onSessionTerminated(this);
            }
        }

        @Override
        public UUID sessionId() {
            return sessionId;
        }

        @Override
        public UUID playerId() {
            return context.playerId();
        }

        @Override
        public Dialogue dialogue() {
            return context.dialogue();
        }

        @Override
        public DialogueContext context() {
            return context;
        }

        @Override
        public int nextStepIndex() {
            return index;
        }

        @Override
        public int totalSteps() {
            return lines.size();
        }

        @Override
        public boolean isComplete() {
            return complete;
        }
    }
}
