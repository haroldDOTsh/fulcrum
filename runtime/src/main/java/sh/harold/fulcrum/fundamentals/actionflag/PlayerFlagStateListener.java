package sh.harold.fulcrum.fundamentals.actionflag;

import java.util.UUID;

/**
 * Observer notified whenever a player's effective flag state changes.
 */
@FunctionalInterface
interface PlayerFlagStateListener {
    void onFlagStateChange(UUID playerId, PlayerFlagState previous, PlayerFlagState current);
}
