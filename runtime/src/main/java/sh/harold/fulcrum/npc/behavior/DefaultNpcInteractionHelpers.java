package sh.harold.fulcrum.npc.behavior;

import sh.harold.fulcrum.api.menu.Menu;
import sh.harold.fulcrum.api.menu.MenuService;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankService;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Concrete helper facade integrating with runtime services.
 */
public final class DefaultNpcInteractionHelpers implements NpcInteractionHelpers {
    private final RankHelper rankHelper;
    private final DialogueHelper dialogueHelper = (player, script) -> {
    };
    private final MenuHelper menuHelper;

    public DefaultNpcInteractionHelpers(RankService rankService,
                                        MenuService menuService,
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
