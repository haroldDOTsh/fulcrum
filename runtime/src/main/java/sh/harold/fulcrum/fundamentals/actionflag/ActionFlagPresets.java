package sh.harold.fulcrum.fundamentals.actionflag;

import java.util.EnumSet;

/**
 * Convenient definitions for built-in flag bundles and overrides.
 */
public final class ActionFlagPresets {
    private ActionFlagPresets() {
    }

    public static FlagBundle lobbyDefault() {
        EnumSet<ActionFlag> flags = EnumSet.noneOf(ActionFlag.class);
        flags.add(ActionFlag.INTERACT_BLOCK);
        flags.add(ActionFlag.INTERACT_ENTITY);
        flags.add(ActionFlag.GENERAL_USE);
        flags.add(ActionFlag.ITEM_DROP);
        flags.add(ActionFlag.ITEM_PICKUP);
        return FlagBundle.of(ActionFlagContexts.LOBBY_DEFAULT, flags);
    }

    public static FlagBundle matchPregameDefault() {
        EnumSet<ActionFlag> flags = EnumSet.noneOf(ActionFlag.class);
        flags.add(ActionFlag.INTERACT_BLOCK);
        flags.add(ActionFlag.INTERACT_ENTITY);
        flags.add(ActionFlag.GENERAL_USE);
        return FlagBundle.of(ActionFlagContexts.MATCH_PREGAME_DEFAULT, flags);
    }

    public static FlagBundle matchActiveFallback() {
        EnumSet<ActionFlag> flags = EnumSet.allOf(ActionFlag.class);
        // Active fallback allows everything by default
        return FlagBundle.of(ActionFlagContexts.MATCH_ACTIVE_FALLBACK, flags);
    }

    public static OverrideRequest spectatorOverride() {
        EnumSet<ActionFlag> denies = EnumSet.of(
                ActionFlag.PVP,
                ActionFlag.DAMAGE_HOSTILE,
                ActionFlag.DAMAGE_PASSIVE,
                ActionFlag.BLOCK_BREAK,
                ActionFlag.BLOCK_PLACE,
                ActionFlag.MODIFY_WORLD_OTHER,
                ActionFlag.ITEM_DROP,
                ActionFlag.ITEM_PICKUP
        );
        return OverrideRequest.of(EnumSet.noneOf(ActionFlag.class), denies);
    }
}
