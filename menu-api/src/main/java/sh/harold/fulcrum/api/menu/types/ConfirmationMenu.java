package sh.harold.fulcrum.api.menu.types;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import sh.harold.fulcrum.api.menu.Menu;
import sh.harold.fulcrum.api.menu.MenuItem;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Represents a confirmation menu with confirm/cancel options.
 * This menu type is designed for simple yes/no decisions and provides
 * a standardized interface for confirmation dialogs.
 * 
 * <p>Confirmation menus typically have a small size (usually 27 slots)
 * and contain confirm and cancel buttons with descriptive information.
 */
public interface ConfirmationMenu extends Menu {

    /**
     * Gets the confirmation message displayed to the user.
     * 
     * @return the confirmation message
     */
    Component getConfirmationMessage();

    /**
     * Gets the slot where the confirm button is located.
     * 
     * @return the confirm button slot
     */
    int getConfirmSlot();

    /**
     * Gets the slot where the cancel button is located.
     * 
     * @return the cancel button slot
     */
    int getCancelSlot();

    /**
     * Gets the slot where the confirmation message is displayed.
     * 
     * @return the confirmation message slot, or -1 if not set
     */
    int getConfirmationMessageSlot();

    /**
     * Gets the ItemStack used for the confirm button.
     * 
     * @return the confirm button ItemStack
     */
    ItemStack getConfirmButton();

    /**
     * Gets the ItemStack used for the cancel button.
     * 
     * @return the cancel button ItemStack
     */
    ItemStack getCancelButton();

    /**
     * Gets the ItemStack used for displaying the confirmation message.
     * 
     * @return the confirmation message ItemStack
     */
    ItemStack getConfirmationMessageButton();

    /**
     * Gets the confirm action handler.
     * 
     * @return the confirm action handler
     */
    ConfirmationAction getConfirmAction();

    /**
     * Gets the cancel action handler.
     * 
     * @return the cancel action handler
     */
    ConfirmationAction getCancelAction();

    /**
     * Gets the timeout for this confirmation menu in milliseconds.
     * 
     * @return the timeout in milliseconds, or -1 if no timeout
     */
    long getTimeoutMs();

    /**
     * Gets the action to perform when the menu times out.
     * 
     * @return the timeout action, or null if no timeout action
     */
    ConfirmationAction getTimeoutAction();

    /**
     * Checks if this confirmation menu has a timeout.
     * 
     * @return true if the menu has a timeout, false otherwise
     */
    boolean hasTimeout();

    /**
     * Gets the time when this confirmation menu was created.
     * 
     * @return the creation time in milliseconds since epoch
     */
    long getCreatedAt();

    /**
     * Checks if this confirmation menu has expired.
     * 
     * @return true if the menu has expired, false otherwise
     */
    boolean isExpired();

    /**
     * Interface for confirmation actions.
     */
    interface ConfirmationAction {
        /**
         * Executes the confirmation action.
         * 
         * @param context the confirmation context
         * @return a CompletableFuture that completes when the action is executed
         */
        CompletableFuture<Void> execute(ConfirmationContext context);
    }

    /**
     * Context information for confirmation actions.
     */
    interface ConfirmationContext {
        /**
         * Gets the player who performed the action.
         * 
         * @return the player
         */
        Player getPlayer();

        /**
         * Gets the confirmation menu.
         * 
         * @return the confirmation menu
         */
        ConfirmationMenu getMenu();

        /**
         * Gets the type of action performed.
         * 
         * @return the action type
         */
        ActionType getActionType();

        /**
         * Closes the confirmation menu.
         * 
         * @return a CompletableFuture that completes when the menu is closed
         */
        CompletableFuture<Void> closeMenu();

        /**
         * Opens a new menu for the player.
         * 
         * @param newMenu the new menu to open
         * @return a CompletableFuture that completes when the new menu is opened
         */
        CompletableFuture<Void> openMenu(Menu newMenu);

        /**
         * Gets the time when the action was performed.
         * 
         * @return the action time in milliseconds since epoch
         */
        long getActionTime();

        /**
         * Action types for confirmation menus.
         */
        enum ActionType {
            CONFIRM,
            CANCEL,
            TIMEOUT
        }
    }

    /**
     * Builder interface for creating ConfirmationMenu instances.
     */
    interface Builder {
        /**
         * Sets the title of the confirmation menu.
         * 
         * @param title the menu title
         * @return this builder instance
         */
        Builder title(Component title);

        /**
         * Sets the title of the confirmation menu using a string.
         * 
         * @param title the menu title as a string
         * @return this builder instance
         */
        Builder title(String title);

        /**
         * Sets the size of the confirmation menu.
         * 
         * @param size the menu size
         * @return this builder instance
         */
        Builder size(int size);

        /**
         * Sets the size of the confirmation menu using rows.
         * 
         * @param rows the number of rows
         * @return this builder instance
         */
        Builder rows(int rows);

        /**
         * Sets the owner of the confirmation menu.
         * 
         * @param owner the owner's UUID
         * @return this builder instance
         */
        Builder owner(UUID owner);

        /**
         * Sets the player for the confirmation menu.
         * 
         * @param player the player
         * @return this builder instance
         */
        Builder player(Player player);

        /**
         * Sets the confirmation message.
         * 
         * @param message the confirmation message
         * @return this builder instance
         */
        Builder confirmationMessage(Component message);

        /**
         * Sets the confirmation message using a string.
         * 
         * @param message the confirmation message as a string
         * @return this builder instance
         */
        Builder confirmationMessage(String message);

        /**
         * Sets the confirm button.
         * 
         * @param slot the slot for the button
         * @param itemStack the ItemStack for the button
         * @param action the action to perform when confirmed
         * @return this builder instance
         */
        Builder confirmButton(int slot, ItemStack itemStack, ConfirmationAction action);

        /**
         * Sets the cancel button.
         * 
         * @param slot the slot for the button
         * @param itemStack the ItemStack for the button
         * @param action the action to perform when cancelled
         * @return this builder instance
         */
        Builder cancelButton(int slot, ItemStack itemStack, ConfirmationAction action);

        /**
         * Sets the confirmation message button.
         * 
         * @param slot the slot for the button
         * @param itemStack the ItemStack for the button
         * @return this builder instance
         */
        Builder confirmationMessageButton(int slot, ItemStack itemStack);

        /**
         * Sets the confirm action using a simple consumer.
         * 
         * @param action the action to perform when confirmed
         * @return this builder instance
         */
        Builder onConfirm(Consumer<ConfirmationContext> action);

        /**
         * Sets the cancel action using a simple consumer.
         * 
         * @param action the action to perform when cancelled
         * @return this builder instance
         */
        Builder onCancel(Consumer<ConfirmationContext> action);

        /**
         * Sets the timeout for the confirmation menu.
         * 
         * @param timeoutMs the timeout in milliseconds
         * @return this builder instance
         */
        Builder timeout(long timeoutMs);

        /**
         * Sets the timeout action.
         * 
         * @param action the action to perform when the menu times out
         * @return this builder instance
         */
        Builder onTimeout(Consumer<ConfirmationContext> action);

        /**
         * Sets a property on the menu.
         * 
         * @param key the property key
         * @param value the property value
         * @return this builder instance
         */
        Builder property(String key, Object value);

        /**
         * Builds the confirmation menu instance.
         * 
         * @return the constructed confirmation menu
         */
        ConfirmationMenu build();

        /**
         * Builds the confirmation menu instance asynchronously.
         * 
         * @return a CompletableFuture containing the constructed confirmation menu
         */
        CompletableFuture<ConfirmationMenu> buildAsync();
    }
}