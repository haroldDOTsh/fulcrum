package sh.harold.fulcrum.api.menu.impl;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import sh.harold.fulcrum.api.menu.AnchorPoint;
import sh.harold.fulcrum.api.menu.CustomMenuBuilder;
import sh.harold.fulcrum.api.menu.component.MenuButton;
import sh.harold.fulcrum.api.menu.component.MenuDisplayItem;
import sh.harold.fulcrum.api.menu.component.MenuItem;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Implementation of a custom menu with viewport support.
 * Allows for virtual inventories larger than the visible area with scrolling.
 */
public class DefaultCustomMenu extends AbstractMenu {
    
    // Virtual grid dimensions
    private final int virtualRows;
    private final int virtualColumns;
    
    // Viewport dimensions (visible area)
    private final int viewportRows;
    private final int viewportColumns = 9; // Always 9 for Minecraft inventories
    
    // Current viewport offset
    private int rowOffset = 0;
    private int columnOffset = 0;
    
    // Anchor point for initial positioning
    private AnchorPoint anchorPoint = AnchorPoint.TOP_LEFT;
    
    // Virtual item grid (row -> column -> item)
    private final Map<Integer, Map<Integer, MenuItem>> virtualGrid = new HashMap<>();
    
    // Scroll buttons
    private MenuButton scrollUpButton;
    private MenuButton scrollDownButton;
    private MenuButton scrollLeftButton;
    private MenuButton scrollRightButton;
    private int scrollUpSlot = -1;
    private int scrollDownSlot = -1;
    private int scrollLeftSlot = -1;
    private int scrollRightSlot = -1;
    
    // Options
    private boolean wrapAround = false;
    private boolean autoRefresh = false;
    private int refreshInterval = 0;
    private Supplier<MenuItem[]> dynamicContentProvider;
    private int viewportIndicatorSlot = -1;
    
    public DefaultCustomMenu(String id, Component title, int viewportRows, int virtualRows,
                           int virtualColumns, Plugin ownerPlugin, Player viewer) {
        super(id, title, viewportRows * 9, ownerPlugin, viewer);
        
        this.viewportRows = viewportRows;
        this.virtualRows = virtualRows;
        this.virtualColumns = virtualColumns;
        
        // Calculate initial offset based on anchor point
        calculateInitialOffset();
    }
    
    @Override
    public boolean isListMenu() {
        return false;
    }
    
    @Override
    public boolean isCustomMenu() {
        return true;
    }
    
    /**
     * Sets an item in the virtual grid at specific coordinates.
     */
    public void setVirtualItem(MenuItem item, int row, int column) {
        if (row < 0 || row >= virtualRows || column < 0 || column >= virtualColumns) {
            throw new IllegalArgumentException("Virtual coordinates out of bounds: " + row + "," + column);
        }
        
        virtualGrid.computeIfAbsent(row, k -> new HashMap<>()).put(column, item);
    }
    
    /**
     * Gets an item from the virtual grid.
     */
    public MenuItem getVirtualItem(int row, int column) {
        Map<Integer, MenuItem> rowMap = virtualGrid.get(row);
        return rowMap != null ? rowMap.get(column) : null;
    }
    
    /**
     * Sets the anchor point for viewport positioning.
     */
    public void setAnchorPoint(AnchorPoint anchor) {
        this.anchorPoint = anchor;
        calculateInitialOffset();
    }
    
    /**
     * Sets up scroll buttons.
     */
    public void setScrollButtons(int upSlot, int downSlot, int leftSlot, int rightSlot) {
        this.scrollUpSlot = upSlot;
        this.scrollDownSlot = downSlot;
        this.scrollLeftSlot = leftSlot;
        this.scrollRightSlot = rightSlot;
        
        // Create default scroll buttons
        this.scrollUpButton = MenuButton.builder(Material.ARROW)
            .name("<yellow>Scroll Up")
            .onClick(player -> scrollViewport(-1, 0))
            .build();
            
        this.scrollDownButton = MenuButton.builder(Material.ARROW)
            .name("<yellow>Scroll Down")
            .onClick(player -> scrollViewport(1, 0))
            .build();
            
        this.scrollLeftButton = MenuButton.builder(Material.ARROW)
            .name("<yellow>Scroll Left")
            .onClick(player -> scrollViewport(0, -1))
            .build();
            
        this.scrollRightButton = MenuButton.builder(Material.ARROW)
            .name("<yellow>Scroll Right")
            .onClick(player -> scrollViewport(0, 1))
            .build();
    }
    
    /**
     * Sets custom scroll buttons.
     */
    public void setCustomScrollButtons(MenuButton up, MenuButton down, 
                                     MenuButton left, MenuButton right) {
        if (up != null) this.scrollUpButton = up;
        if (down != null) this.scrollDownButton = down;
        if (left != null) this.scrollLeftButton = left;
        if (right != null) this.scrollRightButton = right;
    }
    
    /**
     * Enables wrap-around scrolling.
     */
    public void setWrapAround(boolean wrapAround) {
        this.wrapAround = wrapAround;
    }
    
    /**
     * Sets the viewport indicator slot.
     */
    public void setViewportIndicatorSlot(int slot) {
        this.viewportIndicatorSlot = slot;
    }
    
    /**
     * Sets a dynamic content provider.
     */
    public void setDynamicContentProvider(Supplier<MenuItem[]> provider) {
        this.dynamicContentProvider = provider;
    }
    
    /**
     * Enables auto-refresh.
     */
    public void enableAutoRefresh(int intervalSeconds) {
        this.autoRefresh = true;
        this.refreshInterval = intervalSeconds;
        // TODO: Implement scheduled refresh task
    }
    
    /**
     * Scrolls the viewport by the specified offset.
     */
    public boolean scrollViewport(int rowDelta, int columnDelta) {
        int newRowOffset = rowOffset + rowDelta;
        int newColumnOffset = columnOffset + columnDelta;
        
        // Handle wrap-around
        if (wrapAround) {
            if (newRowOffset < 0) {
                newRowOffset = Math.max(0, virtualRows - viewportRows);
            } else if (newRowOffset > virtualRows - viewportRows) {
                newRowOffset = 0;
            }
            
            if (newColumnOffset < 0) {
                newColumnOffset = Math.max(0, virtualColumns - viewportColumns);
            } else if (newColumnOffset > virtualColumns - viewportColumns) {
                newColumnOffset = 0;
            }
        } else {
            // Clamp to valid range
            newRowOffset = Math.max(0, Math.min(newRowOffset, Math.max(0, virtualRows - viewportRows)));
            newColumnOffset = Math.max(0, Math.min(newColumnOffset, Math.max(0, virtualColumns - viewportColumns)));
        }
        
        // Check if actually changed
        if (newRowOffset == rowOffset && newColumnOffset == columnOffset) {
            return false;
        }
        
        // Update offsets
        int oldRowOffset = rowOffset;
        int oldColumnOffset = columnOffset;
        rowOffset = newRowOffset;
        columnOffset = newColumnOffset;
        
        // Update context
        context.setProperty("viewportRowOffset", rowOffset);
        context.setProperty("viewportColumnOffset", columnOffset);
        
        // Trigger scroll handler if set
        Object handler = context.getProperty("scrollHandler", Object.class).orElse(null);
        if (handler instanceof CustomMenuBuilder.ScrollHandler) {
            ((CustomMenuBuilder.ScrollHandler) handler).onScroll(
                viewer, oldRowOffset, oldColumnOffset, rowOffset, columnOffset
            );
        }
        
        // Re-render
        update();
        return true;
    }
    
    /**
     * Sets the viewport offset directly.
     */
    public void setViewportOffset(int rowOffset, int columnOffset) {
        this.rowOffset = Math.max(0, Math.min(rowOffset, Math.max(0, virtualRows - viewportRows)));
        this.columnOffset = Math.max(0, Math.min(columnOffset, Math.max(0, virtualColumns - viewportColumns)));
        
        context.setProperty("viewportRowOffset", this.rowOffset);
        context.setProperty("viewportColumnOffset", this.columnOffset);
    }
    
    @Override
    protected void renderItems() {
        // Clear inventory first
        inventory.clear();
        items.clear();
        
        // Update dynamic content if provider is set
        if (dynamicContentProvider != null) {
            MenuItem[] dynamicItems = dynamicContentProvider.get();
            if (dynamicItems != null) {
                // Place dynamic items in virtual grid
                int index = 0;
                for (int row = 0; row < virtualRows && index < dynamicItems.length; row++) {
                    for (int col = 0; col < virtualColumns && index < dynamicItems.length; col++) {
                        setVirtualItem(dynamicItems[index++], row, col);
                    }
                }
            }
        }
        
        // Render visible portion of virtual grid
        for (int viewRow = 0; viewRow < viewportRows; viewRow++) {
            for (int viewCol = 0; viewCol < viewportColumns; viewCol++) {
                int virtualRow = rowOffset + viewRow;
                int virtualCol = columnOffset + viewCol;
                
                // Get item from virtual grid
                MenuItem item = getVirtualItem(virtualRow, virtualCol);
                if (item != null) {
                    int slot = viewRow * 9 + viewCol;
                    super.setItem(item, slot);
                }
            }
        }
        
        // Update scroll buttons
        updateScrollButtons();
        
        // Update viewport indicator
        updateViewportIndicator();
    }
    
    private void calculateInitialOffset() {
        // Calculate row offset based on vertical anchor
        switch (anchorPoint.getVertical()) {
            case TOP:
                rowOffset = 0;
                break;
            case CENTRE:
                rowOffset = Math.max(0, (virtualRows - viewportRows) / 2);
                break;
            case BOTTOM:
                rowOffset = Math.max(0, virtualRows - viewportRows);
                break;
        }
        
        // Calculate column offset based on horizontal anchor
        switch (anchorPoint.getHorizontal()) {
            case LEFT:
                columnOffset = 0;
                break;
            case CENTRE:
                columnOffset = Math.max(0, (virtualColumns - viewportColumns) / 2);
                break;
            case RIGHT:
                columnOffset = Math.max(0, virtualColumns - viewportColumns);
                break;
        }
        
        // Store in context
        context.setProperty("viewportRowOffset", rowOffset);
        context.setProperty("viewportColumnOffset", columnOffset);
    }
    
    private void updateScrollButtons() {
        // Up button
        if (scrollUpButton != null && scrollUpSlot >= 0) {
            if (canScrollUp()) {
                super.setButton(scrollUpButton, scrollUpSlot);
            } else {
                MenuItem disabled = MenuDisplayItem.builder(Material.GRAY_STAINED_GLASS_PANE)
                    .name(Component.text("Cannot scroll up", NamedTextColor.GRAY))
                    .build();
                super.setItem(disabled, scrollUpSlot);
            }
        }
        
        // Down button
        if (scrollDownButton != null && scrollDownSlot >= 0) {
            if (canScrollDown()) {
                super.setButton(scrollDownButton, scrollDownSlot);
            } else {
                MenuItem disabled = MenuDisplayItem.builder(Material.GRAY_STAINED_GLASS_PANE)
                    .name(Component.text("Cannot scroll down", NamedTextColor.GRAY))
                    .build();
                super.setItem(disabled, scrollDownSlot);
            }
        }
        
        // Left button
        if (scrollLeftButton != null && scrollLeftSlot >= 0) {
            if (canScrollLeft()) {
                super.setButton(scrollLeftButton, scrollLeftSlot);
            } else {
                MenuItem disabled = MenuDisplayItem.builder(Material.GRAY_STAINED_GLASS_PANE)
                    .name(Component.text("Cannot scroll left", NamedTextColor.GRAY))
                    .build();
                super.setItem(disabled, scrollLeftSlot);
            }
        }
        
        // Right button
        if (scrollRightButton != null && scrollRightSlot >= 0) {
            if (canScrollRight()) {
                super.setButton(scrollRightButton, scrollRightSlot);
            } else {
                MenuItem disabled = MenuDisplayItem.builder(Material.GRAY_STAINED_GLASS_PANE)
                    .name(Component.text("Cannot scroll right", NamedTextColor.GRAY))
                    .build();
                super.setItem(disabled, scrollRightSlot);
            }
        }
    }
    
    private void updateViewportIndicator() {
        if (viewportIndicatorSlot >= 0) {
            Component position = Component.text("Position: ", NamedTextColor.YELLOW)
                .append(Component.text((rowOffset + 1) + "-" + (rowOffset + viewportRows), NamedTextColor.WHITE))
                .append(Component.text(" / ", NamedTextColor.GRAY))
                .append(Component.text(virtualRows, NamedTextColor.WHITE))
                .append(Component.text(" rows, ", NamedTextColor.YELLOW))
                .append(Component.text((columnOffset + 1) + "-" + (columnOffset + viewportColumns), NamedTextColor.WHITE))
                .append(Component.text(" / ", NamedTextColor.GRAY))
                .append(Component.text(virtualColumns, NamedTextColor.WHITE))
                .append(Component.text(" cols", NamedTextColor.YELLOW));
            
            MenuItem indicator = MenuDisplayItem.builder(Material.COMPASS)
                .name(position)
                .lore(Component.text("Virtual size: " + virtualRows + "x" + virtualColumns, NamedTextColor.GRAY))
                .lore(Component.text("Viewport: " + viewportRows + "x" + viewportColumns, NamedTextColor.GRAY))
                .build();
            
            super.setItem(indicator, viewportIndicatorSlot);
        }
    }
    
    private boolean canScrollUp() {
        return wrapAround || rowOffset > 0;
    }
    
    private boolean canScrollDown() {
        return wrapAround || rowOffset < virtualRows - viewportRows;
    }
    
    private boolean canScrollLeft() {
        return wrapAround || columnOffset > 0;
    }
    
    private boolean canScrollRight() {
        return wrapAround || columnOffset < virtualColumns - viewportColumns;
    }
}