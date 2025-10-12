package sh.harold.fulcrum.api.menu.impl;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.Plugin;
import sh.harold.fulcrum.api.menu.Menu;
import sh.harold.fulcrum.api.menu.component.MenuButton;
import sh.harold.fulcrum.api.menu.component.MenuItem;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Listener for inventory events related to menus.
 * Handles click validation, exploit prevention, and menu interactions.
 */
public class MenuInventoryListener implements Listener {

    private static final Duration CLICK_RATE_LIMIT = Duration.ofMillis(100); // 100ms between clicks
    private static final Duration DOUBLE_CLICK_WINDOW = Duration.ofMillis(500);
    private final DefaultMenuService menuService;
    private final Plugin plugin;
    // Rate limiting for clicks (player -> last click time)
    private final Map<UUID, Instant> lastClickTimes = new ConcurrentHashMap<>();
    // Double-click prevention
    private final Map<UUID, ClickRecord> recentClicks = new ConcurrentHashMap<>();

    public MenuInventoryListener(DefaultMenuService menuService, Plugin plugin) {
        this.menuService = Objects.requireNonNull(menuService, "MenuService cannot be null");
        this.plugin = Objects.requireNonNull(plugin, "Plugin cannot be null");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        Inventory clickedInventory = event.getClickedInventory();
        InventoryView view = event.getView();

        // Check if this is a menu inventory
        Menu menu = MenuInventoryHolder.getMenu(view.getTopInventory());
        if (menu == null) {
            return;
        }

        // Always cancel the event to prevent item movement
        event.setCancelled(true);

        // Validate the click is in the menu inventory (not player inventory)
        if (clickedInventory == null || !clickedInventory.equals(view.getTopInventory())) {
            // Handle outside click
            if (event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT) {
                handleOutsideClick(player, menu);
            }
            return;
        }

        // Rate limiting check
        if (!checkRateLimit(player)) {
            plugin.getLogger().fine("Rate limit exceeded for player " + player.getName());
            return;
        }

        // Get clicked slot
        int slot = event.getSlot();
        if (slot < 0 || slot >= menu.getSize()) {
            return;
        }

        // Prevent double-clicks
        if (isDoubleClick(player, slot, event.getClick())) {
            plugin.getLogger().fine("Double-click prevented for player " + player.getName());
            return;
        }

        // Security: Verify player is the menu viewer
        if (!menu.getViewer().map(viewer -> viewer.equals(player)).orElse(false)) {
            plugin.getLogger().warning("Player " + player.getName() + " tried to interact with menu they're not viewing!");
            player.closeInventory();
            return;
        }

        // Get the item at the clicked slot
        MenuItem item = null;
        if (menu instanceof AbstractMenu) {
            item = ((AbstractMenu) menu).getItem(slot);
        }

        if (item == null) {
            // Empty slot clicked
            return;
        }

        // Handle button clicks
        if (item instanceof MenuButton) {
            MenuButton button = (MenuButton) item;
            try {
                // Special handling for navigation buttons
                if (button == MenuButton.BACK) {
                    // Simplified: Just close inventory since navigation system was removed
                    player.closeInventory();
                } else if (button == MenuButton.CLOSE) {
                    player.closeInventory();
                } else if (button == MenuButton.REFRESH) {
                    menuService.refreshMenu(player);
                } else {
                    // Regular button click
                    button.handleClick(player, event.getClick());
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error handling button click", e);
                player.sendMessage("§cAn error occurred while processing your click.");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        InventoryView view = event.getView();

        // Check if this is a menu inventory
        Menu menu = MenuInventoryHolder.getMenu(view.getTopInventory());
        if (menu == null) {
            return;
        }

        // Cancel all drag events in menus
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getPlayer();
        Inventory inventory = event.getInventory();

        // Check if this is a menu inventory
        Menu menu = MenuInventoryHolder.getMenu(inventory);
        if (menu == null) {
            return;
        }

        // Notify the menu service
        menuService.handleMenuClosed(player);

        // Trigger close handlers
        if (menu instanceof AbstractMenu) {
            ((AbstractMenu) menu).triggerCloseHandlers();
        }

        // Clean up rate limit data
        lastClickTimes.remove(player.getUniqueId());
        recentClicks.remove(player.getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // Close any open menu
        menuService.closeMenu(player);

        // Navigation history cleanup removed since navigation system was simplified

        // Clean up rate limit data
        lastClickTimes.remove(player.getUniqueId());
        recentClicks.remove(player.getUniqueId());
    }

    /**
     * Additional security: prevent shift-clicking from player inventory
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryAction(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        InventoryView view = event.getView();
        Menu menu = MenuInventoryHolder.getMenu(view.getTopInventory());
        if (menu == null) {
            return;
        }

        // Prevent all shift-clicks and number key presses
        if (event.getClick() == ClickType.SHIFT_LEFT ||
                event.getClick() == ClickType.SHIFT_RIGHT ||
                event.getClick() == ClickType.NUMBER_KEY ||
                event.getClick() == ClickType.DOUBLE_CLICK) {
            event.setCancelled(true);
        }

        // Prevent hotbar swap
        if (event.getAction() == InventoryAction.HOTBAR_SWAP) {
            event.setCancelled(true);
        }
    }

    /**
     * Checks rate limiting for clicks.
     */
    private boolean checkRateLimit(Player player) {
        UUID playerId = player.getUniqueId();
        Instant now = Instant.now();
        Instant lastClick = lastClickTimes.get(playerId);

        if (lastClick != null) {
            Duration elapsed = Duration.between(lastClick, now);
            if (elapsed.compareTo(CLICK_RATE_LIMIT) < 0) {
                return false; // Too fast
            }
        }

        lastClickTimes.put(playerId, now);
        return true;
    }

    /**
     * Checks if this is a double-click.
     */
    private boolean isDoubleClick(Player player, int slot, ClickType clickType) {
        UUID playerId = player.getUniqueId();
        Instant now = Instant.now();
        ClickRecord recent = recentClicks.get(playerId);

        if (recent != null && recent.slot == slot && recent.clickType == clickType) {
            Duration elapsed = Duration.between(recent.timestamp, now);
            if (elapsed.compareTo(DOUBLE_CLICK_WINDOW) < 0) {
                return true; // Double click detected
            }
        }

        recentClicks.put(playerId, new ClickRecord(slot, clickType, now));
        return false;
    }

    /**
     * Handles clicks outside the menu inventory.
     */
    private void handleOutsideClick(Player player, Menu menu) {
        // Check if menu should close on outside click
        if (menu.getContext().getProperty("closeOnOutsideClick", Boolean.class).orElse(true)) {
            player.closeInventory();
        }
    }

    /**
     * Record of a recent click for double-click prevention.
     */
    private static class ClickRecord {
        final int slot;
        final ClickType clickType;
        final Instant timestamp;

        ClickRecord(int slot, ClickType clickType, Instant timestamp) {
            this.slot = slot;
            this.clickType = clickType;
            this.timestamp = timestamp;
        }
    }
}