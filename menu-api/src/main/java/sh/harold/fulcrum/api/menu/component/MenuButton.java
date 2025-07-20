package sh.harold.fulcrum.api.menu.component;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
        .name("&r<red>Close")
        .description("Click to close this menu")
        .sound(Sound.UI_BUTTON_CLICK)
        .onClick(player -> player.closeInventory())
        .build();
    
    public static final MenuButton BACK = builder(Material.ARROW)
        .name("&r<yellow>Back")
        .description("Return to the previous menu")
        .sound(Sound.UI_BUTTON_CLICK)
        .onClick(player -> {
            // Navigation service integration will handle this
            // For now, close the current menu as fallback
            player.closeInventory();
        })
        .build();
    
    public static final MenuButton NEXT_PAGE = builder(Material.LIME_DYE)
        .name("&r<green>Next Page")
        .secondary("Go to the next page")
        .sound(Sound.ITEM_BOOK_PAGE_TURN)
        .onClick(player -> {
            // Pagination will be handled by menu implementations
            player.sendMessage(Component.text("No next page available", NamedTextColor.YELLOW));
        })
        .build();
    
    public static final MenuButton PREVIOUS_PAGE = builder(Material.RED_DYE)
        .name("&r<red>Previous Page")
        .secondary("Go to the previous page")
        .sound(Sound.ITEM_BOOK_PAGE_TURN)
        .onClick(player -> {
            // Pagination will be handled by menu implementations
            player.sendMessage(Component.text("No previous page available", NamedTextColor.YELLOW));
        })
        .build();
    
    public static final MenuButton REFRESH = builder(Material.SUNFLOWER)
        .name("&r<aqua>Refresh")
        .description("Click to refresh this menu")
        .sound(Sound.UI_BUTTON_CLICK)
        .onClick(player -> {
            // Menu refresh will be handled by menu implementations
            player.sendMessage(Component.text("Menu refreshed", NamedTextColor.GREEN));
        })
        .build();
    
    public static final MenuButton SEARCH = builder(Material.COMPASS)
        .name("&r<light_purple>Search")
        .description("Click to search for items")
        .sound(Sound.UI_BUTTON_CLICK)
        .onClick(player -> {
            // Search functionality to be implemented by menu system
            player.sendMessage(Component.text("Search feature coming soon", NamedTextColor.YELLOW));
        })
        .build();
    
    // FIXED: Add &r prefix to all predefined buttons to prevent italicization
    public static final MenuButton SORT = builder(Material.HOPPER)
        .name("&r<gold>Sort")
        .description("Click to sort items")
        .sound(Sound.UI_BUTTON_CLICK)
        .onClick(player -> {
            // Sort functionality to be implemented by menu system
            player.sendMessage(Component.text("Sort feature coming soon", NamedTextColor.YELLOW));
        })
        .build();
    
    public static final MenuButton FILTER = builder(Material.NAME_TAG)
        .name("&r<blue>Filter")
        .description("Click to filter items")
        .sound(Sound.UI_BUTTON_CLICK)
        .onClick(player -> {
            // Filter functionality to be implemented by menu system
            player.sendMessage(Component.text("Filter feature coming soon", NamedTextColor.YELLOW));
        })
        .build();
    
    public static final MenuButton UP_NAVIGATION = builder(Material.SPECTRAL_ARROW)
        .name("&r<green>Up")
        .description("Navigate up")
        .sound(Sound.UI_BUTTON_CLICK)
        .onClick(player -> {
            // Navigation functionality to be implemented by menu system
            player.sendMessage(Component.text("Cannot navigate up", NamedTextColor.YELLOW));
        })
        .build();
    
    public static final MenuButton DOWN_NAVIGATION = builder(Material.TIPPED_ARROW)
        .name("&r<red>Down")
        .description("Navigate down")
        .sound(Sound.UI_BUTTON_CLICK)
        .onClick(player -> {
            // Navigation functionality to be implemented by menu system
            player.sendMessage(Component.text("Cannot navigate down", NamedTextColor.YELLOW));
        })
        .build();
    
    public static final MenuButton FORWARD = builder(Material.ARROW)
        .name("&r<aqua>Forward")
        .description("Navigate forward")
        .sound(Sound.UI_BUTTON_CLICK)
        .onClick(player -> {
            // Forward navigation to be implemented by menu system
            player.sendMessage(Component.text("Cannot navigate forward", NamedTextColor.YELLOW));
        })
        .build();
    
    public static final MenuButton BACK_NAVIGATION = builder(Material.ARROW)
        .name("&r<gray>Back")
        .description("Navigate back")
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
     * Calculates the back navigation button slot for the given number of menu rows.
     * Back navigation button is positioned at the first slot of the last row.
     *
     * @param rows the number of rows in the menu (3-6)
     * @return the slot number for the back navigation button
     */
    public static int getBackNavigationSlot(int rows) {
        validateRows(rows);
        return (rows - 1) * 9; // First slot of the last row
    }
    
    /**
     * Calculates the up navigation button slot for the given number of menu rows.
     * Up navigation button is always at slot 0 (top-left corner).
     *
     * @param rows the number of rows in the menu (3-6)
     * @return the slot number for the up navigation button
     */
    public static int getUpNavigationSlot(int rows) {
        validateRows(rows);
        return 0; // Always top-left corner
    }
    
    /**
     * Calculates the down navigation button slot for the given number of menu rows.
     * Down navigation button is positioned at the first slot of the last row.
     *
     * @param rows the number of rows in the menu (3-6)
     * @return the slot number for the down navigation button
     */
    public static int getDownNavigationSlot(int rows) {
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
            validateSlotCalculation("BackNavigation", rows, getBackNavigationSlot(rows), expectedBackNav);
            validateSlotCalculation("UpNavigation", rows, getUpNavigationSlot(rows), expectedUpNav);
            validateSlotCalculation("DownNavigation", rows, getDownNavigationSlot(rows), expectedDownNav);
            
            // Validate slot bounds (must be within 0 to totalSlots-1)
            validateSlotBounds(rows, actualTotalSlots, "Close", getCloseSlot(rows));
            validateSlotBounds(rows, actualTotalSlots, "Back", getBackSlot(rows));
            validateSlotBounds(rows, actualTotalSlots, "Search", getSearchSlot(rows));
            validateSlotBounds(rows, actualTotalSlots, "Sort", getSortSlot(rows));
            validateSlotBounds(rows, actualTotalSlots, "Filter", getFilterSlot(rows));
            validateSlotBounds(rows, actualTotalSlots, "Forward", getForwardSlot(rows));
            validateSlotBounds(rows, actualTotalSlots, "BackNavigation", getBackNavigationSlot(rows));
            validateSlotBounds(rows, actualTotalSlots, "UpNavigation", getUpNavigationSlot(rows));
            validateSlotBounds(rows, actualTotalSlots, "DownNavigation", getDownNavigationSlot(rows));
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
            .name("&r<red>Close")
            .description("Click to close this menu")
            .sound(Sound.UI_BUTTON_CLICK)
            .onClick(player -> player.closeInventory())
            .slot(getCloseSlot(rows))
            .anchor() // Automatically anchor control buttons
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
                // FIXED: Use NavigationService for proper back navigation
                try {
                    // Try to get NavigationService from menu context and navigate back
                    if (navigateBack(player)) {
                        return; // Successfully navigated back
                    }
                } catch (Exception e) {
                    // Fallback if navigation service is not available
                    System.err.println("Failed to navigate back using NavigationService: " + e.getMessage());
                }
                
                // Fallback: close inventory if navigation service fails
                player.closeInventory();
            })
            .slot(getBackSlot(rows))
            .anchor() // Automatically anchor control buttons
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
     * Creates a back navigation button with automatic positioning for the given menu size.
     *
     * @param rows the number of rows in the menu (3-6)
     * @return a positioned back navigation button
     */
    public static MenuButton createPositionedBackNavigation(int rows) {
        return builder(Material.ARROW)
            .name("&r<gray>Back")
            .description("Navigate back")
            .sound(Sound.UI_BUTTON_CLICK)
            .onClick(player -> {
                // FIXED: Use NavigationService for proper back navigation
                try {
                    // Try to get NavigationService from menu context and navigate back
                    if (navigateBack(player)) {
                        return; // Successfully navigated back
                    }
                } catch (Exception e) {
                    // Fallback if navigation service is not available
                    System.err.println("Failed to navigate back using NavigationService: " + e.getMessage());
                }
                
                // Fallback: close inventory if navigation service fails
                player.closeInventory();
            })
            .slot(getBackNavigationSlot(rows))
            .build();
    }
    
    /**
     * Creates an up navigation button with automatic positioning for the given menu size.
     *
     * @param rows the number of rows in the menu (3-6)
     * @return a positioned up navigation button
     */
    public static MenuButton createPositionedUpNavigation(int rows) {
        return builder(Material.SPECTRAL_ARROW)
            .name("&r<green>Up")
            .description("Navigate up")
            .sound(Sound.UI_BUTTON_CLICK)
            .onClick(player -> player.sendMessage(Component.text("Cannot navigate up", NamedTextColor.YELLOW)))
            .slot(getUpNavigationSlot(rows))
            .anchor() // Automatically anchor control buttons
            .build();
    }
    
    /**
     * Creates a down navigation button with automatic positioning for the given menu size.
     *
     * @param rows the number of rows in the menu (3-6)
     * @return a positioned down navigation button
     */
    public static MenuButton createPositionedDownNavigation(int rows) {
        return builder(Material.TIPPED_ARROW)
            .name("&r<red>Down")
            .description("Navigate down")
            .sound(Sound.UI_BUTTON_CLICK)
            .onClick(player -> player.sendMessage(Component.text("Cannot navigate down", NamedTextColor.YELLOW)))
            .slot(getDownNavigationSlot(rows))
            .anchor() // Automatically anchor control buttons
            .build();
    }
    
    /**
     * Helper method to navigate back using NavigationService.
     * FIXED: Proper NavigationService integration for back navigation.
     *
     * @param player the player to navigate back
     * @return true if navigation was successful, false otherwise
     */
    private static boolean navigateBack(Player player) {
        try {
            // Try to access NavigationService via Bukkit's ServicesManager
            org.bukkit.plugin.ServicesManager servicesManager = org.bukkit.Bukkit.getServicesManager();
            sh.harold.fulcrum.api.menu.NavigationService navigationService =
                servicesManager.load(sh.harold.fulcrum.api.menu.NavigationService.class);
            
            if (navigationService != null && navigationService.canNavigateBack(player)) {
                // Get the previous menu from navigation history
                Optional<sh.harold.fulcrum.api.menu.Menu> previousMenu = navigationService.popMenu(player);
                if (previousMenu.isPresent()) {
                    // Try to access MenuService to open the previous menu
                    sh.harold.fulcrum.api.menu.MenuService menuService =
                        servicesManager.load(sh.harold.fulcrum.api.menu.MenuService.class);
                    
                    if (menuService != null) {
                        // Open the previous menu
                        menuService.openMenu(previousMenu.get(), player);
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // Service not available or error occurred - will fallback to closing inventory
            System.err.println("NavigationService not available for back navigation: " + e.getMessage());
        }
        
        return false;
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
         * Automatically adds &r prefix to prevent italicization.
         *
         * @param name the display name
         * @return this builder
         */
        public Builder name(String name) {
            if (name != null) {
                // Automatically prepend &r to prevent italicization unless already present
                String processedName = name.startsWith("&r") ? name : "&r" + name;
                String converted = convertLegacyColors(processedName);
                this.name = miniMessage.deserialize(converted);
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
         * 
         * @param slot the slot position (0-53 for double chest)
         * @return this builder
         */
        public Builder slot(int slot) {
            this.slot = slot;
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