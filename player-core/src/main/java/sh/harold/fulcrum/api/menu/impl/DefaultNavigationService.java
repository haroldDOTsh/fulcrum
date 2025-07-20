package sh.harold.fulcrum.api.menu.impl;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.menu.Menu;
import sh.harold.fulcrum.api.menu.NavigationService;
import sh.harold.fulcrum.api.menu.component.MenuButton;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default implementation of NavigationService.
 * Manages menu navigation history and breadcrumb generation.
 */
public class DefaultNavigationService implements NavigationService {
    
    private final Map<UUID, Stack<Menu>> navigationHistory = new ConcurrentHashMap<>();
    private final List<NavigationListener> listeners = new CopyOnWriteArrayList<>();
    private BackButtonConfig defaultBackButton;
    
    @Override
    public void pushMenu(Player player, Menu menu) {
        Objects.requireNonNull(player, "Player cannot be null");
        Objects.requireNonNull(menu, "Menu cannot be null");
        
        Stack<Menu> history = navigationHistory.computeIfAbsent(
            player.getUniqueId(), 
            k -> new Stack<>()
        );
        
        // Don't push the same menu twice in a row
        if (!history.isEmpty() && history.peek().equals(menu)) {
            return;
        }
        
        Menu previousMenu = history.isEmpty() ? null : history.peek();
        history.push(menu);
        
        // Notify listeners
        if (previousMenu != null) {
            listeners.forEach(listener -> 
                listener.onNavigateForward(player, previousMenu, menu)
            );
        }
    }
    
    @Override
    public Optional<Menu> popMenu(Player player) {
        Objects.requireNonNull(player, "Player cannot be null");
        
        Stack<Menu> history = navigationHistory.get(player.getUniqueId());
        if (history == null || history.size() <= 1) {
            return Optional.empty();
        }
        
        Menu currentMenu = history.pop();
        Menu previousMenu = history.peek();
        
        // Notify listeners
        listeners.forEach(listener -> 
            listener.onNavigateBack(player, currentMenu, previousMenu)
        );
        
        return Optional.of(previousMenu);
    }
    
    @Override
    public Optional<Menu> getCurrentMenu(Player player) {
        Objects.requireNonNull(player, "Player cannot be null");
        
        Stack<Menu> history = navigationHistory.get(player.getUniqueId());
        return (history != null && !history.isEmpty()) 
            ? Optional.of(history.peek()) 
            : Optional.empty();
    }
    
    @Override
    public Stack<Menu> getNavigationHistory(Player player) {
        Objects.requireNonNull(player, "Player cannot be null");
        
        Stack<Menu> history = navigationHistory.get(player.getUniqueId());
        if (history == null) {
            return new Stack<>();
        }
        
        // Return a defensive copy
        Stack<Menu> copy = new Stack<>();
        copy.addAll(history);
        return copy;
    }
    
    @Override
    public void clearHistory(Player player) {
        Objects.requireNonNull(player, "Player cannot be null");
        
        navigationHistory.remove(player.getUniqueId());
        
        // Notify listeners
        listeners.forEach(listener -> listener.onHistoryCleared(player));
    }
    
    @Override
    public void clearAllHistory() {
        // Notify listeners for each player
        navigationHistory.keySet().forEach(playerId -> {
            Player player = org.bukkit.Bukkit.getPlayer(playerId);
            if (player != null) {
                listeners.forEach(listener -> listener.onHistoryCleared(player));
            }
        });
        
        navigationHistory.clear();
    }
    
    @Override
    public int getNavigationDepth(Player player) {
        Objects.requireNonNull(player, "Player cannot be null");
        
        Stack<Menu> history = navigationHistory.get(player.getUniqueId());
        return history != null ? history.size() - 1 : 0;
    }
    
    @Override
    public boolean canNavigateBack(Player player) {
        Objects.requireNonNull(player, "Player cannot be null");
        
        Stack<Menu> history = navigationHistory.get(player.getUniqueId());
        return history != null && history.size() > 1;
    }
    
    @Override
    public Component buildBreadcrumb(Player player) {
        return buildBreadcrumb(player, Component.text(" > ", NamedTextColor.GRAY));
    }
    
    @Override
    public Component buildBreadcrumb(Player player, Component separator) {
        Objects.requireNonNull(player, "Player cannot be null");
        Objects.requireNonNull(separator, "Separator cannot be null");
        
        List<Component> path = getNavigationPath(player);
        if (path.isEmpty()) {
            return Component.empty();
        }
        
        Component breadcrumb = path.get(0);
        for (int i = 1; i < path.size(); i++) {
            breadcrumb = breadcrumb.append(separator).append(path.get(i));
        }
        
        return breadcrumb;
    }
    
    @Override
    public Component buildBreadcrumb(Player player, int maxDepth) {
        Objects.requireNonNull(player, "Player cannot be null");
        
        List<Component> path = getNavigationPath(player);
        if (path.isEmpty()) {
            return Component.empty();
        }
        
        Component separator = Component.text(" > ", NamedTextColor.GRAY);
        Component breadcrumb = Component.empty();
        
        if (path.size() > maxDepth) {
            // Add ellipsis for truncated items
            breadcrumb = Component.text("...", NamedTextColor.GRAY)
                .append(separator);
            
            // Show last maxDepth items
            int startIndex = path.size() - maxDepth;
            breadcrumb = breadcrumb.append(path.get(startIndex));
            
            for (int i = startIndex + 1; i < path.size(); i++) {
                breadcrumb = breadcrumb.append(separator).append(path.get(i));
            }
        } else {
            // Show all items
            breadcrumb = path.get(0);
            for (int i = 1; i < path.size(); i++) {
                breadcrumb = breadcrumb.append(separator).append(path.get(i));
            }
        }
        
        return breadcrumb;
    }
    
    @Override
    public List<Component> getNavigationPath(Player player) {
        Objects.requireNonNull(player, "Player cannot be null");
        
        Stack<Menu> history = navigationHistory.get(player.getUniqueId());
        if (history == null || history.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<Component> path = new ArrayList<>();
        for (Menu menu : history) {
            path.add(menu.getTitle());
        }
        
        return path;
    }
    
    @Override
    public Optional<Menu> navigateToRoot(Player player) {
        Objects.requireNonNull(player, "Player cannot be null");
        
        Stack<Menu> history = navigationHistory.get(player.getUniqueId());
        if (history == null || history.isEmpty()) {
            return Optional.empty();
        }
        
        // Keep only the root menu
        Menu root = history.firstElement();
        history.clear();
        history.push(root);
        
        return Optional.of(root);
    }
    
    @Override
    public Optional<Menu> navigateToDepth(Player player, int depth) {
        Objects.requireNonNull(player, "Player cannot be null");
        
        if (depth < 0) {
            throw new IllegalArgumentException("Depth cannot be negative");
        }
        
        Stack<Menu> history = navigationHistory.get(player.getUniqueId());
        if (history == null || history.isEmpty()) {
            return Optional.empty();
        }
        
        if (depth >= history.size()) {
            return Optional.empty();
        }
        
        // Remove menus until we reach the desired depth
        while (history.size() > depth + 1) {
            history.pop();
        }
        
        return Optional.of(history.peek());
    }
    
    @Override
    public void addNavigationListener(NavigationListener listener) {
        Objects.requireNonNull(listener, "Listener cannot be null");
        listeners.add(listener);
    }
    
    @Override
    public void removeNavigationListener(NavigationListener listener) {
        Objects.requireNonNull(listener, "Listener cannot be null");
        listeners.remove(listener);
    }
    
    @Override
    public void setDefaultBackButton(MenuButton backButton, int slot) {
        Objects.requireNonNull(backButton, "Back button cannot be null");
        
        if (slot < 0 || slot > 53) {
            throw new IllegalArgumentException("Slot must be between 0 and 53");
        }
        
        this.defaultBackButton = new DefaultBackButtonConfig(backButton, slot);
    }
    
    @Override
    public Optional<BackButtonConfig> getDefaultBackButton() {
        return Optional.ofNullable(defaultBackButton);
    }
    
    /**
     * Default implementation of BackButtonConfig.
     */
    private static class DefaultBackButtonConfig implements BackButtonConfig {
        private final MenuButton button;
        private final int slot;
        
        DefaultBackButtonConfig(MenuButton button, int slot) {
            this.button = button;
            this.slot = slot;
        }
        
        @Override
        public MenuButton getButton() {
            return button;
        }
        
        @Override
        public int getSlot() {
            return slot;
        }
    }
}