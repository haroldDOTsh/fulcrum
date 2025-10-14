package sh.harold.fulcrum.fundamentals.actionflag;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies side-effects whenever action flag state changes (vanish, potion invisibility, gamemode).
 */
final class ActionFlagEffects implements PlayerFlagStateListener, Listener {
    private static final PotionEffect VANISH_POTION = new PotionEffect(
            PotionEffectType.INVISIBILITY,
            Integer.MAX_VALUE,
            0,
            false,
            false,
            false
    );

    private final ActionFlagService service;
    private final VanishService vanishService;
    private final Set<UUID> potionApplied = ConcurrentHashMap.newKeySet();

    ActionFlagEffects(JavaPlugin plugin, ActionFlagService service, VanishService vanishService) {
        this.service = Objects.requireNonNull(service, "service");
        this.vanishService = Objects.requireNonNull(vanishService, "vanishService");
        service.addStateListener(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    void shutdown() {
        service.removeStateListener(this);
        potionApplied.clear();
        HandlerList.unregisterAll(this);
    }

    @Override
    public void onFlagStateChange(UUID playerId, PlayerFlagState previous, PlayerFlagState current) {
        handlePotionFlag(playerId, previous, current);
        handleVanishFlag(playerId, previous, current);
        handleGamemode(playerId, previous, current);
    }

    private void handlePotionFlag(UUID playerId, PlayerFlagState previous, PlayerFlagState current) {
        boolean hadPotion = hasFlag(previous, ActionFlag.INVISIBLE_POTION);
        boolean hasPotion = hasFlag(current, ActionFlag.INVISIBLE_POTION);
        if (!hadPotion && hasPotion) {
            applyInvisibilityPotion(playerId);
        } else if (hadPotion && !hasPotion) {
            removeInvisibilityPotion(playerId);
        }
    }

    private void handleVanishFlag(UUID playerId, PlayerFlagState previous, PlayerFlagState current) {
        boolean wasVanished = hasFlag(previous, ActionFlag.INVISIBLE_PACKET);
        boolean shouldVanish = hasFlag(current, ActionFlag.INVISIBLE_PACKET);
        if (!wasVanished && shouldVanish) {
            vanishService.vanish(playerId);
        } else if (wasVanished && !shouldVanish) {
            vanishService.reveal(playerId);
        }
    }

    private void handleGamemode(UUID playerId, PlayerFlagState previous, PlayerFlagState current) {
        if (!hasFlag(current, ActionFlag.GAMEMODE)) {
            return;
        }
        Optional<GameMode> previousMode = previous.optionalGamemode();
        Optional<GameMode> currentMode = current.optionalGamemode();
        if (Objects.equals(previousMode.orElse(null), currentMode.orElse(null))) {
            return;
        }
        currentMode.ifPresent(mode -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.getGameMode() != mode) {
                player.setGameMode(mode);
            }
        });
    }

    private boolean hasFlag(PlayerFlagState state, ActionFlag flag) {
        return (state.mask() & flag.mask()) != 0L;
    }

    private void applyInvisibilityPotion(UUID playerId) {
        potionApplied.add(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.addPotionEffect(VANISH_POTION);
        }
    }

    private void removeInvisibilityPotion(UUID playerId) {
        if (!potionApplied.remove(playerId)) {
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.removePotionEffect(PotionEffectType.INVISIBILITY);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        PlayerFlagSnapshot snapshot = service.snapshot(playerId);
        if (snapshot.activeFlags().contains(ActionFlag.INVISIBLE_POTION)) {
            applyInvisibilityPotion(playerId);
        } else {
            potionApplied.remove(playerId);
        }
        if (snapshot.activeFlags().contains(ActionFlag.GAMEMODE)) {
            snapshot.gamemode().ifPresent(mode -> {
                Player player = event.getPlayer();
                if (player.getGameMode() != mode) {
                    player.setGameMode(mode);
                }
            });
        }
    }
}
