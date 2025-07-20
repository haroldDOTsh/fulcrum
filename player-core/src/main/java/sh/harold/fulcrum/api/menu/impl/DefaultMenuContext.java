package sh.harold.fulcrum.api.menu.impl;

import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.menu.MenuContext;
import sh.harold.fulcrum.api.menu.MenuContextSnapshot;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of MenuContext.
 * Provides thread-safe property storage and snapshot capabilities.
 */
public class DefaultMenuContext implements MenuContext {
    
    private final String menuId;
    private final Player viewer;
    private final long openTimestamp;
    private final Map<String, Object> properties = new ConcurrentHashMap<>();
    
    /**
     * Creates a new menu context.
     * 
     * @param menuId the menu ID
     * @param viewer the player viewing the menu
     */
    public DefaultMenuContext(String menuId, Player viewer) {
        this.menuId = Objects.requireNonNull(menuId, "Menu ID cannot be null");
        this.viewer = Objects.requireNonNull(viewer, "Viewer cannot be null");
        this.openTimestamp = System.currentTimeMillis();
    }
    
    @Override
    public String getMenuId() {
        return menuId;
    }
    
    @Override
    public Player getViewer() {
        return viewer;
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getProperty(String key, Class<T> type) {
        Objects.requireNonNull(key, "Property key cannot be null");
        Objects.requireNonNull(type, "Type cannot be null");
        
        Object value = properties.get(key);
        if (value != null && type.isInstance(value)) {
            return Optional.of((T) value);
        }
        return Optional.empty();
    }
    
    @Override
    public void setProperty(String key, Object value) {
        Objects.requireNonNull(key, "Property key cannot be null");
        
        if (value == null) {
            properties.remove(key);
        } else {
            properties.put(key, value);
        }
    }
    
    @Override
    public Object removeProperty(String key) {
        Objects.requireNonNull(key, "Property key cannot be null");
        return properties.remove(key);
    }
    
    @Override
    public boolean hasProperty(String key) {
        Objects.requireNonNull(key, "Property key cannot be null");
        return properties.containsKey(key);
    }
    
    @Override
    public Map<String, Object> getProperties() {
        return Collections.unmodifiableMap(new HashMap<>(properties));
    }
    
    @Override
    public void clearProperties() {
        properties.clear();
    }
    
    @Override
    public long getOpenTimestamp() {
        return openTimestamp;
    }
    
    @Override
    public MenuContextSnapshot createSnapshot() {
        return new DefaultMenuContextSnapshot(
            menuId,
            System.currentTimeMillis(),
            new HashMap<>(properties)
        );
    }
    
    @Override
    public void restoreFromSnapshot(MenuContextSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "Snapshot cannot be null");
        
        if (!snapshot.getMenuId().equals(menuId)) {
            throw new IllegalArgumentException("Snapshot menu ID does not match context menu ID");
        }
        
        properties.clear();
        properties.putAll(snapshot.getProperties());
    }
    
    /**
     * Default implementation of MenuContextSnapshot.
     */
    private static class DefaultMenuContextSnapshot implements MenuContextSnapshot {
        private final String menuId;
        private final long snapshotTimestamp;
        private final Map<String, Object> properties;
        
        DefaultMenuContextSnapshot(String menuId, long snapshotTimestamp, Map<String, Object> properties) {
            this.menuId = menuId;
            this.snapshotTimestamp = snapshotTimestamp;
            this.properties = Collections.unmodifiableMap(properties);
        }
        
        @Override
        public String getMenuId() {
            return menuId;
        }
        
        @Override
        public long getSnapshotTimestamp() {
            return snapshotTimestamp;
        }
        
        @Override
        public Map<String, Object> getProperties() {
            return properties;
        }
    }
}