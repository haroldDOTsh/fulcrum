package sh.harold.fulcrum.npc.behavior;

import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.dialogue.Dialogue;
import sh.harold.fulcrum.dialogue.DialogueStartRequest;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Facade exposing helper services to NPC behavior callbacks.
 */
public interface NpcInteractionHelpers {

    NpcInteractionHelpers NOOP = new NpcInteractionHelpers() {
        private final RankHelper ranks = (playerId, rank) -> false;
        private final DialogueHelper dialogues = (context, dialogue, customizer) -> {
        };
        private final MenuHelper menus = (player, descriptor) -> {
        };

        @Override
        public RankHelper ranks() {
            return ranks;
        }

        @Override
        public DialogueHelper dialogues() {
            return dialogues;
        }

        @Override
        public MenuHelper menus() {
            return menus;
        }
    };

    RankHelper ranks();

    DialogueHelper dialogues();

    MenuHelper menus();

    default void ensureRankAtLeast(UUID playerId, Rank rank) {
        if (rank == null) {
            return;
        }
        if (!ranks().hasAtLeast(playerId, rank)) {
            throw new IllegalStateException("Player " + playerId + " lacks required rank " + rank.name());
        }
    }

    interface RankHelper {
        boolean hasAtLeast(UUID playerId, Rank rank);
    }

    interface DialogueHelper {
        default void start(InteractionContext context, Dialogue dialogue) {
            start(context, dialogue, builder -> {
            });
        }

        void start(InteractionContext context,
                   Dialogue dialogue,
                   Consumer<DialogueStartRequest.Builder> customizer);
    }

    interface MenuHelper {
        void open(Player player, Object menuDescriptor);
    }
}
