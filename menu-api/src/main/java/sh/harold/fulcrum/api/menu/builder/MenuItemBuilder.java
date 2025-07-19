package sh.harold.fulcrum.api.menu.builder;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import sh.harold.fulcrum.api.menu.MenuItem;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Builder interface for creating menu items with a fluent API.
 * This interface provides a type-safe way to construct menu item instances
 * with proper validation and immutable results.
 * 
 * <p>All builders are immutable - each method returns a new builder instance
 * with the specified modification applied.
 */
public interface MenuItemBuilder {

    /**
     * Sets the ItemStack for this menu item.
     * 
     * @param itemStack the ItemStack to use
     * @return a new builder instance with the ItemStack set
     * @throws IllegalArgumentException if itemStack is null
     */
    MenuItemBuilder itemStack(ItemStack itemStack);

    /**
     * Sets the display name for this menu item.
     * 
     * @param displayName the display name
     * @return a new builder instance with the display name set
     * @throws IllegalArgumentException if displayName is null
     */
    MenuItemBuilder displayName(Component displayName);

    /**
     * Sets the display name for this menu item using a string.
     * 
     * @param displayName the display name as a string
     * @return a new builder instance with the display name set
     * @throws IllegalArgumentException if displayName is null
     */
    MenuItemBuilder displayName(String displayName);

    /**
     * Sets the lore for this menu item.
     * 
     * @param lore the lore lines
     * @return a new builder instance with the lore set
     * @throws IllegalArgumentException if lore is null
     */
    MenuItemBuilder lore(List<Component> lore);

    /**
     * Sets the lore for this menu item using strings.
     * 
     * @param lore the lore lines as strings
     * @return a new builder instance with the lore set
     * @throws IllegalArgumentException if lore is null
     */
    MenuItemBuilder lore(String... lore);

    /**
     * Adds a single lore line to this menu item.
     * 
     * @param loreLine the lore line to add
     * @return a new builder instance with the lore line added
     * @throws IllegalArgumentException if loreLine is null
     */
    MenuItemBuilder addLore(Component loreLine);

    /**
     * Adds a single lore line to this menu item using a string.
     * 
     * @param loreLine the lore line to add as a string
     * @return a new builder instance with the lore line added
     * @throws IllegalArgumentException if loreLine is null
     */
    MenuItemBuilder addLore(String loreLine);

    /**
     * Sets the slot position for this menu item.
     * 
     * @param slot the slot position (0-based)
     * @return a new builder instance with the slot set
     * @throws IllegalArgumentException if slot is negative
     */
    MenuItemBuilder slot(int slot);

    /**
     * Sets the click handler for this menu item.
     * 
     * @param clickHandler the click handler
     * @return a new builder instance with the click handler set
     * @throws IllegalArgumentException if clickHandler is null
     */
    MenuItemBuilder clickHandler(MenuItem.ClickHandler clickHandler);

    /**
     * Sets a simple click handler using a lambda or method reference.
     * 
     * @param clickHandler the click handler
     * @return a new builder instance with the click handler set
     * @throws IllegalArgumentException if clickHandler is null
     */
    MenuItemBuilder onClick(java.util.function.Consumer<MenuItem.ClickContext> clickHandler);

    /**
     * Sets an async click handler using a lambda or method reference.
     * 
     * @param clickHandler the async click handler
     * @return a new builder instance with the click handler set
     * @throws IllegalArgumentException if clickHandler is null
     */
    MenuItemBuilder onClickAsync(java.util.function.Function<MenuItem.ClickContext, CompletableFuture<Void>> clickHandler);

    /**
     * Sets whether this menu item is clickable.
     * 
     * @param clickable true if clickable, false otherwise
     * @return a new builder instance with the clickable state set
     */
    MenuItemBuilder clickable(boolean clickable);

    /**
     * Sets whether this menu item is visible.
     * 
     * @param visible true if visible, false otherwise
     * @return a new builder instance with the visible state set
     */
    MenuItemBuilder visible(boolean visible);

    /**
     * Sets a property on this menu item.
     * 
     * @param key the property key
     * @param value the property value
     * @return a new builder instance with the property set
     * @throws IllegalArgumentException if key is null
     */
    MenuItemBuilder property(String key, Object value);

    /**
     * Removes a property from this menu item.
     * 
     * @param key the property key
     * @return a new builder instance with the property removed
     * @throws IllegalArgumentException if key is null
     */
    MenuItemBuilder removeProperty(String key);

    /**
     * Sets the amount for the ItemStack.
     * 
     * @param amount the amount (1-64)
     * @return a new builder instance with the amount set
     * @throws IllegalArgumentException if amount is out of range
     */
    MenuItemBuilder amount(int amount);

    /**
     * Sets the durability for the ItemStack.
     * 
     * @param durability the durability
     * @return a new builder instance with the durability set
     * @throws IllegalArgumentException if durability is negative
     */
    MenuItemBuilder durability(short durability);

    /**
     * Sets whether this menu item should glow (enchantment effect).
     * 
     * @param glow true to add glow effect, false to remove
     * @return a new builder instance with the glow state set
     */
    MenuItemBuilder glow(boolean glow);

    /**
     * Sets custom model data for the ItemStack.
     * 
     * @param customModelData the custom model data
     * @return a new builder instance with the custom model data set
     */
    MenuItemBuilder customModelData(int customModelData);

    /**
     * Sets whether this menu item should be unbreakable.
     * 
     * @param unbreakable true to make unbreakable, false otherwise
     * @return a new builder instance with the unbreakable state set
     */
    MenuItemBuilder unbreakable(boolean unbreakable);

    /**
     * Applies NBT data to the ItemStack.
     * 
     * @param nbtData the NBT data as a string
     * @return a new builder instance with the NBT data applied
     * @throws IllegalArgumentException if nbtData is invalid
     */
    MenuItemBuilder nbt(String nbtData);

    /**
     * Sets a close menu action for this item.
     * When clicked, this item will close the menu.
     * 
     * @return a new builder instance with the close action set
     */
    MenuItemBuilder closeMenu();

    /**
     * Sets a refresh menu action for this item.
     * When clicked, this item will refresh the menu.
     * 
     * @return a new builder instance with the refresh action set
     */
    MenuItemBuilder refreshMenu();

    /**
     * Sets an open menu action for this item.
     * When clicked, this item will open the specified menu.
     * 
     * @param menuId the ID of the menu to open
     * @return a new builder instance with the open menu action set
     * @throws IllegalArgumentException if menuId is null or empty
     */
    MenuItemBuilder openMenu(String menuId);

    /**
     * Sets a command execution action for this item.
     * When clicked, this item will execute the specified command as the player.
     * 
     * @param command the command to execute (without leading slash)
     * @return a new builder instance with the command action set
     * @throws IllegalArgumentException if command is null or empty
     */
    MenuItemBuilder executeCommand(String command);

    /**
     * Sets a console command execution action for this item.
     * When clicked, this item will execute the specified command as console.
     * 
     * @param command the command to execute (without leading slash)
     * @return a new builder instance with the console command action set
     * @throws IllegalArgumentException if command is null or empty
     */
    MenuItemBuilder executeConsoleCommand(String command);

    /**
     * Validates the current builder state.
     * 
     * @throws IllegalStateException if the builder is in an invalid state
     */
    void validate();

    /**
     * Builds the menu item instance.
     * 
     * @return the constructed menu item
     * @throws IllegalStateException if the builder is in an invalid state
     */
    MenuItem build();

    /**
     * Builds the menu item instance asynchronously.
     * 
     * @return a CompletableFuture containing the constructed menu item
     * @throws IllegalStateException if the builder is in an invalid state
     */
    CompletableFuture<MenuItem> buildAsync();

    /**
     * Creates a copy of this builder.
     * 
     * @return a new builder instance with the same state
     */
    MenuItemBuilder copy();

    /**
     * Resets the builder to its initial state.
     *
     * @return a new builder instance in the initial state
     */
    MenuItemBuilder reset();

    // ===== SPECIALIZED BUILDERS =====

    /**
     * Creates a specialized toggle item builder.
     * Toggle items can be in an "on" or "off" state with different appearances.
     *
     * @return a new ToggleItemBuilder instance
     */
    static ToggleItemBuilder toggle() {
        return new DefaultToggleItemBuilder();
    }

    /**
     * Creates a specialized info item builder.
     * Info items are display-only with no click functionality.
     *
     * @return a new InfoItemBuilder instance
     */
    static InfoItemBuilder info() {
        return new DefaultInfoItemBuilder();
    }

    /**
     * Creates a specialized action item builder.
     * Action items perform specific predefined actions when clicked.
     *
     * @return a new ActionItemBuilder instance
     */
    static ActionItemBuilder action() {
        return new DefaultActionItemBuilder();
    }

    /**
     * Specialized builder for toggle items that can be in an "on" or "off" state.
     */
    interface ToggleItemBuilder extends MenuItemBuilder {
        
        /**
         * Sets the initial state of the toggle.
         *
         * @param enabled true for "on" state, false for "off" state
         * @return this builder
         */
        ToggleItemBuilder initialState(boolean enabled);

        /**
         * Sets the ItemStack to use when the toggle is in the "on" state.
         *
         * @param onItemStack the ItemStack for enabled state
         * @return this builder
         */
        ToggleItemBuilder onItemStack(ItemStack onItemStack);

        /**
         * Sets the ItemStack to use when the toggle is in the "off" state.
         *
         * @param offItemStack the ItemStack for disabled state
         * @return this builder
         */
        ToggleItemBuilder offItemStack(ItemStack offItemStack);

        /**
         * Sets the display name to use when the toggle is in the "on" state.
         *
         * @param onDisplayName the display name for enabled state
         * @return this builder
         */
        ToggleItemBuilder onDisplayName(Component onDisplayName);

        /**
         * Sets the display name to use when the toggle is in the "off" state.
         *
         * @param offDisplayName the display name for disabled state
         * @return this builder
         */
        ToggleItemBuilder offDisplayName(Component offDisplayName);

        /**
         * Sets the lore to use when the toggle is in the "on" state.
         *
         * @param onLore the lore for enabled state
         * @return this builder
         */
        ToggleItemBuilder onLore(List<Component> onLore);

        /**
         * Sets the lore to use when the toggle is in the "off" state.
         *
         * @param offLore the lore for disabled state
         * @return this builder
         */
        ToggleItemBuilder offLore(List<Component> offLore);

        /**
         * Sets the action to perform when toggled to the "on" state.
         *
         * @param onToggleAction the action for enabling
         * @return this builder
         */
        ToggleItemBuilder onToggle(java.util.function.Consumer<MenuItem.ClickContext> onToggleAction);

        /**
         * Sets the action to perform when toggled to the "off" state.
         *
         * @param offToggleAction the action for disabling
         * @return this builder
         */
        ToggleItemBuilder offToggle(java.util.function.Consumer<MenuItem.ClickContext> offToggleAction);

        /**
         * Sets whether the toggle state should persist across menu refreshes.
         *
         * @param persistent true to persist state, false otherwise
         * @return this builder
         */
        ToggleItemBuilder persistent(boolean persistent);
    }

    /**
     * Specialized builder for info items that display information without click functionality.
     */
    interface InfoItemBuilder extends MenuItemBuilder {
        
        /**
         * Sets the info category for styling purposes.
         *
         * @param category the info category
         * @return this builder
         */
        InfoItemBuilder category(InfoCategory category);

        /**
         * Sets the primary value to display.
         *
         * @param value the primary value
         * @return this builder
         */
        InfoItemBuilder value(Object value);

        /**
         * Sets the label for the value.
         *
         * @param label the value label
         * @return this builder
         */
        InfoItemBuilder label(String label);

        /**
         * Sets the format string for displaying the value.
         *
         * @param format the format string (e.g., "Count: %d")
         * @return this builder
         */
        InfoItemBuilder format(String format);

        /**
         * Sets whether this info item should update automatically.
         *
         * @param autoUpdate true to auto-update, false otherwise
         * @return this builder
         */
        InfoItemBuilder autoUpdate(boolean autoUpdate);

        /**
         * Sets the update interval in milliseconds for auto-updating items.
         *
         * @param intervalMs the update interval
         * @return this builder
         */
        InfoItemBuilder updateInterval(long intervalMs);

        /**
         * Sets a supplier for dynamic values.
         *
         * @param valueSupplier the value supplier
         * @return this builder
         */
        InfoItemBuilder dynamicValue(java.util.function.Supplier<Object> valueSupplier);

        /**
         * Categories for info items to determine styling and behavior.
         */
        enum InfoCategory {
            /** General information */
            GENERAL,
            /** Statistical information */
            STATISTICS,
            /** Status information */
            STATUS,
            /** Progress information */
            PROGRESS,
            /** Warning information */
            WARNING,
            /** Error information */
            ERROR,
            /** Success information */
            SUCCESS
        }
    }

    /**
     * Specialized builder for action items that perform specific actions when clicked.
     */
    interface ActionItemBuilder extends MenuItemBuilder {
        
        /**
         * Sets the action type for this item.
         *
         * @param actionType the action type
         * @return this builder
         */
        ActionItemBuilder actionType(ActionType actionType);

        /**
         * Sets the confirmation requirement for this action.
         *
         * @param requireConfirmation true to require confirmation, false otherwise
         * @return this builder
         */
        ActionItemBuilder requireConfirmation(boolean requireConfirmation);

        /**
         * Sets the confirmation message to display.
         *
         * @param confirmationMessage the confirmation message
         * @return this builder
         */
        ActionItemBuilder confirmationMessage(Component confirmationMessage);

        /**
         * Sets the cooldown period for this action in milliseconds.
         *
         * @param cooldownMs the cooldown period
         * @return this builder
         */
        ActionItemBuilder cooldown(long cooldownMs);

        /**
         * Sets whether this action should be logged.
         *
         * @param logAction true to log the action, false otherwise
         * @return this builder
         */
        ActionItemBuilder logAction(boolean logAction);

        /**
         * Sets the permission required to perform this action.
         *
         * @param permission the required permission
         * @return this builder
         */
        ActionItemBuilder permission(String permission);

        /**
         * Sets the cost for performing this action.
         *
         * @param cost the action cost
         * @return this builder
         */
        ActionItemBuilder cost(double cost);

        /**
         * Sets a pre-action validator.
         *
         * @param validator the validator function
         * @return this builder
         */
        ActionItemBuilder validator(java.util.function.Predicate<MenuItem.ClickContext> validator);

        /**
         * Sets a post-action callback.
         *
         * @param callback the callback function
         * @return this builder
         */
        ActionItemBuilder onSuccess(java.util.function.Consumer<MenuItem.ClickContext> callback);

        /**
         * Sets an action failure callback.
         *
         * @param callback the failure callback
         * @return this builder
         */
        ActionItemBuilder onFailure(java.util.function.Consumer<MenuItem.ClickContext> callback);

        /**
         * Types of actions that can be performed.
         */
        enum ActionType {
            /** Generic custom action */
            CUSTOM,
            /** Close menu action */
            CLOSE_MENU,
            /** Refresh menu action */
            REFRESH_MENU,
            /** Open another menu */
            OPEN_MENU,
            /** Execute a command */
            EXECUTE_COMMAND,
            /** Teleport action */
            TELEPORT,
            /** Purchase/transaction action */
            PURCHASE,
            /** Delete/remove action */
            DELETE,
            /** Save/confirm action */
            SAVE,
            /** Cancel action */
            CANCEL
        }
    }

    // Default implementations will be provided in player-core
    final class DefaultToggleItemBuilder implements ToggleItemBuilder {
        // Implementation will be in player-core package
        // This is a placeholder to maintain API contract
        private DefaultToggleItemBuilder() {
            throw new UnsupportedOperationException("Default implementations are in player-core");
        }

        // All MenuItemBuilder methods would be implemented here
        // For brevity, showing the pattern - actual implementation needed
        @Override
        public MenuItemBuilder itemStack(ItemStack itemStack) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public MenuItemBuilder displayName(Component displayName) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public MenuItemBuilder displayName(String displayName) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public MenuItemBuilder lore(List<Component> lore) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public MenuItemBuilder lore(String... lore) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public MenuItemBuilder addLore(Component loreLine) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public MenuItemBuilder addLore(String loreLine) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public MenuItemBuilder slot(int slot) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public MenuItemBuilder clickHandler(MenuItem.ClickHandler clickHandler) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public MenuItemBuilder onClick(java.util.function.Consumer<MenuItem.ClickContext> clickHandler) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public MenuItemBuilder onClickAsync(java.util.function.Function<MenuItem.ClickContext, CompletableFuture<Void>> clickHandler) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public MenuItemBuilder clickable(boolean clickable) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public MenuItemBuilder visible(boolean visible) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public MenuItemBuilder property(String key, Object value) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public MenuItemBuilder removeProperty(String key) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public MenuItemBuilder amount(int amount) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public MenuItemBuilder durability(short durability) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public MenuItemBuilder glow(boolean glow) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public MenuItemBuilder customModelData(int customModelData) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public MenuItemBuilder unbreakable(boolean unbreakable) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public MenuItemBuilder nbt(String nbtData) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public MenuItemBuilder closeMenu() {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public MenuItemBuilder refreshMenu() {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public MenuItemBuilder openMenu(String menuId) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public MenuItemBuilder executeCommand(String command) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public MenuItemBuilder executeConsoleCommand(String command) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public void validate() {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public MenuItem build() {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public CompletableFuture<MenuItem> buildAsync() {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public MenuItemBuilder copy() {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public MenuItemBuilder reset() {
            throw new UnsupportedOperationException("Not implemented");
        }

        // ToggleItemBuilder specific methods
        @Override
        public ToggleItemBuilder initialState(boolean enabled) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public ToggleItemBuilder onItemStack(ItemStack onItemStack) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public ToggleItemBuilder offItemStack(ItemStack offItemStack) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public ToggleItemBuilder onDisplayName(Component onDisplayName) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public ToggleItemBuilder offDisplayName(Component offDisplayName) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public ToggleItemBuilder onLore(List<Component> onLore) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public ToggleItemBuilder offLore(List<Component> offLore) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public ToggleItemBuilder onToggle(java.util.function.Consumer<MenuItem.ClickContext> onToggleAction) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public ToggleItemBuilder offToggle(java.util.function.Consumer<MenuItem.ClickContext> offToggleAction) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public ToggleItemBuilder persistent(boolean persistent) {
            throw new UnsupportedOperationException("Not implemented");
        }
    }

    final class DefaultInfoItemBuilder implements InfoItemBuilder {
        // Similar placeholder pattern - implementation in player-core
        private DefaultInfoItemBuilder() {
            throw new UnsupportedOperationException("Default implementations are in player-core");
        }

        // All methods would be implemented (showing pattern only)
        @Override
        public MenuItemBuilder itemStack(ItemStack itemStack) {
            throw new UnsupportedOperationException("Not implemented");
        }

        // ... other MenuItemBuilder methods ...

        @Override
        public InfoItemBuilder category(InfoCategory category) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public InfoItemBuilder value(Object value) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public InfoItemBuilder label(String label) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public InfoItemBuilder format(String format) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public InfoItemBuilder autoUpdate(boolean autoUpdate) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public InfoItemBuilder updateInterval(long intervalMs) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public InfoItemBuilder dynamicValue(java.util.function.Supplier<Object> valueSupplier) {
            throw new UnsupportedOperationException("Not implemented");
        }

        // Placeholder implementations for all required MenuItemBuilder methods
        @Override public MenuItemBuilder displayName(Component displayName) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder displayName(String displayName) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder lore(List<Component> lore) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder lore(String... lore) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder addLore(Component loreLine) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder addLore(String loreLine) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder slot(int slot) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder clickHandler(MenuItem.ClickHandler clickHandler) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder onClick(java.util.function.Consumer<MenuItem.ClickContext> clickHandler) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder onClickAsync(java.util.function.Function<MenuItem.ClickContext, CompletableFuture<Void>> clickHandler) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder clickable(boolean clickable) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder visible(boolean visible) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder property(String key, Object value) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder removeProperty(String key) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder amount(int amount) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder durability(short durability) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder glow(boolean glow) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder customModelData(int customModelData) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder unbreakable(boolean unbreakable) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder nbt(String nbtData) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder closeMenu() { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder refreshMenu() { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder openMenu(String menuId) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder executeCommand(String command) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder executeConsoleCommand(String command) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public void validate() { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItem build() { throw new UnsupportedOperationException("Not implemented"); }
        @Override public CompletableFuture<MenuItem> buildAsync() { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder copy() { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder reset() { throw new UnsupportedOperationException("Not implemented"); }
    }

    final class DefaultActionItemBuilder implements ActionItemBuilder {
        // Similar placeholder pattern - implementation in player-core
        private DefaultActionItemBuilder() {
            throw new UnsupportedOperationException("Default implementations are in player-core");
        }

        // ActionItemBuilder specific methods
        @Override
        public ActionItemBuilder actionType(ActionType actionType) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public ActionItemBuilder requireConfirmation(boolean requireConfirmation) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public ActionItemBuilder confirmationMessage(Component confirmationMessage) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public ActionItemBuilder cooldown(long cooldownMs) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public ActionItemBuilder logAction(boolean logAction) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public ActionItemBuilder permission(String permission) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public ActionItemBuilder cost(double cost) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public ActionItemBuilder validator(java.util.function.Predicate<MenuItem.ClickContext> validator) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public ActionItemBuilder onSuccess(java.util.function.Consumer<MenuItem.ClickContext> callback) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public ActionItemBuilder onFailure(java.util.function.Consumer<MenuItem.ClickContext> callback) {
            throw new UnsupportedOperationException("Not implemented");
        }

        // Placeholder implementations for all required MenuItemBuilder methods
        @Override public MenuItemBuilder itemStack(ItemStack itemStack) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder displayName(Component displayName) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder displayName(String displayName) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder lore(List<Component> lore) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder lore(String... lore) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder addLore(Component loreLine) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder addLore(String loreLine) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder slot(int slot) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder clickHandler(MenuItem.ClickHandler clickHandler) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder onClick(java.util.function.Consumer<MenuItem.ClickContext> clickHandler) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder onClickAsync(java.util.function.Function<MenuItem.ClickContext, CompletableFuture<Void>> clickHandler) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder clickable(boolean clickable) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder visible(boolean visible) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder property(String key, Object value) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder removeProperty(String key) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder amount(int amount) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder durability(short durability) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder glow(boolean glow) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder customModelData(int customModelData) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder unbreakable(boolean unbreakable) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder nbt(String nbtData) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder closeMenu() { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder refreshMenu() { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder openMenu(String menuId) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder executeCommand(String command) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder executeConsoleCommand(String command) { throw new UnsupportedOperationException("Not implemented"); }
        @Override public void validate() { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItem build() { throw new UnsupportedOperationException("Not implemented"); }
        @Override public CompletableFuture<MenuItem> buildAsync() { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder copy() { throw new UnsupportedOperationException("Not implemented"); }
        @Override public MenuItemBuilder reset() { throw new UnsupportedOperationException("Not implemented"); }
    }
}