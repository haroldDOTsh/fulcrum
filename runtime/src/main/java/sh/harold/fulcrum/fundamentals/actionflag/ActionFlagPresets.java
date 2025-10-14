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
        flags.add(ActionFlag.HEALTH);
        flags.add(ActionFlag.HUNGER);
        flags.add(ActionFlag.GAMEMODE);
        return FlagBundle.of(ActionFlagContexts.LOBBY_DEFAULT, flags).withGamemode(org.bukkit.GameMode.SURVIVAL);
    }

    public static FlagBundle matchPregameDefault() {
        EnumSet<ActionFlag> flags = EnumSet.noneOf(ActionFlag.class);
        flags.add(ActionFlag.INTERACT_BLOCK);
        flags.add(ActionFlag.INTERACT_ENTITY);
        flags.add(ActionFlag.GENERAL_USE);
        flags.add(ActionFlag.HEALTH);
        flags.add(ActionFlag.HUNGER);
        flags.add(ActionFlag.GAMEMODE);
        return FlagBundle.of(ActionFlagContexts.MATCH_PREGAME_DEFAULT, flags).withGamemode(org.bukkit.GameMode.ADVENTURE);
    }

    public static FlagBundle matchActiveFallback() {
        EnumSet<ActionFlag> flags = EnumSet.allOf(ActionFlag.class);
        // Active fallback allows everything by default
        return FlagBundle.of(ActionFlagContexts.MATCH_ACTIVE_FALLBACK, flags).withGamemode(org.bukkit.GameMode.SURVIVAL);
    }

    public static OverrideRequest spectatorOverride() {
        EnumSet<ActionFlag> allows = EnumSet.of(
                ActionFlag.INVISIBLE_POTION,
                ActionFlag.INVISIBLE_PACKET,
                ActionFlag.GAMEMODE
        );
        EnumSet<ActionFlag> denies = EnumSet.of(
                ActionFlag.PVP,
                ActionFlag.DAMAGE_HOSTILE,
                ActionFlag.DAMAGE_PASSIVE,
                ActionFlag.BLOCK_BREAK,
                ActionFlag.BLOCK_PLACE,
                ActionFlag.MODIFY_WORLD_OTHER,
                ActionFlag.HEALTH,
                ActionFlag.HUNGER,
                ActionFlag.ITEM_DROP,
                ActionFlag.ITEM_PICKUP
        );
        return OverrideRequest.of(allows, denies).withGamemode(org.bukkit.GameMode.SPECTATOR);
    }
}
