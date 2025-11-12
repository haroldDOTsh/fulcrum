package sh.harold.fulcrum.npc.behavior;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.menu.Menu;
import sh.harold.fulcrum.api.menu.MenuService;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.dialogue.Dialogue;
import sh.harold.fulcrum.dialogue.DialogueService;
import sh.harold.fulcrum.dialogue.DialogueStartRequest;
import sh.harold.fulcrum.dialogue.DialogueStartResult;
import sh.harold.fulcrum.message.Message;
import sh.harold.fulcrum.message.util.GenericResponse;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Concrete helper facade integrating with runtime services.
 */
public final class DefaultNpcInteractionHelpers implements NpcInteractionHelpers {
    private final RankHelper rankHelper;
    private final DialogueHelper dialogueHelper;
    private final MenuHelper menuHelper;

    public DefaultNpcInteractionHelpers(RankService rankService,
                                        MenuService menuService,
                                        DialogueService dialogueService,
                                        Logger logger) {
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
                                }
                            })
                            .exceptionally(throwable -> {
                                if (logger != null) {
                                    logger.log(Level.WARNING, "Dialogue start failed", throwable);
                                }
                                return null;
                            });
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
}
