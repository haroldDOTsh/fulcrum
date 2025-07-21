package sh.harold.fulcrum.api.menu.component;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Represents an interactive button in a menu with click handlers and cooldown support.
 */
public class MenuButton implements MenuItem {
    
    // FIXED: Add &r prefix to all predefined buttons to prevent italicization
    public static final MenuButton CLOSE = builder(Material.BARRIER)
        .name("&cClose")
        .sound(Sound.UI_BUTTON_CLICK)
        .onClick(player -> player.closeInventory())
        .build();
    
    public static final MenuButton BACK = builder(Material.ARROW)
        .name("&7Back")
        .sound(Sound.UI_BUTTON_CLICK)
        .onClick(player -> {
            // Navigation service integration will handle this
            // For now, close the current menu as fallback
            player.closeInventory();
        })
        .build();
    
    
    public static final MenuButton REFRESH = builder(Material.SUNFLOWER)
        .name("&bRefresh")
        .sound(Sound.UI_BUTTON_CLICK)
        .onClick(player -> {
            // Menu refresh will be handled by menu implementations
            player.sendMessage(Component.text("Menu refreshed", NamedTextColor.GREEN));
        })
        .build();
    
    public static final MenuButton SEARCH = builder(Material.COMPASS)
        .name("&dSearch")
        .description("Click to search for items")
        .sound(Sound.UI_BUTTON_CLICK)
        .onClick(player -> {
            // Search functionality to be implemented by menu system
            player.sendMessage(Component.text("Search feature coming soon", NamedTextColor.YELLOW));
        })
        .build();
    
    // FIXED: Add &r prefix to all predefined buttons to prevent italicization
    public static final MenuButton SORT = builder(Material.HOPPER)
        .name("&6Sort")
        .description("Click to sort items")
        .sound(Sound.UI_BUTTON_CLICK)
        .onClick(player -> {
            // Sort functionality to be implemented by menu system
            player.sendMessage(Component.text("Sort feature coming soon", NamedTextColor.YELLOW));
        })
        .build();
    
    public static final MenuButton FILTER = builder(Material.NAME_TAG)
        .name("&bFilter")
        .description("Click to filter items")
        .sound(Sound.UI_BUTTON_CLICK)
        .onClick(player -> {
            // Filter functionality to be implemented by menu system
            player.sendMessage(Component.text("Filter feature coming soon", NamedTextColor.YELLOW));
        })
        .build();
    
    public static final MenuButton UP_NAVIGATION = builder(Material.SPECTRAL_ARROW)
        .name("&7Navigate Up")
        .sound(Sound.UI_BUTTON_CLICK)
        .onClick(player -> {
            // Navigation functionality to be implemented by menu system
            player.sendMessage(Component.text("Cannot navigate up", NamedTextColor.YELLOW));
        })
        .build();
    
    public static final MenuButton DOWN_NAVIGATION = builder(Material.TIPPED_ARROW)
        .name("&7Down")
        .sound(Sound.UI_BUTTON_CLICK)
        .onClick(player -> {
            // Navigation functionality to be implemented by menu system
            player.sendMessage(Component.text("Cannot navigate down", NamedTextColor.YELLOW));
        })
        .build();
    
    public static final MenuButton FORWARD = builder(Material.ARROW)
        .name("&6Forward")
        .sound(Sound.UI_BUTTON_CLICK)
        .onClick(player -> {
            // Forward navigation to be implemented by menu system
            player.sendMessage(Component.text("Cannot navigate forward", NamedTextColor.YELLOW));
        })
        .build();
    
    public static final MenuButton BACK_NAVIGATION = builder(Material.ARROW)
        .name("&7Back")
        .sound(Sound.UI_BUTTON_CLICK)
        .onClick(player -> {
            // Back navigation to be implemented by menu system
            player.closeInventory();
        })
        .build();
    

    // Static initializer to validate positioning on class load (development only)
    static {
        try {
            validatePositioning();
            // System.out.println("MenuButton positioning validation passed!"); // Uncomment for debug
        } catch (Exception e) {
            throw new RuntimeException("MenuButton positioning validation failed: " + e.getMessage(), e);
        }
    }
    
    // Slot calculation utility methods for different menu sizes
    // Standard menu sizes: 3 rows=27 slots, 4 rows=36 slots, 5 rows=45 slots, 6 rows=54 slots
    
    /**
     * Calculates the close button slot for the given number of menu rows.
     * Close button is positioned at the center of the bottom row.
     *
     * @param rows the number of rows in the menu (3-6)
     * @return the slot number for the close button
     */
    public static int getCloseSlot(int rows) {
        validateRows(rows);
        return (rows - 1) * 9 + 4; // Center slot of the last row (index 4 in 0-8 range)
    }
    
    /**
     * Calculates the back button slot for the given number of menu rows.
     * Back button is positioned immediately LEFT of the close button.
     * For 6-row menu: close=49, back=48; for 5-row menu: close=40, back=39.
     *
     * @param rows the number of rows in the menu (3-6)
     * @return the slot number for the back button
     */
    public static int getBackSlot(int rows) {
        validateRows(rows);
        return (rows - 1) * 9 + 3; // One slot left of center (close button is at +4)
    }
    
    /**
     * Calculates the search button slot for the given number of menu rows.
     * Search button is positioned at the start of the bottom row.
     *
     * @param rows the number of rows in the menu (3-6)
     * @return the slot number for the search button
     */
    public static int getSearchSlot(int rows) {
        validateRows(rows);
        return (rows - 1) * 9; // First slot of the last row
    }
    
    /**
     * Calculates the sort button slot for the given number of menu rows.
     * Sort button is positioned one slot right of search.
     *
     * @param rows the number of rows in the menu (3-6)
     * @return the slot number for the sort button
     */
    public static int getSortSlot(int rows) {
        validateRows(rows);
        return (rows - 1) * 9 + 1; // Second slot of the last row
    }
    
    /**
     * Calculates the filter button slot for the given number of menu rows.
     * Filter button is positioned two slots right of search.
     *
     * @param rows the number of rows in the menu (3-6)
     * @return the slot number for the filter button
     */
    public static int getFilterSlot(int rows) {
        validateRows(rows);
        return (rows - 1) * 9 + 2; // Third slot of the last row
    }
    
    /**
     * Calculates the forward navigation button slot for the given number of menu rows.
     * Forward button is positioned one slot right of the center (close button).
     *
     * @param rows the number of rows in the menu (3-6)
     * @return the slot number for the forward button
     */
    public static int getForwardSlot(int rows) {
        validateRows(rows);
        return (rows - 1) * 9 + 5; // Center + 1 slot of the last row
    }
    
    /**
     * Calculates the pagination back button slot for the given number of menu rows.
     * Pagination back button is positioned at the first slot of the last row.
     * Used for scrolling backwards through paginated content.
     *
     * @param rows the number of rows in the menu (3-6)
     * @return the slot number for the pagination back button
     */
    public static int getPaginateBackSlot(int rows) {
        validateRows(rows);
        return (rows - 1) * 9; // First slot of the last row
    }
    
    /**
     * Calculates the pagination up button slot for the given number of menu rows.
     * Pagination up button is always at slot 0 (top-left corner).
     * Used for scrolling upwards through paginated content.
     *
     * @param rows the number of rows in the menu (3-6)
     * @return the slot number for the pagination up button
     */
    public static int getPaginateUpSlot(int rows) {
        validateRows(rows);
        return 0; // Always top-left corner
    }
    
    /**
     * Calculates the pagination down button slot for the given number of menu rows.
     * Pagination down button is positioned at the first slot of the last row.
     * Used for scrolling downwards through paginated content.
     *
     * @param rows the number of rows in the menu (3-6)
     * @return the slot number for the pagination down button
     */
    public static int getPaginateDownSlot(int rows) {
        validateRows(rows);
        return (rows - 1) * 9; // First slot of the last row
    }
    
    /**
     * Validates that the number of rows is within acceptable range.
     *
     * @param rows the number of rows to validate
     * @throws IllegalArgumentException if rows is not between 3 and 6
     */
    private static void validateRows(int rows) {
        if (rows < 3 || rows > 6) {
            throw new IllegalArgumentException("Menu rows must be between 3 and 6, got: " + rows);
        }
    }
    
    /**
     * Validates the positioning logic for all standard menu sizes.
     * This method can be called during development to ensure slot calculations are correct.
     *
     * @return true if all validations pass
     * @throws IllegalStateException if any validation fails
     */
    public static boolean validatePositioning() {
        // Test data: rows -> expected results
        int[][] testCases = {
            // {rows, totalSlots, closeSlot, backSlot, searchSlot, sortSlot, filterSlot, forwardSlot, backNavSlot, upNavSlot, downNavSlot}
            {3, 27, 22, 21, 18, 19, 20, 23, 18, 0, 18},  // 3 rows: close=22, back=21, search=18, sort=19, filter=20, forward=23
            {4, 36, 31, 30, 27, 28, 29, 32, 27, 0, 27},  // 4 rows: close=31, back=30, search=27, sort=28, filter=29, forward=32
            {5, 45, 40, 39, 36, 37, 38, 41, 36, 0, 36},  // 5 rows: close=40, back=39, search=36, sort=37, filter=38, forward=41
            {6, 54, 49, 48, 45, 46, 47, 50, 45, 0, 45}   // 6 rows: close=49, back=48, search=45, sort=46, filter=47, forward=50
        };
        
        for (int[] testCase : testCases) {
            int rows = testCase[0];
            int expectedTotalSlots = testCase[1];
            int expectedClose = testCase[2];
            int expectedBack = testCase[3];
            int expectedSearch = testCase[4];
            int expectedSort = testCase[5];
            int expectedFilter = testCase[6];
            int expectedForward = testCase[7];
            int expectedBackNav = testCase[8];
            int expectedUpNav = testCase[9];
            int expectedDownNav = testCase[10];
            
            // Validate total slots calculation
            int actualTotalSlots = rows * 9;
            if (actualTotalSlots != expectedTotalSlots) {
                throw new IllegalStateException(String.format(
                    "Total slots mismatch for %d rows: expected %d, got %d",
                    rows, expectedTotalSlots, actualTotalSlots
                ));
            }
            
            // Validate individual slot calculations
            validateSlotCalculation("Close", rows, getCloseSlot(rows), expectedClose);
            validateSlotCalculation("Back", rows, getBackSlot(rows), expectedBack);
            validateSlotCalculation("Search", rows, getSearchSlot(rows), expectedSearch);
            validateSlotCalculation("Sort", rows, getSortSlot(rows), expectedSort);
            validateSlotCalculation("Filter", rows, getFilterSlot(rows), expectedFilter);
            validateSlotCalculation("Forward", rows, getForwardSlot(rows), expectedForward);
            validateSlotCalculation("PaginateBack", rows, getPaginateBackSlot(rows), expectedBackNav);
            validateSlotCalculation("PaginateUp", rows, getPaginateUpSlot(rows), expectedUpNav);
            validateSlotCalculation("PaginateDown", rows, getPaginateDownSlot(rows), expectedDownNav);
            
            // Validate slot bounds (must be within 0 to totalSlots-1)
            validateSlotBounds(rows, actualTotalSlots, "Close", getCloseSlot(rows));
            validateSlotBounds(rows, actualTotalSlots, "Back", getBackSlot(rows));
            validateSlotBounds(rows, actualTotalSlots, "Search", getSearchSlot(rows));
            validateSlotBounds(rows, actualTotalSlots, "Sort", getSortSlot(rows));
            validateSlotBounds(rows, actualTotalSlots, "Filter", getFilterSlot(rows));
            validateSlotBounds(rows, actualTotalSlots, "Forward", getForwardSlot(rows));
            validateSlotBounds(rows, actualTotalSlots, "PaginateBack", getPaginateBackSlot(rows));
            validateSlotBounds(rows, actualTotalSlots, "PaginateUp", getPaginateUpSlot(rows));
            validateSlotBounds(rows, actualTotalSlots, "PaginateDown", getPaginateDownSlot(rows));
        }
        
        return true;
    }
    
    /**
     * Helper method to validate individual slot calculations.
     */
    private static void validateSlotCalculation(String buttonType, int rows, int actual, int expected) {
        if (actual != expected) {
            throw new IllegalStateException(String.format(
                "%s slot mismatch for %d rows: expected %d, got %d",
                buttonType, rows, expected, actual
            ));
        }
    }
    
    /**
     * Helper method to validate slot bounds.
     */
    private static void validateSlotBounds(int rows, int totalSlots, String buttonType, int slot) {
        if (slot < 0 || slot >= totalSlots) {
            throw new IllegalStateException(String.format(
                "%s slot out of bounds for %d rows: slot %d not in range [0, %d)",
                buttonType, rows, slot, totalSlots
            ));
        }
    }
    
    // Positioned button factory methods
    
    /**
     * Creates a close button with automatic positioning for the given menu size.
     *
     * @param rows the number of rows in the menu (3-6)
     * @return a positioned close button
     */
    public static MenuButton createPositionedClose(int rows) {
        return builder(Material.BARRIER)
            .name("&cClose")
            .sound(Sound.UI_BUTTON_CLICK)
            .onClick(player -> player.closeInventory())
            .slot(getCloseSlot(rows))
            .build();
    }
    
    /**
     * Creates a back button with automatic positioning for the given menu size.
     *
     * @param rows the number of rows in the menu (3-6)
     * @return a positioned back button
     */
    public static MenuButton createPositionedBack(int rows) {
        return builder(Material.ARROW)
            .name("&r<yellow>Back")
            .description("Return to the previous menu")
            .sound(Sound.UI_BUTTON_CLICK)
            .onClick(player -> {
                // Simplified: Just close inventory since navigation system was removed
                player.closeInventory();
            })
            .slot(getBackSlot(rows))
            .build();
    }
    
    /**
     * Creates a search button with automatic positioning for the given menu size.
     *
     * @param rows the number of rows in the menu (3-6)
     * @return a positioned search button
     */
    public static MenuButton createPositionedSearch(int rows) {
        return builder(Material.COMPASS)
            .name("&r<light_purple>Search")
            .description("Click to search for items")
            .sound(Sound.UI_BUTTON_CLICK)
            .onClick(player -> player.sendMessage(Component.text("Search feature coming soon", NamedTextColor.YELLOW)))
            .slot(getSearchSlot(rows))
            .build();
    }
    
    /**
     * Creates a sort button with automatic positioning for the given menu size.
     *
     * @param rows the number of rows in the menu (3-6)
     * @return a positioned sort button
     */
    public static MenuButton createPositionedSort(int rows) {
        return builder(Material.HOPPER)
            .name("&r<gold>Sort")
            .description("Click to sort items")
            .sound(Sound.UI_BUTTON_CLICK)
            .onClick(player -> player.sendMessage(Component.text("Sort feature coming soon", NamedTextColor.YELLOW)))
            .slot(getSortSlot(rows))
            .build();
    }
    
    /**
     * Creates a filter button with automatic positioning for the given menu size.
     *
     * @param rows the number of rows in the menu (3-6)
     * @return a positioned filter button
     */
    public static MenuButton createPositionedFilter(int rows) {
        return builder(Material.NAME_TAG)
            .name("&r<blue>Filter")
            .description("Click to filter items")
            .sound(Sound.UI_BUTTON_CLICK)
            .onClick(player -> player.sendMessage(Component.text("Filter feature coming soon", NamedTextColor.YELLOW)))
            .slot(getFilterSlot(rows))
            .build();
    }
    
    /**
     * Creates a forward navigation button with automatic positioning for the given menu size.
     *
     * @param rows the number of rows in the menu (3-6)
     * @return a positioned forward button
     */
    public static MenuButton createPositionedForward(int rows) {
        return builder(Material.ARROW)
            .name("&r<aqua>Forward")
            .description("Navigate forward")
            .sound(Sound.UI_BUTTON_CLICK)
            .onClick(player -> player.sendMessage(Component.text("Cannot navigate forward", NamedTextColor.YELLOW)))
            .slot(getForwardSlot(rows))
            .build();
    }
    
    /**
     * Creates a pagination back button with automatic positioning for the given menu size.
     *
     * @param rows the number of rows in the menu (3-6)
     * @return a positioned pagination back button
     */
    public static MenuButton createPositionedPaginateBack(int rows) {
        return builder(Material.ARROW)
            .name("&r<gray>Back")
            .description("Navigate back through pages")
            .sound(Sound.UI_BUTTON_CLICK)
            .onClick(player -> {
                // Pagination back functionality to be implemented by menu system
                player.sendMessage(Component.text("Cannot navigate back", NamedTextColor.YELLOW));
            })
            .slot(getPaginateBackSlot(rows))
            .build();
    }
    
    /**
     * Creates a pagination up button with automatic positioning for the given menu size.
     *
     * @param rows the number of rows in the menu (3-6)
     * @return a positioned pagination up button
     */
    public static MenuButton createPositionedPaginateUp(int rows) {
        return builder(Material.SPECTRAL_ARROW)
            .name("&r<green>Up")
            .description("Navigate up through content")
            .sound(Sound.UI_BUTTON_CLICK)
            .onClick(player -> player.sendMessage(Component.text("Cannot navigate up", NamedTextColor.YELLOW)))
            .slot(getPaginateUpSlot(rows))
            .build();
    }
    
    /**
     * Creates a pagination down button with automatic positioning for the given menu size.
     *
     * @param rows the number of rows in the menu (3-6)
     * @return a positioned pagination down button
     */
    public static MenuButton createPositionedPaginateDown(int rows) {
        return builder(Material.TIPPED_ARROW)
            .name("&r<red>Down")
            .description("Navigate down through content")
            .sound(Sound.UI_BUTTON_CLICK)
            .onClick(player -> player.sendMessage(Component.text("Cannot navigate down", NamedTextColor.YELLOW)))
            .slot(getPaginateDownSlot(rows))
            .build();
    }
    
    /**
     * Helper method for back navigation (simplified).
     * Since the complex navigation system was removed, this always returns false
     * so callers will fall back to closing the inventory.
     *
     * @param player the player to navigate back
     * @return false (always falls back to closing inventory)
     */
    private static boolean navigateBack(Player player) {
        // Navigation system was simplified - always return false to trigger fallback
        return false;
    }
    
    /**
     * Creates a common close button for menus.
     *
     * @return a close button with standard styling
     */
    public static MenuButton createCloseButton() {
        return builder(Material.BARRIER)
            .name("&r<red>Close")
            .description("Click to close this menu")
            .sound(Sound.UI_BUTTON_CLICK)
            .onClick(player -> player.closeInventory())
            .build();
    }
    
    /**
     * Creates a common back button for parent menu navigation.
     *
     * @param parentMenuId the parent menu ID to navigate to
     * @param menuService the menu service for navigation
     * @return a back button with parent menu navigation logic
     */
    public static MenuButton createBackButtonForParentMenu(String parentMenuId, Object menuService) {
        return builder(Material.ARROW)
            .name("&r<yellow>« Back")
            .lore("&7Return to " + parentMenuId)
            .sound(Sound.UI_BUTTON_CLICK)
            .onClick(player -> {
                // Use reflection to call menu service methods since we can't import the concrete class here
                try {
                    // Try to get menu registry and open template
                    Object registry = menuService.getClass().getMethod("getMenuRegistry").invoke(menuService);
                    if ((Boolean) registry.getClass().getMethod("hasTemplate", String.class).invoke(registry, parentMenuId)) {
                        registry.getClass().getMethod("openTemplate", String.class, Player.class).invoke(registry, parentMenuId, player);
                        return;
                    }
                    
                    // Try to open menu instance
                    if ((Boolean) menuService.getClass().getMethod("hasMenuInstance", String.class).invoke(menuService, parentMenuId)) {
                        menuService.getClass().getMethod("openMenuInstance", String.class, Player.class).invoke(menuService, parentMenuId, player);
                        return;
                    }
                } catch (Exception e) {
                    System.err.println("[MENU] Failed to navigate to parent menu '" + parentMenuId + "': " + e.getMessage());
                }
                
                // Fallback: Close current menu and notify player
                player.closeInventory();
                player.sendMessage(Component.text("Returned to previous menu", NamedTextColor.GRAY));
            })
            .build();
    }
    
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private ItemStack displayItem;
    private Component name;
    private List<Component> lore;
    private final int slot;
    private final Map<ClickType, Consumer<Player>> clickHandlers;
    private final Duration cooldown;
    private final Map<UUID, Instant> cooldowns = new ConcurrentHashMap<>();
    private final Sound sound;
    private final float volume;
    private final float pitch;
    private boolean anchored;
    
    private MenuButton(Builder builder) {
        this.slot = builder.slot;
        this.name = builder.name;
        this.lore = new ArrayList<>(builder.lore);
        this.clickHandlers = new HashMap<>(builder.clickHandlers);
        this.cooldown = builder.cooldown;
        this.sound = builder.sound;
        this.volume = builder.volume;
        this.pitch = builder.pitch;
        this.anchored = builder.anchored;
        
        // Add click prompt if there are handlers and it's not already there
        if (!clickHandlers.isEmpty() && !builder.skipClickPrompt) {
            if (!lore.isEmpty()) {
                lore.add(Component.empty());
            }
            lore.add(Component.text("Click to interact!", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        }
        
        // Build the ItemStack
        this.displayItem = new ItemStack(builder.material, builder.amount);
        updateItemMeta();
    }
    
    private void updateItemMeta() {
        ItemMeta meta = displayItem.getItemMeta();
        if (meta != null) {
            if (name != null) {
                meta.displayName(name);
            }
            if (!lore.isEmpty()) {
                meta.lore(lore);
            }
            displayItem.setItemMeta(meta);
        }
    }
    
    /**
     * Handles a click event on this button.
     * 
     * @param player the player who clicked
     * @param clickType the type of click
     * @return true if the click was handled, false otherwise
     */
    public boolean handleClick(Player player, ClickType clickType) {
        // Check cooldown
        if (cooldown != null && !cooldown.isZero()) {
            UUID playerId = player.getUniqueId();
            Instant lastClick = cooldowns.get(playerId);
            Instant now = Instant.now();
            
            if (lastClick != null && Duration.between(lastClick, now).compareTo(cooldown) < 0) {
                Duration remaining = cooldown.minus(Duration.between(lastClick, now));
                player.sendMessage(Component.text("Please wait " + formatDuration(remaining) + " before clicking again!", 
                    NamedTextColor.RED));
                return false;
            }
            
            cooldowns.put(playerId, now);
        }
        
        // Try specific click type first
        Consumer<Player> handler = clickHandlers.get(clickType);
        if (handler == null) {
            // Fall back to generic handler
            handler = clickHandlers.get(null);
        }
        
        if (handler != null) {
            // Play sound before executing the handler
            if (sound != null) {
                player.playSound(player.getLocation(), sound, SoundCategory.MASTER, volume, pitch);
            }
            
            handler.accept(player);
            return true;
        }
        
        return false;
    }
    
    private String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        if (seconds < 60) {
            return seconds + "s";
        } else {
            long minutes = seconds / 60;
            seconds = seconds % 60;
            return minutes + "m " + seconds + "s";
        }
    }
    
    @Override
    public Component getName() {
        return name;
    }
    
    @Override
    public List<Component> getLore() {
        return new ArrayList<>(lore);
    }
    
    @Override
    public ItemStack getDisplayItem() {
        return displayItem.clone();
    }
    
    @Override
    public void setDisplayItem(ItemStack itemStack) {
        this.displayItem = Objects.requireNonNull(itemStack, "ItemStack cannot be null").clone();
        // Preserve name and lore if they exist
        if (name != null || !lore.isEmpty()) {
            updateItemMeta();
        }
    }
    
    @Override
    public int getSlot() {
        return slot;
    }
    
    @Override
    public boolean hasSlot() {
        return slot >= 0;
    }
    
    @Override
    public boolean isAnchored() {
        return anchored;
    }
    
    @Override
    public void setAnchored(boolean anchored) {
        this.anchored = anchored;
    }
    
    /**
     * Creates a new builder for MenuButton.
     *
     * @param material the material for the button
     * @return a new Builder instance
     */
    public static Builder builder(Material material) {
        return new Builder(material);
    }
    
    /**
     * Builder class for MenuButton with fluent API.
     */
    public static class Builder {
        private final MiniMessage miniMessage = MiniMessage.miniMessage();
        private final Material material;
        private int amount = 1;
        private Component name;
        private final List<Component> lore = new ArrayList<>();
        private int slot = -1;
        private final Map<ClickType, Consumer<Player>> clickHandlers = new HashMap<>();
        private Duration cooldown;
        private boolean skipClickPrompt = false;
        private Sound sound;
        private float volume = 1.0f;
        private float pitch = 1.0f;
        private boolean anchored = false;
        
        private Builder(Material material) {
            this.material = Objects.requireNonNull(material, "Material cannot be null");
        }
        
        /**
         * Sets the display name of the button.
         * Supports MiniMessage formatting and legacy color codes.
         * Uses dual approach: legacy compatibility with &amp;r prefix and Adventure's proper decoration API.
         *
         * @param name the display name
         * @return this builder
         */
        public Builder name(String name) {
            if (name != null) {
                // Process text through both legacy and Adventure approaches
                
                // 1. Legacy approach: Add reset code if not present
                String processedName;
                if (name.startsWith("&r") || name.startsWith("<reset>")) {
                    processedName = name;
                } else {
                    processedName = "&r" + name;
                }
                
                // 2. Adventure approach: Use proper decoration API
                Component component;
                if (processedName.contains("§") || processedName.contains("&")) {
                    // Legacy format - use LegacyComponentSerializer
                    component = LegacyComponentSerializer.legacyAmpersand().deserialize(processedName);
                } else {
                    // MiniMessage format
                    component = miniMessage.deserialize(processedName);
                }
                
                // 3. Apply Adventure italic decoration fix
                component = component.decoration(TextDecoration.ITALIC, false);
                
                this.name = component;
            }
            return this;
        }
        
        /**
         * Sets the display name using an Adventure Component.
         * 
         * @param name the display name component
         * @return this builder
         */
        public Builder name(Component name) {
            this.name = name;
            return this;
        }
        
        /**
         * Adds a secondary line in dark gray below the name.
         * 
         * @param text the secondary text
         * @return this builder
         */
        public Builder secondary(String text) {
            if (text != null && !text.isEmpty()) {
                Component secondary = miniMessage.deserialize(convertLegacyColors(text))
                    .color(NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false);
                lore.add(0, secondary); // Add at beginning
            }
            return this;
        }
        
        /**
         * Adds a description block with automatic word wrapping.
         * Each line will be formatted in gray.
         * 
         * @param description the description text
         * @return this builder
         */
        public Builder description(String description) {
            if (description != null && !description.isEmpty()) {
                // Add empty line before description if lore exists
                if (!lore.isEmpty()) {
                    lore.add(Component.empty());
                }
                
                // Word wrap at approximately 30 characters
                List<String> wrapped = wordWrap(description, 30);
                for (String line : wrapped) {
                    Component descLine = miniMessage.deserialize(convertLegacyColors(line))
                        .color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false);
                    lore.add(descLine);
                }
            }
            return this;
        }
        
        /**
         * Adds a single lore line.
         * 
         * @param line the lore line
         * @return this builder
         */
        public Builder lore(String line) {
            if (line != null) {
                Component loreLine = miniMessage.deserialize(convertLegacyColors(line))
                    .decoration(TextDecoration.ITALIC, false);
                lore.add(loreLine);
            }
            return this;
        }
        
        /**
         * Adds a lore line using an Adventure Component.
         * 
         * @param line the lore component
         * @return this builder
         */
        public Builder lore(Component line) {
            if (line != null) {
                lore.add(line);
            }
            return this;
        }
        
        /**
         * Adds multiple lore lines.
         * 
         * @param lines the lore lines
         * @return this builder
         */
        public Builder lore(String... lines) {
            for (String line : lines) {
                lore(line);
            }
            return this;
        }
        
        /**
         * Adds multiple lore lines using Adventure Components.
         * 
         * @param lines the lore components
         * @return this builder
         */
        public Builder lore(Component... lines) {
            lore.addAll(Arrays.asList(lines));
            return this;
        }
        
        /**
         * Sets the item amount.
         * 
         * @param amount the stack size
         * @return this builder
         */
        public Builder amount(int amount) {
            this.amount = Math.max(1, Math.min(64, amount));
            return this;
        }
        
        /**
         * Sets the slot position for this button.
         * Buttons with slots are automatically anchored (non-scrolling).
         *
         * @param slot the slot position (0-53 for double chest)
         * @return this builder
         */
        public Builder slot(int slot) {
            this.slot = slot;
            this.anchored = true; // Automatically anchor buttons with slots
            return this;
        }
        
        /**
         * Sets a generic click handler for all click types.
         * 
         * @param handler the click handler
         * @return this builder
         */
        public Builder onClick(Consumer<Player> handler) {
            if (handler != null) {
                this.clickHandlers.put(null, handler);
            }
            return this;
        }
        
        /**
         * Sets a click handler for a specific click type.
         * 
         * @param clickType the click type
         * @param handler the click handler
         * @return this builder
         */
        public Builder onClick(ClickType clickType, Consumer<Player> handler) {
            if (clickType != null && handler != null) {
                this.clickHandlers.put(clickType, handler);
            }
            return this;
        }
        
        /**
         * Sets the cooldown duration for this button.
         * 
         * @param cooldown the cooldown duration
         * @return this builder
         */
        public Builder cooldown(Duration cooldown) {
            this.cooldown = cooldown;
            return this;
        }
        
        /**
         * Skips adding the automatic "Click to interact!" prompt.
         *
         * @return this builder
         */
        public Builder skipClickPrompt() {
            this.skipClickPrompt = true;
            return this;
        }
        
        /**
         * Sets the sound to play when this button is clicked.
         * Uses default volume (1.0) and pitch (1.0).
         *
         * @param sound the sound to play
         * @return this builder
         */
        public Builder sound(Sound sound) {
            this.sound = sound;
            this.volume = 1.0f;
            this.pitch = 1.0f;
            return this;
        }
        
        /**
         * Sets the sound to play when this button is clicked with custom volume and pitch.
         *
         * @param sound the sound to play
         * @param volume the volume (0.0 to 1.0)
         * @param pitch the pitch (0.5 to 2.0)
         * @return this builder
         */
        public Builder sound(Sound sound, float volume, float pitch) {
            this.sound = sound;
            this.volume = Math.max(0.0f, Math.min(1.0f, volume));
            this.pitch = Math.max(0.5f, Math.min(2.0f, pitch));
            return this;
        }
        
        /**
         * Sets whether this button should be anchored (non-scrolling).
         * Anchored buttons remain in fixed positions regardless of viewport scrolling.
         *
         * @param anchored true to anchor this button
         * @return this builder
         */
        public Builder anchor(boolean anchored) {
            this.anchored = anchored;
            return this;
        }
        
        /**
         * Anchors this button (equivalent to anchor(true)).
         * Anchored buttons remain in fixed positions regardless of viewport scrolling.
         *
         * @return this builder
         */
        public Builder anchor() {
            return anchor(true);
        }
        
        /**
         * Builds the MenuButton.
         *
         * @return the built MenuButton
         */
        public MenuButton build() {
            return new MenuButton(this);
        }
        
        /**
         * Converts legacy color codes (&) to MiniMessage format.
         */
        private String convertLegacyColors(String text) {
            if (text == null) return null;
            
            // Replace color codes
            text = text.replace("&0", "<black>");
            text = text.replace("&1", "<dark_blue>");
            text = text.replace("&2", "<dark_green>");
            text = text.replace("&3", "<dark_aqua>");
            text = text.replace("&4", "<dark_red>");
            text = text.replace("&5", "<dark_purple>");
            text = text.replace("&6", "<gold>");
            text = text.replace("&7", "<gray>");
            text = text.replace("&8", "<dark_gray>");
            text = text.replace("&9", "<blue>");
            text = text.replace("&a", "<green>");
            text = text.replace("&b", "<aqua>");
            text = text.replace("&c", "<red>");
            text = text.replace("&d", "<light_purple>");
            text = text.replace("&e", "<yellow>");
            text = text.replace("&f", "<white>");
            
            // Replace formatting codes
            text = text.replace("&k", "<obfuscated>");
            text = text.replace("&l", "<bold>");
            text = text.replace("&m", "<strikethrough>");
            text = text.replace("&n", "<underlined>");
            text = text.replace("&o", "<italic>");
            text = text.replace("&r", "<reset>");
            
            return text;
        }
        
        /**
         * Word wraps text at the specified length.
         */
        private List<String> wordWrap(String text, int maxLength) {
            List<String> lines = new ArrayList<>();
            String[] words = text.split(" ");
            StringBuilder currentLine = new StringBuilder();
            
            for (String word : words) {
                if (currentLine.length() > 0 && currentLine.length() + word.length() + 1 > maxLength) {
                    lines.add(currentLine.toString());
                    currentLine = new StringBuilder();
                }
                
                if (currentLine.length() > 0) {
                    currentLine.append(" ");
                }
                currentLine.append(word);
            }
            
            if (currentLine.length() > 0) {
                lines.add(currentLine.toString());
            }
            
            return lines;
        }
    }
}