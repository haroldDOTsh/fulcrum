package sh.harold.fulcrum.api.menu.impl;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import sh.harold.fulcrum.api.menu.*;
import sh.harold.fulcrum.api.menu.component.MenuItem;
import sh.harold.fulcrum.api.rank.RankUtils;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Default implementation of MenuRegistry.
 * Manages menu templates and provides factory methods for menu creation.
 */
public class DefaultMenuRegistry implements MenuRegistry {
    
    private final Map<String, MenuTemplate> templates = new ConcurrentHashMap<>();
    private final Map<Plugin, Set<String>> pluginTemplates = new ConcurrentHashMap<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    
    @Override
    public void registerTemplate(String templateId, MenuTemplate template, Plugin plugin) {
        Objects.requireNonNull(templateId, "Template ID cannot be null");
        Objects.requireNonNull(template, "Template cannot be null");
        Objects.requireNonNull(plugin, "Plugin cannot be null");
        
        if (templates.containsKey(templateId)) {
            throw new IllegalArgumentException("Template with ID '" + templateId + "' is already registered");
        }
        
        templates.put(templateId, template);
        pluginTemplates.computeIfAbsent(plugin, k -> ConcurrentHashMap.newKeySet()).add(templateId);
    }
    
    @Override
    public boolean unregisterTemplate(String templateId) {
        Objects.requireNonNull(templateId, "Template ID cannot be null");
        
        MenuTemplate template = templates.remove(templateId);
        if (template != null) {
            // Remove from plugin mapping
            pluginTemplates.values().forEach(set -> set.remove(templateId));
            return true;
        }
        return false;
    }
    
    @Override
    public int unregisterTemplates(Plugin plugin) {
        Objects.requireNonNull(plugin, "Plugin cannot be null");
        
        Set<String> templateIds = pluginTemplates.remove(plugin);
        if (templateIds != null) {
            templateIds.forEach(id -> templates.remove(id));
            return templateIds.size();
        }
        return 0;
    }
    
    @Override
    public Optional<MenuTemplate> getTemplate(String templateId) {
        Objects.requireNonNull(templateId, "Template ID cannot be null");
        return Optional.ofNullable(templates.get(templateId));
    }
    
    @Override
    public boolean hasTemplate(String templateId) {
        Objects.requireNonNull(templateId, "Template ID cannot be null");
        return templates.containsKey(templateId);
    }
    
    @Override
    public Set<String> getTemplateIds() {
        return Collections.unmodifiableSet(new HashSet<>(templates.keySet()));
    }
    
    @Override
    public Collection<String> getTemplatesByPlugin(Plugin plugin) {
        Objects.requireNonNull(plugin, "Plugin cannot be null");
        
        Set<String> ids = pluginTemplates.get(plugin);
        return ids != null ? Collections.unmodifiableSet(ids) : Collections.emptySet();
    }
    
    @Override
    public CompletableFuture<Menu> openTemplate(String templateId, Player player, MenuContext context) {
        Objects.requireNonNull(templateId, "Template ID cannot be null");
        Objects.requireNonNull(player, "Player cannot be null");
        
        MenuTemplate template = templates.get(templateId);
        if (template == null) {
            throw new IllegalArgumentException("Template with ID '" + templateId + "' not found");
        }
        
        // Check rank-based access
        String permission = template.getRequiredPermission();
        if (permission != null) {
            // Check rank based on permission type
            boolean hasAccess = false;
            if (permission.contains("admin") || permission.contains("staff")) {
                hasAccess = RankUtils.isAdmin(player);
            } else {
                // For general permissions, allow DEFAULT rank (all players)
                hasAccess = true;
            }
            
            if (!hasAccess) {
                return CompletableFuture.failedFuture(
                    new SecurityException("Player does not have the required rank to open this menu")
                );
            }
        }
        
        // Build and open the menu
        return template.build(player, context).thenCompose(menu -> {
            // Get menu service to open the menu
            Plugin plugin = template.getOwnerPlugin();
            if (plugin.isEnabled()) {
                MenuService menuService = plugin.getServer().getServicesManager()
                    .load(MenuService.class);
                if (menuService != null) {
                    return menuService.openMenu(menu, player).thenApply(v -> menu);
                }
            }
            return CompletableFuture.completedFuture(menu);
        });
    }
    
    @Override
    public CompletableFuture<Menu> createFromTemplate(String templateId, MenuContext context) {
        Objects.requireNonNull(templateId, "Template ID cannot be null");
        
        MenuTemplate template = templates.get(templateId);
        if (template == null) {
            throw new IllegalArgumentException("Template with ID '" + templateId + "' not found");
        }
        
        // Build without a specific player (template should handle null player)
        return template.build(null, context);
    }
    
    @Override
    public void registerListTemplate(String templateId, Component title, 
                                   Function<Player, Collection<? extends MenuItem>> itemProvider, 
                                   Plugin plugin) {
        Objects.requireNonNull(itemProvider, "Item provider cannot be null");
        
        SimpleListTemplate template = new SimpleListTemplate(
            templateId, title, plugin, itemProvider
        );
        
        registerTemplate(templateId, template, plugin);
    }
    
    @Override
    public void registerListTemplate(String templateId, String title,
                                   Function<Player, Collection<? extends MenuItem>> itemProvider,
                                   Plugin plugin) {
        Component titleComponent = miniMessage.deserialize(title);
        registerListTemplate(templateId, titleComponent, itemProvider, plugin);
    }
    
    @Override
    public void clearRegistry() {
        templates.clear();
        pluginTemplates.clear();
    }
    
    /**
     * Simple implementation of a list menu template.
     */
    private class SimpleListTemplate implements MenuTemplate {
        private final String id;
        private final Component title;
        private final Plugin plugin;
        private final Function<Player, Collection<? extends MenuItem>> itemProvider;
        
        SimpleListTemplate(String id, Component title, Plugin plugin,
                          Function<Player, Collection<? extends MenuItem>> itemProvider) {
            this.id = id;
            this.title = title;
            this.plugin = plugin;
            this.itemProvider = itemProvider;
        }
        
        @Override
        public String getId() {
            return id;
        }
        
        @Override
        public Component getDisplayName() {
            return title;
        }
        
        @Override
        public Component getDescription() {
            return Component.text("Simple list menu template");
        }
        
        @Override
        public Plugin getOwnerPlugin() {
            return plugin;
        }
        
        @Override
        public CompletableFuture<Menu> build(Player player, MenuContext context) {
            return CompletableFuture.supplyAsync(() -> {
                // Get menu service
                MenuService menuService = plugin.getServer().getServicesManager()
                    .load(MenuService.class);
                
                if (menuService == null) {
                    throw new IllegalStateException("MenuService not available");
                }
                
                // Create list menu builder
                ListMenuBuilder builder = menuService.createListMenu()
                    .title(title);
                
                // Add items if player is provided
                if (player != null) {
                    Collection<? extends MenuItem> items = itemProvider.apply(player);
                    builder.addItems(items);
                }
                
                // Build without opening
                return builder.buildAsync().join();
            });
        }
        
        @Override
        public MenuType getType() {
            return MenuType.LIST;
        }
        
        @Override
        public String getRequiredPermission() {
            return null;
        }
    }
    
}