package sh.harold.fulcrum.api.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import sh.harold.fulcrum.api.menu.component.MenuItem;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Registry for managing menu templates and definitions.
 * Allows plugins to register reusable menu configurations that can be
 * instantiated on demand with dynamic data.
 */
public interface MenuRegistry {
    
    /**
     * Registers a menu template with the given ID.
     * Templates can be instantiated multiple times with different data.
     * 
     * @param templateId unique identifier for the template
     * @param template the menu template
     * @param plugin the plugin registering the template
     * @throws IllegalArgumentException if templateId is already registered
     */
    void registerTemplate(String templateId, MenuTemplate template, Plugin plugin);
    
    /**
     * Unregisters a menu template.
     * 
     * @param templateId the template ID to unregister
     * @return true if the template was unregistered, false if not found
     */
    boolean unregisterTemplate(String templateId);
    
    /**
     * Unregisters all templates for a specific plugin.
     * Useful for plugin cleanup on disable.
     * 
     * @param plugin the plugin whose templates to unregister
     * @return the number of templates unregistered
     */
    int unregisterTemplates(Plugin plugin);
    
    /**
     * Gets a menu template by ID.
     * 
     * @param templateId the template ID
     * @return an Optional containing the template if found
     */
    Optional<MenuTemplate> getTemplate(String templateId);
    
    /**
     * Checks if a template is registered.
     * 
     * @param templateId the template ID to check
     * @return true if the template exists
     */
    boolean hasTemplate(String templateId);
    
    /**
     * Gets all registered template IDs.
     * 
     * @return unmodifiable set of template IDs
     */
    Set<String> getTemplateIds();
    
    /**
     * Gets all templates registered by a specific plugin.
     * 
     * @param plugin the plugin
     * @return collection of template IDs registered by the plugin
     */
    Collection<String> getTemplatesByPlugin(Plugin plugin);
    
    /**
     * Opens a menu from a template for a player.
     * 
     * @param templateId the template ID
     * @param player the player to open the menu for
     * @param context optional context data for the menu
     * @return CompletableFuture that completes with the opened menu
     * @throws IllegalArgumentException if template not found
     */
    CompletableFuture<Menu> openTemplate(String templateId, Player player, MenuContext context);
    
    /**
     * Opens a menu from a template with default context.
     * 
     * @param templateId the template ID
     * @param player the player to open the menu for
     * @return CompletableFuture that completes with the opened menu
     */
    default CompletableFuture<Menu> openTemplate(String templateId, Player player) {
        return openTemplate(templateId, player, null);
    }
    
    /**
     * Creates a menu instance from a template without opening it.
     * 
     * @param templateId the template ID
     * @param context optional context data for the menu
     * @return CompletableFuture that completes with the menu instance
     * @throws IllegalArgumentException if template not found
     */
    CompletableFuture<Menu> createFromTemplate(String templateId, MenuContext context);
    
    /**
     * Registers a simple list menu template.
     * 
     * @param templateId the template ID
     * @param title the menu title
     * @param itemProvider function to provide items based on player
     * @param plugin the registering plugin
     */
    void registerListTemplate(String templateId, Component title, 
                             Function<Player, Collection<? extends MenuItem>> itemProvider, 
                             Plugin plugin);
    
    /**
     * Registers a simple list menu template with string title.
     * 
     * @param templateId the template ID
     * @param title the menu title (supports color codes)
     * @param itemProvider function to provide items based on player
     * @param plugin the registering plugin
     */
    void registerListTemplate(String templateId, String title,
                             Function<Player, Collection<? extends MenuItem>> itemProvider,
                             Plugin plugin);
    
    /**
     * Clears all registered templates.
     * Useful for plugin reload scenarios.
     */
    void clearRegistry();
    
    /**
     * Interface for menu templates.
     * Templates define how to build menus with dynamic data.
     */
    interface MenuTemplate {
        /**
         * Gets the template ID.
         * 
         * @return the unique template identifier
         */
        String getId();
        
        /**
         * Gets the template name for display purposes.
         * 
         * @return the template display name
         */
        Component getDisplayName();
        
        /**
         * Gets the template description.
         * 
         * @return the template description
         */
        Component getDescription();
        
        /**
         * Gets the plugin that registered this template.
         * 
         * @return the owner plugin
         */
        Plugin getOwnerPlugin();
        
        /**
         * Builds a menu instance from this template.
         * 
         * @param player the player the menu is for
         * @param context optional context data
         * @return CompletableFuture that completes with the built menu
         */
        CompletableFuture<Menu> build(Player player, MenuContext context);
        
        /**
         * Gets the type of menu this template creates.
         * 
         * @return the menu type
         */
        MenuType getType();
        
        /**
         * Checks if this template requires specific permissions.
         * 
         * @return the required permission or null if none
         */
        String getRequiredPermission();
    }
    
    /**
     * Types of menus that can be created from templates.
     */
    enum MenuType {
        /**
         * List menu with pagination.
         */
        LIST,
        
        /**
         * Custom menu with viewport.
         */
        CUSTOM,
        
        /**
         * Simple chest menu without special features.
         */
        SIMPLE
    }
    
}