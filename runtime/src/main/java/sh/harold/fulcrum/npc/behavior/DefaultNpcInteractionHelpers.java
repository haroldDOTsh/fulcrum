package sh.harold.fulcrum.npc.behavior;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import sh.harold.fulcrum.api.menu.Menu;
import sh.harold.fulcrum.api.menu.MenuService;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.dialogue.*;
import sh.harold.fulcrum.message.Message;
import sh.harold.fulcrum.message.util.GenericResponse;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Concrete helper facade integrating with runtime services.
 */
public final class DefaultNpcInteractionHelpers implements NpcInteractionHelpers {
    private static final double PROXIMITY_RADIUS_BLOCKS = 4.0D;
    private static final double PROXIMITY_RADIUS_BLOCKS_SQUARED = PROXIMITY_RADIUS_BLOCKS * PROXIMITY_RADIUS_BLOCKS;
    private static final long PROXIMITY_POLL_INTERVAL_TICKS = 10L;

    private final RankHelper rankHelper;
    private final DialogueHelper dialogueHelper;
    private final MenuHelper menuHelper;
    private final DialogueService dialogueService;
    private final JavaPlugin plugin;
    private final ConcurrentMap<UUID, BukkitTask> proximityGuards = new ConcurrentHashMap<>();

    public DefaultNpcInteractionHelpers(RankService rankService,
                                        MenuService menuService,
                                        DialogueService dialogueService,
                                        Logger logger,
                                        JavaPlugin plugin) {
        this.dialogueService = dialogueService;
        this.plugin = plugin;
        this.rankHelper = new RankHelper() {
            @Override
            public boolean hasAtLeast(UUID playerId, Rank rank) {
                if (rankService == null || playerId == null || rank == null) {
                    return false;
                }
                Rank current = rankService.getPrimaryRankSync(playerId);
                if (current == null) {
                    current = Rank.DEFAULT;
                }
                return current.getPriority() >= rank.getPriority();
            }
        };
        if (dialogueService == null) {
            this.dialogueHelper = (context, dialogue, customizer) -> {
                if (logger != null) {
                    logger.warning("Dialogue service unavailable; NPC dialogue helper is disabled.");
                }
            };
        } else {
            this.dialogueHelper = new DialogueHelper() {
                private final LegacyComponentSerializer serializer = LegacyComponentSerializer.legacyAmpersand();

                @Override
                public void start(InteractionContext context,
                                  Dialogue dialogue,
                                  Consumer<DialogueStartRequest.Builder> customizer) {
                    if (context == null || dialogue == null) {
                        return;
                    }
                    Player player = context.player();
                    if (player == null || !player.isOnline()) {
                        return;
                    }
                    UUID playerId = player.getUniqueId();
                    if (shouldAdvanceExistingSession(playerId, context, dialogue)) {
                        if (dialogueService.advance(playerId).isPresent()) {
                            return;
                        }
                    }
                    DialogueStartRequest.Builder builder = DialogueStartRequest.builder(player, dialogue)
                            .displayName(serializer.serialize(context.definition().profile().displayNameComponent()))
                            .npcId(context.npcInstanceId())
                            .attribute("npc.definitionId", context.definition().id())
                            .attribute("npc.descriptor", context.definition().profile().descriptor());
                    if (context.definition().poiAnchor() != null) {
                        builder.attribute("npc.poiAnchor", context.definition().poiAnchor());
                    }
                    if (customizer != null) {
                        try {
                            customizer.accept(builder);
                        } catch (Exception ex) {
                            if (logger != null) {
                                logger.log(Level.WARNING, "Dialogue customizer failure", ex);
                            }
                        }
                    }
                    dialogueService.startConversation(builder.build())
                            .thenAccept(result -> {
                                if (result instanceof DialogueStartResult.CooldownRejected) {
                                    Message.error(GenericResponse.ERROR_COOLDOWN).send(player);
                                    return;
                                }
                                if (result instanceof DialogueStartResult.Started(DialogueSession session)) {
                                    registerProximityGuard(player, context, session);
                                }
                            })
                            .exceptionally(throwable -> {
                                if (logger != null) {
                                    logger.log(Level.WARNING, "Dialogue start failed", throwable);
                                }
                                return null;
                            });
                }

                private boolean shouldAdvanceExistingSession(UUID playerId, InteractionContext context, Dialogue dialogue) {
                    if (playerId == null) {
                        return false;
                    }
                    return dialogueService.activeSession(playerId)
                            .filter(session -> matchesConversation(session, context, dialogue))
                            .filter(session -> session.nextStepIndex() < session.totalSteps())
                            .isPresent();
                }

                private boolean matchesConversation(DialogueSession session,
                                                    InteractionContext context,
                                                    Dialogue dialogue) {
                    if (session == null || context == null || dialogue == null) {
                        return false;
                    }
                    if (!dialogue.equals(session.dialogue())) {
                        return false;
                    }
                    UUID contextNpcId = context.npcInstanceId();
                    if (contextNpcId == null) {
                        return false;
                    }
                    return session.context().npcIdOptional()
                            .map(contextNpcId::equals)
                            .orElse(false);
                }
            };
        }
        this.menuHelper = (player, descriptor) -> {
            if (menuService == null || player == null || descriptor == null) {
                return;
            }
            if (descriptor instanceof Menu menu) {
                menuService.openMenu(menu, player);
            } else if (descriptor instanceof String template) {
                menuService.openMenuTemplate(template, player);
            } else {
                if (logger != null) {
                    logger.fine("Unsupported menu descriptor type: " + descriptor.getClass().getName());
                }
            }
        };
    }

    @Override
    public RankHelper ranks() {
        return rankHelper;
    }

    @Override
    public DialogueHelper dialogues() {
        return dialogueHelper;
    }

    @Override
    public MenuHelper menus() {
        return menuHelper;
    }

    private void registerProximityGuard(Player player, InteractionContext context, DialogueSession session) {
        if (plugin == null || session == null) {
            return;
        }
        Location anchor = context.location();
        if (anchor == null || anchor.getWorld() == null) {
            return;
        }
        Location snapshot = anchor.clone();
        UUID playerId = player.getUniqueId();
        cancelProximityGuard(playerId);
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                () -> pollProximity(player, snapshot, playerId, context),
                PROXIMITY_POLL_INTERVAL_TICKS,
                PROXIMITY_POLL_INTERVAL_TICKS
        );
        proximityGuards.put(playerId, task);
    }

    private void pollProximity(Player player, Location anchor, UUID playerId, InteractionContext context) {
        if (!player.isOnline()) {
            cancelSession(playerId, DialogueCancelReason.PLAYER_LEFT);
            return;
        }
        if (dialogueService == null || dialogueService.activeSession(playerId).isEmpty()) {
            cancelProximityGuard(playerId);
            return;
        }
        Location current = player.getLocation();
        if (current.getWorld() == null || !current.getWorld().equals(anchor.getWorld())) {
            notifyAndCancel(player, playerId, context);
            return;
        }
        if (current.distanceSquared(anchor) > PROXIMITY_RADIUS_BLOCKS_SQUARED) {
            notifyAndCancel(player, playerId, context);
        }
    }

    private void notifyAndCancel(Player player, UUID playerId, InteractionContext context) {
        Component message = Component.text()
                .color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, true)
                .append(Component.text("You walk away from "))
                .append(context.definition().profile().displayNameComponent().color(NamedTextColor.GRAY))
                .append(Component.text("."))
                .build();
        player.sendMessage(message);
        cancelSession(playerId, DialogueCancelReason.DISTANCE);
    }

    private void cancelSession(UUID playerId, DialogueCancelReason reason) {
        if (dialogueService != null) {
            dialogueService.cancel(playerId, reason);
        }
        cancelProximityGuard(playerId);
    }

    private void cancelProximityGuard(UUID playerId) {
        BukkitTask task = proximityGuards.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }
}
