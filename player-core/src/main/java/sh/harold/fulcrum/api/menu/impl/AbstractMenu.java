package sh.harold.fulcrum.api.menu.impl;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;
import sh.harold.fulcrum.api.menu.Menu;
import sh.harold.fulcrum.api.menu.MenuContext;
import sh.harold.fulcrum.api.menu.component.MenuButton;
import sh.harold.fulcrum.api.menu.component.MenuItem;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Abstract base implementation of Menu interface.
 * Provides common functionality for all menu types.
 */
public abstract class AbstractMenu implements Menu {
    
    protected final String id;
    protected final Component title;
    protected final int size;
    protected final Plugin ownerPlugin;
    protected final MenuContext context;
    protected final Map<Integer, MenuItem> items = new ConcurrentHashMap<>();
    protected final List<Runnable> closeHandlers = new ArrayList<>();
    protected final List<Runnable> updateHandlers = new ArrayList<>();
    
    protected Inventory inventory;
    protected Menu parent;
    protected Player viewer;
    
    protected AbstractMenu(String id, Component title, int size, Plugin ownerPlugin, Player viewer) {
        this.id = Objects.requireNonNull(id, "Menu ID cannot be null");
        this.title = Objects.requireNonNull(title, "Title cannot be null");
        this.ownerPlugin = Objects.requireNonNull(ownerPlugin, "Owner plugin cannot be null");
        this.viewer = viewer;
        
        // Validate size (must be multiple of 9, between 9 and 54)
        if (size < 9 || size > 54 || size % 9 != 0) {
            throw new IllegalArgumentException("Menu size must be a multiple of 9 between 9 and 54");
        }
        this.size = size;
        
        // Create context
        this.context = new DefaultMenuContext(id, viewer);
        
        // Create inventory with custom holder
        MenuInventoryHolder holder = new MenuInventoryHolder(this);
        this.inventory = Bukkit.createInventory(holder, size, title);
        
        // NOTE: Don't call renderItems() here - let concrete implementations
        // call it after they've finished initialization
    }
    
    @Override
    public String getId() {
        return id;
    }
    
    @Override
    public Component getTitle() {
        return title;
    }
    
    @Override
    public int getSize() {
        return size;
    }
    
    @Override
    public Inventory getInventory() {
        return inventory;
    }
    
    @Override
    public Plugin getOwnerPlugin() {
        return ownerPlugin;
    }
    
    @Override
    public boolean isOpen() {
        return viewer != null && viewer.getOpenInventory().getTopInventory().equals(inventory);
    }
    
    @Override
    public Optional<Player> getViewer() {
        return Optional.ofNullable(viewer);
    }
    
    @Override
    public void update() {
        if (!isOpen()) {
            return;
        }
        
        // Clear inventory
        inventory.clear();
        
        // Re-render items
        renderItems();
        
        // Trigger update handlers
        updateHandlers.forEach(Runnable::run);
    }
    
    @Override
    public void close() {
        getViewer().ifPresent(Player::closeInventory);
    }
    
    @Override
    public Optional<Menu> getParent() {
        return Optional.ofNullable(parent);
    }
    
    @Override
    public void setParent(Menu parent) {
        this.parent = parent;
    }
    
    @Override
    public MenuContext getContext() {
        return context;
    }
    
    @Override
    public boolean navigateToPage(int page) {
        // Default implementation for non-paginated menus
        return false;
    }
    
    @Override
    public void onClose(Runnable handler) {
        Objects.requireNonNull(handler, "Close handler cannot be null");
        closeHandlers.add(handler);
    }
    
    @Override
    public void onUpdate(Runnable handler) {
        Objects.requireNonNull(handler, "Update handler cannot be null");
        updateHandlers.add(handler);
    }
    
    /**
     * Sets an item at the specified slot.
     * 
     * @param item the item to set
     * @param slot the slot position
     */
    public void setItem(MenuItem item, int slot) {
        if (slot < 0 || slot >= size) {
            throw new IllegalArgumentException("Slot " + slot + " is out of bounds for menu size " + size);
        }
        
        items.put(slot, item);
        if (inventory != null) {
            inventory.setItem(slot, item.getDisplayItem());
        }
    }
    
    /**
     * Sets a button at the specified slot.
     * 
     * @param button the button to set
     * @param slot the slot position
     */
    public void setButton(MenuButton button, int slot) {
        setItem(button, slot);
    }
    
    /**
     * Gets an item at the specified slot.
     * 
     * @param slot the slot position
     * @return the item at the slot, or null if empty
     */
    public MenuItem getItem(int slot) {
        return items.get(slot);
    }
    
    /**
     * Gets a button at the specified slot.
     * 
     * @param slot the slot position
     * @return the button at the slot, or null if not a button
     */
    public MenuButton getButton(int slot) {
        MenuItem item = items.get(slot);
        return item instanceof MenuButton ? (MenuButton) item : null;
    }
    
    /**
     * Removes an item from the specified slot.
     * 
     * @param slot the slot position
     */
    public void removeItem(int slot) {
        items.remove(slot);
        if (inventory != null) {
            inventory.setItem(slot, null);
        }
    }
    
    /**
     * Clears all items from the menu.
     */
    public void clearItems() {
        items.clear();
        if (inventory != null) {
            inventory.clear();
        }
    }
    
    /**
     * Triggers all close handlers.
     */
    public void triggerCloseHandlers() {
        closeHandlers.forEach(Runnable::run);
    }
    
    /**
     * Renders all items to the inventory.
     * Should be overridden by subclasses for custom rendering logic.
     */
    protected void renderItems() {
        // Render all items to their slots
        items.forEach((slot, item) -> {
            inventory.setItem(slot, item.getDisplayItem());
        });
    }
    
    /**
     * Fills a range of slots with the specified item.
     * 
     * @param item the item to fill with
     * @param startSlot the starting slot (inclusive)
     * @param endSlot the ending slot (inclusive)
     */
    protected void fillSlots(MenuItem item, int startSlot, int endSlot) {
        for (int slot = startSlot; slot <= endSlot && slot < size; slot++) {
            setItem(item, slot);
        }
    }
    
    /**
     * Adds a border around the menu with the specified item.
     * 
     * @param borderItem the item to use for the border
     */
    protected void addBorder(MenuItem borderItem) {
        int rows = getRows();
        
        // Top row
        for (int i = 0; i < 9; i++) {
            setItem(borderItem, i);
        }
        
        // Bottom row
        for (int i = (rows - 1) * 9; i < rows * 9; i++) {
            setItem(borderItem, i);
        }
        
        // Left and right columns (excluding corners already filled)
        for (int row = 1; row < rows - 1; row++) {
            setItem(borderItem, row * 9); // Left column
            setItem(borderItem, (row * 9) + 8); // Right column
        }
    }
    
    /**
     * Gets the slot indices that form the border of the menu.
     * 
     * @return set of border slot indices
     */
    protected Set<Integer> getBorderSlots() {
        Set<Integer> borderSlots = new HashSet<>();
        int rows = getRows();
        
        // Top and bottom rows
        for (int i = 0; i < 9; i++) {
            borderSlots.add(i); // Top
            borderSlots.add((rows - 1) * 9 + i); // Bottom
        }
        
        // Left and right columns
        for (int row = 1; row < rows - 1; row++) {
            borderSlots.add(row * 9); // Left
            borderSlots.add((row * 9) + 8); // Right
        }
        
        return borderSlots;
    }
    
    /**
     * Gets available content slots (excluding border if present).
     * 
     * @param excludeBorder whether to exclude border slots
     * @return list of available content slots
     */
    protected List<Integer> getContentSlots(boolean excludeBorder) {
        List<Integer> contentSlots = new ArrayList<>();
        Set<Integer> borderSlots = excludeBorder ? getBorderSlots() : Collections.emptySet();
        
        for (int slot = 0; slot < size; slot++) {
            if (!borderSlots.contains(slot) && !items.containsKey(slot)) {
                contentSlots.add(slot);
            }
        }
        
        return contentSlots;
    }
}