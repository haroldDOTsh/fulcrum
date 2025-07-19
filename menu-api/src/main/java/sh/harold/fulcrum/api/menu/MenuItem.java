package sh.harold.fulcrum.api.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Represents an individual item within a menu.
 * Menu items are immutable once created and contain both the visual representation
 * and the behavior when clicked.
 */
public interface MenuItem {

    // ===== MENU UTILITY CONSTANTS =====

    // === NAVIGATION UTILITIES ===
    MenuItem CLOSE_MENU = createUtility("close", Material.BARRIER, "Close Menu", MenuClickHandlers.CLOSE);
    MenuItem BACK_BUTTON = createUtility("back", Material.ARROW, "Go Back", MenuClickHandlers.BACK);
    MenuItem HOME_BUTTON = createUtility("home", Material.BEACON, "Main Menu", MenuClickHandlers.HOME);

    // === INFORMATION UTILITIES ===
    MenuItem INFO_PANEL = createUtility("info", Material.BOOK, "Information", MenuClickHandlers.INFO);
    MenuItem HELP_TOOLTIP = createUtility("help", Material.PAPER, "Help & Tips", MenuClickHandlers.HELP);

    // === FILTERING & SORTING UTILITIES ===
    MenuItem FILTER_ALL = createUtility("filter_all", Material.COMPASS, "Show All", MenuClickHandlers.FILTER_CLEAR);
    MenuItem FILTER_TOGGLE = createUtility("filter_toggle", Material.LEVER, "Toggle Filter", MenuClickHandlers.FILTER_TOGGLE);
    MenuItem SORT_AZ = createUtility("sort_az", Material.ENCHANTED_BOOK, "Sort A-Z", MenuClickHandlers.SORT_ALPHABETICAL);
    MenuItem SORT_DATE = createUtility("sort_date", Material.CLOCK, "Sort by Date", MenuClickHandlers.SORT_DATE);
    MenuItem SORT_VALUE = createUtility("sort_value", Material.GOLD_INGOT, "Sort by Value", MenuClickHandlers.SORT_VALUE);

    // === SEARCH UTILITIES ===
    MenuItem SEARCH_INPUT = createUtility("search", Material.SPYGLASS, "Search Items", MenuClickHandlers.SEARCH);
    MenuItem CLEAR_SEARCH = createUtility("clear_search", Material.BARRIER, "Clear Search", MenuClickHandlers.SEARCH_CLEAR);

    // === CONFIRMATION UTILITIES ===
    MenuItem CONFIRM_YES = createUtility("confirm_yes", Material.GREEN_WOOL, "Confirm", MenuClickHandlers.CONFIRM);
    MenuItem CONFIRM_NO = createUtility("confirm_no", Material.RED_WOOL, "Cancel", MenuClickHandlers.CANCEL);

    // === PAGINATION UTILITIES (Auto-managed) ===
    MenuItem NEXT_PAGE = createUtility("next_page", Material.ARROW, "Next Page", MenuClickHandlers.NEXT_PAGE);
    MenuItem PREV_PAGE = createUtility("prev_page", Material.ARROW, "Previous Page", MenuClickHandlers.PREV_PAGE);
    MenuItem PAGE_INFO = createUtility("page_info", Material.PAPER, "Page Info", MenuClickHandlers.PAGE_INFO);

    /**
     * Creates a utility MenuItem with standard properties.
     *
     * @param utilityId the utility identifier
     * @param material the material for the ItemStack
     * @param displayName the display name
     * @param clickHandler the click handler
     * @return a new utility MenuItem
     */
    static MenuItem createUtility(String utilityId, Material material, String displayName, ClickHandler clickHandler) {
        return new UtilityMenuItem(utilityId, material, displayName, clickHandler);
    }

    /**
     * Default implementation of MenuItem for utility items.
     */
    final class UtilityMenuItem implements MenuItem {
        private final String utilityId;
        private final ItemStack itemStack;
        private final ClickHandler clickHandler;
        private final Component displayName;
        private final MenuItemProperties properties;
        private int slot = -1; // Will be set when assigned to a menu

        private UtilityMenuItem(String utilityId, Material material, String displayName, ClickHandler clickHandler) {
            this.utilityId = utilityId;
            this.itemStack = new ItemStack(material);
            this.displayName = Component.text(displayName);
            this.clickHandler = clickHandler;
            this.properties = new UtilityMenuItemProperties(utilityId);
        }

        @Override
        public ItemStack getItemStack() {
            return itemStack.clone();
        }

        @Override
        public Component getDisplayName() {
            return displayName;
        }

        @Override
        public List<Component> getLore() {
            return List.of(); // Utilities typically don't have lore
        }

        @Override
        public int getSlot() {
            return slot;
        }

        @Override
        public ClickHandler getClickHandler() {
            return clickHandler;
        }

        @Override
        public boolean isClickable() {
            return true;
        }

        @Override
        public boolean isVisible() {
            return true;
        }

        @Override
        public MenuItemProperties getProperties() {
            return properties;
        }

        @Override
        public boolean hasProperty(String property) {
            return properties.hasProperty(property);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getProperty(String property, T defaultValue) {
            Object value = properties.getProperty(property);
            return value != null ? (T) value : defaultValue;
        }

        @Override
        public MenuItem copyWithSlot(int newSlot) {
            UtilityMenuItem copy = new UtilityMenuItem(utilityId, itemStack.getType(), displayName.toString(), clickHandler);
            copy.slot = newSlot;
            return copy;
        }

        @Override
        public MenuItem copyWithItemStack(ItemStack newItemStack) {
            UtilityMenuItem copy = new UtilityMenuItem(utilityId, newItemStack.getType(), displayName.toString(), clickHandler);
            copy.slot = this.slot;
            return copy;
        }

        @Override
        public MenuItem copyWithClickHandler(ClickHandler newClickHandler) {
            UtilityMenuItem copy = new UtilityMenuItem(utilityId, itemStack.getType(), displayName.toString(), newClickHandler);
            copy.slot = this.slot;
            return copy;
        }

        @Override
        public void validate() {
            if (utilityId == null || utilityId.trim().isEmpty()) {
                throw new IllegalStateException("Utility ID cannot be null or empty");
            }
            if (itemStack == null) {
                throw new IllegalStateException("ItemStack cannot be null");
            }
            if (clickHandler == null) {
                throw new IllegalStateException("ClickHandler cannot be null");
            }
        }

        /**
         * Simple properties implementation for utility items.
         */
        private static class UtilityMenuItemProperties implements MenuItemProperties {
            private final Map<String, Object> properties = new HashMap<>();

            public UtilityMenuItemProperties(String utilityId) {
                properties.put("utility.id", utilityId);
                properties.put("utility.type", "standard");
                properties.put("auto.managed", true);
            }

            @Override
            public Object getProperty(String key) {
                return properties.get(key);
            }

            @Override
            public void setProperty(String key, Object value) {
                properties.put(key, value);
            }

            @Override
            public boolean hasProperty(String key) {
                return properties.containsKey(key);
            }

            @Override
            public void removeProperty(String key) {
                properties.remove(key);
            }

            @Override
            public java.util.Set<String> getPropertyKeys() {
                return new HashSet<>(properties.keySet());
            }
        }
    }

    /**
     * Placeholder for standard menu click handlers.
     * These will be implemented in Phase 3 when the utility system is complete.
     */
    final class MenuClickHandlers {
        public static final ClickHandler CLOSE = context -> context.closeMenu();
        public static final ClickHandler BACK = context -> CompletableFuture.completedFuture(null); // Placeholder
        public static final ClickHandler HOME = context -> CompletableFuture.completedFuture(null); // Placeholder
        public static final ClickHandler INFO = context -> CompletableFuture.completedFuture(null); // Placeholder
        public static final ClickHandler HELP = context -> CompletableFuture.completedFuture(null); // Placeholder
        public static final ClickHandler FILTER_CLEAR = context -> CompletableFuture.completedFuture(null); // Placeholder
        public static final ClickHandler FILTER_TOGGLE = context -> CompletableFuture.completedFuture(null); // Placeholder
        public static final ClickHandler SORT_ALPHABETICAL = context -> CompletableFuture.completedFuture(null); // Placeholder
        public static final ClickHandler SORT_DATE = context -> CompletableFuture.completedFuture(null); // Placeholder
        public static final ClickHandler SORT_VALUE = context -> CompletableFuture.completedFuture(null); // Placeholder
        public static final ClickHandler SEARCH = context -> CompletableFuture.completedFuture(null); // Placeholder
        public static final ClickHandler SEARCH_CLEAR = context -> CompletableFuture.completedFuture(null); // Placeholder
        public static final ClickHandler CONFIRM = context -> CompletableFuture.completedFuture(null); // Placeholder
        public static final ClickHandler CANCEL = context -> context.closeMenu();
        public static final ClickHandler NEXT_PAGE = context -> CompletableFuture.completedFuture(null); // Placeholder
        public static final ClickHandler PREV_PAGE = context -> CompletableFuture.completedFuture(null); // Placeholder
        public static final ClickHandler PAGE_INFO = context -> CompletableFuture.completedFuture(null); // Placeholder

        private MenuClickHandlers() {} // Utility class
    }

    /**
     * Gets the ItemStack representation of this menu item.
     * 
     * @return the ItemStack for this menu item
     */
    ItemStack getItemStack();

    /**
     * Gets the display name of this menu item.
     * 
     * @return the display name as a Component
     */
    Component getDisplayName();

    /**
     * Gets the lore (description) of this menu item.
     * 
     * @return the lore as a list of Components
     */
    List<Component> getLore();

    /**
     * Gets the slot position of this menu item.
     * 
     * @return the slot position (0-based)
     */
    int getSlot();

    /**
     * Gets the click handler for this menu item.
     * 
     * @return the click handler
     */
    ClickHandler getClickHandler();

    /**
     * Checks if this menu item is clickable.
     * 
     * @return true if clickable, false otherwise
     */
    boolean isClickable();

    /**
     * Checks if this menu item is visible.
     * 
     * @return true if visible, false otherwise
     */
    boolean isVisible();

    /**
     * Gets the menu item properties.
     * 
     * @return the menu item properties
     */
    MenuItemProperties getProperties();

    /**
     * Checks if this menu item has a specific property.
     * 
     * @param property the property to check
     * @return true if the property exists, false otherwise
     */
    boolean hasProperty(String property);

    /**
     * Gets a property value from this menu item.
     * 
     * @param property the property name
     * @param defaultValue the default value if the property doesn't exist
     * @param <T> the type of the property value
     * @return the property value or default value
     */
    <T> T getProperty(String property, T defaultValue);

    /**
     * Creates a copy of this menu item with a new slot position.
     * 
     * @param newSlot the new slot position
     * @return a new menu item instance with the same properties but different slot
     */
    MenuItem copyWithSlot(int newSlot);

    /**
     * Creates a copy of this menu item with a new ItemStack.
     * 
     * @param newItemStack the new ItemStack
     * @return a new menu item instance with the new ItemStack
     */
    MenuItem copyWithItemStack(ItemStack newItemStack);

    /**
     * Creates a copy of this menu item with a new click handler.
     * 
     * @param newClickHandler the new click handler
     * @return a new menu item instance with the new click handler
     */
    MenuItem copyWithClickHandler(ClickHandler newClickHandler);

    /**
     * Validates that this menu item is properly configured.
     * 
     * @throws IllegalStateException if the menu item is invalid
     */
    void validate();

    /**
     * Interface for handling menu item clicks.
     */
    interface ClickHandler {
        /**
         * Called when this menu item is clicked.
         * 
         * @param context the click context containing information about the click
         * @return a CompletableFuture that completes when the click is handled
         */
        CompletableFuture<Void> onClick(ClickContext context);
    }

    /**
     * Context information for menu item clicks.
     */
    interface ClickContext {
        /**
         * Gets the player who clicked the item.
         * 
         * @return the player as a Bukkit Player
         */
        org.bukkit.entity.Player getPlayer();

        /**
         * Gets the menu that contains this item.
         * 
         * @return the parent menu
         */
        Menu getMenu();

        /**
         * Gets the menu item that was clicked.
         * 
         * @return the clicked menu item
         */
        MenuItem getMenuItem();

        /**
         * Gets the slot that was clicked.
         * 
         * @return the clicked slot
         */
        int getSlot();

        /**
         * Gets the type of click performed.
         * 
         * @return the click type
         */
        org.bukkit.event.inventory.ClickType getClickType();

        /**
         * Gets the ItemStack that was clicked.
         * 
         * @return the clicked ItemStack
         */
        ItemStack getClickedItem();

        /**
         * Checks if the click should be cancelled.
         * 
         * @return true if the click should be cancelled, false otherwise
         */
        boolean isCancelled();

        /**
         * Sets whether the click should be cancelled.
         * 
         * @param cancelled true to cancel the click, false to allow it
         */
        void setCancelled(boolean cancelled);

        /**
         * Closes the menu for the player.
         * 
         * @return a CompletableFuture that completes when the menu is closed
         */
        CompletableFuture<Void> closeMenu();

        /**
         * Refreshes the menu for the player.
         * 
         * @return a CompletableFuture that completes when the menu is refreshed
         */
        CompletableFuture<Void> refreshMenu();

        /**
         * Opens a new menu for the player.
         * 
         * @param newMenu the new menu to open
         * @return a CompletableFuture that completes when the new menu is opened
         */
        CompletableFuture<Void> openMenu(Menu newMenu);
    }

    /**
     * Interface for menu item properties.
     */
    interface MenuItemProperties {
        /**
         * Gets a property value.
         * 
         * @param key the property key
         * @return the property value, or null if not found
         */
        Object getProperty(String key);

        /**
         * Sets a property value.
         * 
         * @param key the property key
         * @param value the property value
         */
        void setProperty(String key, Object value);

        /**
         * Checks if a property exists.
         * 
         * @param key the property key
         * @return true if the property exists, false otherwise
         */
        boolean hasProperty(String key);

        /**
         * Removes a property.
         * 
         * @param key the property key
         */
        void removeProperty(String key);

        /**
         * Gets all property keys.
         * 
         * @return a set of all property keys
         */
        java.util.Set<String> getPropertyKeys();
    }
}