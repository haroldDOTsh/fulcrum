package sh.harold.fulcrum.fundamentals.rank;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.rank.enums.FunctionalRank;
import sh.harold.fulcrum.api.rank.enums.MonthlyPackageRank;
import sh.harold.fulcrum.api.rank.enums.PackageRank;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.api.rank.model.EffectiveRank;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Manages visual display aspects of ranks including tablist and chat formatting.
 * Uses Paper's Adventure API for rich text formatting.
 */
public class RankDisplayManager {

    private final RankService rankService;
    private final JavaPlugin plugin;

    public RankDisplayManager(RankService rankService, JavaPlugin plugin) {
        this.rankService = rankService;
        this.plugin = plugin;
    }

    /**
     * Update a player's tablist display based on their effective rank.
     * This method is async-safe and handles the main thread scheduling internally.
     */
    public CompletableFuture<Void> updatePlayerTablist(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return CompletableFuture.completedFuture(null);
        }

        return rankService.getEffectiveRank(playerId)
                .thenAccept(effectiveRank -> {
                    // Schedule tablist update on main thread since it's a Bukkit API operation
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Component displayName = createDisplayName(player.getName(), effectiveRank);
                        Component tablistName = createTablistName(player.getName(), effectiveRank);
                        
                        player.displayName(displayName);
                        player.playerListName(tablistName);
                    });
                });
    }

    /**
     * Create a chat display name component for a player based on their effective rank.
     */
    public Component createChatName(String playerName, EffectiveRank effectiveRank) {
        String prefix = getHighestPriorityPrefix(effectiveRank);
        NamedTextColor nameColor = getHighestPriorityNameColor(effectiveRank);
        
        if (prefix.isEmpty()) {
            return Component.text(playerName, nameColor);
        }
        
        NamedTextColor prefixColor = getHighestPriorityColor(effectiveRank);
        return Component.text(prefix, prefixColor)
                .append(Component.text(playerName, nameColor));
    }

    /**
     * Create a display name component for a player.
     */
    private Component createDisplayName(String playerName, EffectiveRank effectiveRank) {
        return createChatName(playerName, effectiveRank);
    }

    /**
     * Create a tablist name component for a player.
     */
    private Component createTablistName(String playerName, EffectiveRank effectiveRank) {
        String tablistPrefix = getHighestPriorityTablistPrefix(effectiveRank);
        NamedTextColor nameColor = getHighestPriorityNameColor(effectiveRank);
        
        if (tablistPrefix.isEmpty()) {
            return Component.text(playerName, nameColor);
        }
        
        NamedTextColor prefixColor = getHighestPriorityColor(effectiveRank);
        return Component.text(tablistPrefix, prefixColor)
                .append(Component.text(playerName, nameColor));
    }

    /**
     * Get the highest priority rank prefix for display.
     */
    private String getHighestPriorityPrefix(EffectiveRank effectiveRank) {
        // Functional ranks have highest priority
        if (effectiveRank.functional() != null) {
            return effectiveRank.functional().getRankPrefix();
        }
        
        // Monthly ranks have second priority
        if (effectiveRank.monthly() != null) {
            return effectiveRank.monthly().getRankPrefix();
        }
        
        // Package ranks have lowest priority (but always present)
        return effectiveRank.packageRank().getRankPrefix();
    }

    /**
     * Get the highest priority tablist prefix for display.
     */
    private String getHighestPriorityTablistPrefix(EffectiveRank effectiveRank) {
        // Functional ranks have highest priority
        if (effectiveRank.functional() != null) {
            return effectiveRank.functional().getTablistPrefix();
        }
        
        // Monthly ranks have second priority
        if (effectiveRank.monthly() != null) {
            return effectiveRank.monthly().getTablistPrefix();
        }
        
        // Package ranks have lowest priority (but always present)
        return effectiveRank.packageRank().getTablistPrefix();
    }

    /**
     * Get the highest priority name color for display.
     */
    private NamedTextColor getHighestPriorityNameColor(EffectiveRank effectiveRank) {
        // Functional ranks have highest priority
        if (effectiveRank.functional() != null) {
            return effectiveRank.functional().getNameColor();
        }
        
        // Monthly ranks have second priority
        if (effectiveRank.monthly() != null) {
            return effectiveRank.monthly().getNameColor();
        }
        
        // Package ranks have lowest priority (but always present)
        return effectiveRank.packageRank().getNameColor();
    }

    /**
     * Get the highest priority color for display.
     */
    private NamedTextColor getHighestPriorityColor(EffectiveRank effectiveRank) {
        // Functional ranks have highest priority
        if (effectiveRank.functional() != null) {
            return effectiveRank.functional().getColor();
        }
        
        // Monthly ranks have second priority
        if (effectiveRank.monthly() != null) {
            return effectiveRank.monthly().getColor();
        }
        
        // Package ranks have lowest priority (but always present)
        return effectiveRank.packageRank().getColor();
    }

    /**
     * Update tablist for all online players.
     * Useful for server startup or global rank changes.
     */
    public CompletableFuture<Void> updateAllPlayerTablists() {
        CompletableFuture<?>[] futures = Bukkit.getOnlinePlayers().stream()
                .map(player -> updatePlayerTablist(player.getUniqueId()))
                .toArray(CompletableFuture[]::new);
        
        return CompletableFuture.allOf(futures);
    }
}