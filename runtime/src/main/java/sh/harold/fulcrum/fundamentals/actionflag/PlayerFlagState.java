package sh.harold.fulcrum.fundamentals.actionflag;

import org.bukkit.GameMode;

import java.util.Optional;

/**
 * Snapshot of a player's effective flag state (mask + gamemode).
 */
record PlayerFlagState(long mask, GameMode gamemode) {
    Optional<GameMode> optionalGamemode() {
        return Optional.ofNullable(gamemode);
    }
}
