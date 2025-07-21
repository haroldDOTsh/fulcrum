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
    private boolean autoRefresh = false;
    private int refreshInterval = 0;
    private Supplier<MenuItem[]> dynamicContentProvider;
    
    // fillEmpty item for post-rendering pipeline
    private final MenuItem fillEmptyItem;
    
    public DefaultCustomMenu(String id, Component title, int viewportRows, int virtualRows,
                           int virtualColumns, Plugin ownerPlugin, Player viewer, MenuItem fillEmptyItem) {
        super(id, title, viewportRows * 9, ownerPlugin, viewer);
        
        this.viewportRows = viewportRows;
        this.virtualRows = virtualRows;
        this.virtualColumns = virtualColumns;
        this.fillEmptyItem = fillEmptyItem;
        
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
        
        // Clamp to valid range (simplified - no wrap-around)
        newRowOffset = Math.max(0, Math.min(newRowOffset, Math.max(0, virtualRows - viewportRows)));
        newColumnOffset = Math.max(0, Math.min(newColumnOffset, Math.max(0, virtualColumns - viewportColumns)));
        
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
        // CRITICAL FIX: Check if navigation bar is present to determine max content area
        boolean hasNavigationBar = hasNavigationBar();
        
        // VIEWPORT PROTECTION: When navigation is present AND viewport is explicitly set smaller,
        // content area must be further reduced to respect both constraints
        int maxContentRows;
        if (hasNavigationBar) {
            // When navigation is present, reserve bottom row and respect explicit viewport
            // If viewport is set to 3 rows, content can only use 2 rows (0-17)
            // If viewport is set to 4 rows, content can only use 3 rows (0-26)
            // If viewport is set to 5 rows, content can only use 4 rows (0-35)
            // If viewport is set to 6 rows, content can only use 5 rows (0-44)
            maxContentRows = Math.max(1, viewportRows - 1);
        } else {
            // No navigation bar, use full viewport
            maxContentRows = viewportRows;
        }
        int viewportSlotsEnd = maxContentRows * viewportColumns; // viewportColumns is always 9
        
        // Save persistent items: items outside content area OR anchored items OR navigation items
        Map<Integer, MenuItem> persistentItems = new HashMap<>();
        for (Map.Entry<Integer, MenuItem> entry : items.entrySet()) {
            int slot = entry.getKey();
            MenuItem item = entry.getValue();
            // Preserve items outside the virtual content area OR anchored items
            // When navigation is present, also preserve items in navigation area (slots 45-53)
            boolean isInNavigationArea = hasNavigationBar && slot >= 45 && slot < 54;
            if (slot >= viewportSlotsEnd || item.isAnchored() || isInNavigationArea) {
                persistentItems.put(slot, entry.getValue());
            }
        }
        
        // Clear inventory and items first - this removes old virtual grid items from viewport
        inventory.clear();
        items.clear();
        
        // Restore persistent items first
        for (Map.Entry<Integer, MenuItem> entry : persistentItems.entrySet()) {
            super.setItem(entry.getValue(), entry.getKey());
        }
        
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
        
        // Render visible portion of virtual grid - ONLY in content area (respects navigation bar)
        for (int viewRow = 0; viewRow < maxContentRows; viewRow++) {
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
        
        
        // Apply post-rendering items using the new layered pipeline
        applyPostRenderingItems();
    }
    
    /**
     * FIXED: Critical layer priority and viewport logic.
     *
     * NEW PIPELINE - Navigation elements are applied LAST and have highest priority:
     * 1. Apply fillEmpty to empty content slots (0-44 when nav present, 0-53 when no nav)
     * 2. Apply virtual grid items to content area
     * 3. Apply navigation elements (slots 45-53) - HIGHEST PRIORITY, never overwritten
     */
    private void applyPostRenderingItems() {
        boolean hasNavigationBar = hasNavigationBar();
        
        // FIXED: Use same viewport calculation as renderItems() for consistency
        int maxContentRows;
        if (hasNavigationBar) {
            // When navigation is present, reserve bottom row and respect explicit viewport
            maxContentRows = Math.max(1, viewportRows - 1);
        } else {
            // No navigation bar, use full viewport
            maxContentRows = viewportRows;
        }
        int contentSlotsEnd = maxContentRows * viewportColumns; // Content area limit
        
        // Layer 1: Apply fillEmpty to empty content slots only (not navigation area)
        for (int slot = 0; slot < contentSlotsEnd; slot++) {
            if (!items.containsKey(slot) && fillEmptyItem != null) {
                super.setItem(fillEmptyItem, slot);
            }
        }
        
        // Layer 2: Virtual grid items were already applied in renderItems() - preserve them
        // (No action needed here - virtual grid takes precedence over fillEmpty)
        
        // Layer 3: Navigation elements (HIGHEST PRIORITY - applied LAST, never overwritten)
        if (hasNavigationBar) {
            applyNavigationElements();
        }
        
        // Layer 4: Apply automatic buttons to remaining empty slots in content area
        for (int slot = 0; slot < contentSlotsEnd; slot++) {
            if (!items.containsKey(slot)) {
                MenuButton automaticButton = getAutomaticButtonForSlot(slot);
                if (automaticButton != null) {
                    super.setButton(automaticButton, slot);
                }
            }
        }
    }
    
    /**
     * Determines if this menu has a navigation bar that reserves the bottom row.
     */
    private boolean hasNavigationBar() {
        // Check for navigation-related context properties set by builders
        Boolean autoNavigationButtons = (Boolean) context.getProperty("autoNavigationButtons", Boolean.class).orElse(false);
        Boolean isOversized = (Boolean) context.getProperty("isOversized", Boolean.class).orElse(false);
        
        // Navigation bar is present if:
        // 1. Auto navigation buttons are enabled, OR
        // 2. Menu is oversized (has scroll buttons in bottom row), OR
        // 3. Has back/close buttons in bottom row (always present)
        return autoNavigationButtons || isOversized || hasBottomRowButtons();
    }
    
    /**
     * Checks if menu has buttons positioned in bottom row (slots 45-53).
     * FIXED: Smart navigation bar detection - only reserve bottom row when actually needed.
     */
    private boolean hasBottomRowButtons() {
        // Check if any items are placed in bottom row positions
        for (int slot = 45; slot < 54; slot++) {
            if (items.containsKey(slot)) {
                return true;
            }
        }
        
        // FIXED: For non-viewport menus, only reserve bottom row if there are actual navigation elements needed
        // Check if this is a viewport menu (has virtual content larger than viewport)
        boolean hasViewport = virtualRows > viewportRows || virtualColumns > viewportColumns;
        
        // Get automatic button configuration
        Boolean autoCloseButton = (Boolean) context.getProperty("autoCloseButton", Boolean.class).orElse(true);
        Boolean autoBackButton = (Boolean) context.getProperty("autoBackButton", Boolean.class).orElse(false);
        
        // For viewport menus, always reserve bottom row for navigation controls
        if (hasViewport) {
            return true;
        }
        
        // For non-viewport menus, only reserve if there are actual navigation elements beyond just close button
        // Close button alone doesn't justify reserving entire bottom row for non-viewport menus
        return autoBackButton || hasNavigationButtons();
    }
    
    /**
     * Checks if menu has navigation buttons (search, sort, filter, etc.) that would use bottom row.
     */
    private boolean hasNavigationButtons() {
        Boolean autoNavigationButtons = (Boolean) context.getProperty("autoNavigationButtons", Boolean.class).orElse(false);
        Boolean isOversized = (Boolean) context.getProperty("isOversized", Boolean.class).orElse(false);
        
        // Navigation buttons are needed if explicitly enabled or menu is oversized
        return autoNavigationButtons || isOversized;
    }
    
    /**
     * Applies navigation elements to bottom row with highest priority.
     * These elements can never be overwritten by virtual grid items.
     */
    private void applyNavigationElements() {
        // Apply automatic navigation buttons in bottom row (slots 45-53)
        for (int slot = 45; slot < 54; slot++) {
            MenuButton navigationButton = getAutomaticButtonForSlot(slot);
            if (navigationButton != null) {
                super.setButton(navigationButton, slot); // Force override any existing content
            }
        }
    }
    
    /**
     * Gets an automatic button that should be placed at the specified slot, if any.
     * This ensures automatic buttons are visible even if they weren't initially placed.
     */
    private MenuButton getAutomaticButtonForSlot(int slot) {
        // Check for standard automatic button positions
        // These would typically be configured through the builder and stored in context
        
        // Close button (typically bottom-right corner)
        if (slot == MenuButton.getCloseSlot(viewportRows)) {
            Boolean autoClose = (Boolean) context.getProperty("autoCloseButton", Boolean.class).orElse(true);
            if (autoClose) {
                return MenuButton.createPositionedClose(viewportRows);
            }
        }
        
        // Back button (typically bottom-left corner)
        if (slot == MenuButton.getBackSlot(viewportRows)) {
            Boolean autoBack = (Boolean) context.getProperty("autoBackButton", Boolean.class).orElse(false);
            if (autoBack) {
                return MenuButton.createPositionedBack(viewportRows);
            }
        }
        
        // Navigation buttons if enabled
        Boolean autoNavigation = (Boolean) context.getProperty("autoNavigationButtons", Boolean.class).orElse(false);
        if (autoNavigation) {
            if (slot == MenuButton.getPaginateUpSlot(viewportRows)) {
                return MenuButton.createPositionedPaginateUp(viewportRows);
            }
            if (slot == MenuButton.getPaginateDownSlot(viewportRows)) {
                return MenuButton.createPositionedPaginateDown(viewportRows);
            }
            if (slot == MenuButton.getSearchSlot(viewportRows)) {
                return MenuButton.createPositionedSearch(viewportRows);
            }
            if (slot == MenuButton.getSortSlot(viewportRows)) {
                return MenuButton.createPositionedSort(viewportRows);
            }
            if (slot == MenuButton.getFilterSlot(viewportRows)) {
                return MenuButton.createPositionedFilter(viewportRows);
            }
            if (slot == MenuButton.getForwardSlot(viewportRows)) {
                return MenuButton.createPositionedForward(viewportRows);
            }
            if (slot == MenuButton.getPaginateBackSlot(viewportRows)) {
                return MenuButton.createPositionedPaginateBack(viewportRows);
            }
        }
        
        return null; // No automatic button for this slot
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
        // Get dimension information from context
        Boolean verticallyOversized = (Boolean) context.getProperty("verticallyOversized", Boolean.class).orElse(true);
        Boolean horizontallyOversized = (Boolean) context.getProperty("horizontallyOversized", Boolean.class).orElse(true);
        Boolean isOversized = (Boolean) context.getProperty("isOversized", Boolean.class).orElse(false);
        
        // Up button - only show if vertically oversized and can scroll up
        if (verticallyOversized && scrollUpButton != null && scrollUpSlot >= 0) {
            if (canScrollUp()) {
                super.setButton(scrollUpButton, scrollUpSlot);
            } else {
                // Hide button when at scroll limit - remove from slot completely
                items.remove(scrollUpSlot);
                if (inventory.getItem(scrollUpSlot) != null) {
                    inventory.setItem(scrollUpSlot, null);
                }
            }
        }
        
        // Down button - only show if vertically oversized and can scroll down
        if (verticallyOversized && scrollDownButton != null && scrollDownSlot >= 0) {
            if (canScrollDown()) {
                super.setButton(scrollDownButton, scrollDownSlot);
            } else {
                // Hide button when at scroll limit - remove from slot completely
                items.remove(scrollDownSlot);
                if (inventory.getItem(scrollDownSlot) != null) {
                    inventory.setItem(scrollDownSlot, null);
                }
            }
        }
        
        // Left button - only show if horizontally oversized and can scroll left
        if (horizontallyOversized && scrollLeftButton != null && scrollLeftSlot >= 0) {
            if (canScrollLeft()) {
                super.setButton(scrollLeftButton, scrollLeftSlot);
            } else {
                // Hide button when at scroll limit - remove from slot completely
                items.remove(scrollLeftSlot);
                if (inventory.getItem(scrollLeftSlot) != null) {
                    inventory.setItem(scrollLeftSlot, null);
                }
            }
        }
        
        // Right button - only show if horizontally oversized and can scroll right
        if (horizontallyOversized && scrollRightButton != null && scrollRightSlot >= 0) {
            if (canScrollRight()) {
                super.setButton(scrollRightButton, scrollRightSlot);
            } else {
                // Hide button when at scroll limit - remove from slot completely
                items.remove(scrollRightSlot);
                if (inventory.getItem(scrollRightSlot) != null) {
                    inventory.setItem(scrollRightSlot, null);
                }
            }
        }
        
        // Add bottom row black glass for oversized menus only
        if (isOversized) {
            addBottomRowBlackGlass();
        }
    }
    
    /**
     * Adds black stained glass panes to the entire bottom row for oversized menus.
     * This indicates the bottom row is reserved for navigation controls.
     */
    private void addBottomRowBlackGlass() {
        int bottomRowStart = (viewportRows - 1) * 9; // Start of bottom row
        MenuItem blackGlass = MenuDisplayItem.builder(Material.BLACK_STAINED_GLASS_PANE)
            .name(Component.text("Navigation Area", NamedTextColor.DARK_GRAY))
            .build();
        
        // Fill bottom row (slots 45-53 for 6-row menu) with black glass
        for (int slot = bottomRowStart; slot < bottomRowStart + 9; slot++) {
            // Only add black glass if slot doesn't have a navigation button or other important item
            if (!items.containsKey(slot)) {
                super.setItem(blackGlass, slot);
            }
        }
    }
    
    private boolean canScrollUp() {
        return rowOffset > 0;
    }
    
    private boolean canScrollDown() {
        return rowOffset < virtualRows - viewportRows;
    }
    
    private boolean canScrollLeft() {
        return columnOffset > 0;
    }
    
    private boolean canScrollRight() {
        return columnOffset < virtualColumns - viewportColumns;
    }
}