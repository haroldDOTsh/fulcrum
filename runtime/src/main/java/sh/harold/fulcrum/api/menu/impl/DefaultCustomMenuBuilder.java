package sh.harold.fulcrum.api.menu.impl;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import sh.harold.fulcrum.api.menu.AnchorPoint;
import sh.harold.fulcrum.api.menu.CustomMenuBuilder;
import sh.harold.fulcrum.api.menu.Menu;
import sh.harold.fulcrum.api.menu.component.MenuButton;
import sh.harold.fulcrum.api.menu.component.MenuDisplayItem;
import sh.harold.fulcrum.api.menu.component.MenuItem;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Default implementation of CustomMenuBuilder.
 * Builds custom menus with viewport support and flexible dimensions.
 */
public class DefaultCustomMenuBuilder implements CustomMenuBuilder {
    
    private final DefaultMenuService menuService;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.legacySection();
    
    private Component title = Component.text("Custom Menu");
    private Integer viewPortRows = null; // Changed to null to track if explicitly set
    private int rows = 6;
    private int columns = 9;
    private AnchorPoint anchor = AnchorPoint.TOP_LEFT;
    private Plugin owner;
    
    // Track whether viewport was explicitly set
    private boolean hasExplicitViewport = false;
    
    // Items and buttons
    private final Map<Integer, Map<Integer, MenuItem>> virtualItems = new HashMap<>();
    private final Map<Integer, MenuButton> buttons = new HashMap<>();
    private MenuItem fillEmptyItem;
    private MenuItem borderItem;
    
    // Scroll configuration
    private boolean addScrollButtons = false;
    private int scrollUpSlot = -1;
    private int scrollDownSlot = -1;
    private int scrollLeftSlot = -1;
    private int scrollRightSlot = -1;
    
    // Options
    private ScrollHandler scrollHandler;
    private int autoRefreshInterval = 0;
    private Supplier<MenuItem[]> dynamicContentProvider;
    private int initialRowOffset = 0;
    private int initialColumnOffset = 0;
    private boolean closeOnOutsideClick = true;
    
    // Parent menu configuration for explicit navigation
    private String parentMenuId = null;
    
    public DefaultCustomMenuBuilder(DefaultMenuService menuService) {
        this.menuService = Objects.requireNonNull(menuService, "MenuService cannot be null");
        this.owner = menuService.getPlugin();
    }
    
    @Override
    public CustomMenuBuilder title(Component title) {
        this.title = Objects.requireNonNull(title, "Title cannot be null");
        return this;
    }
    
    @Override
    public CustomMenuBuilder title(String title) {
        Objects.requireNonNull(title, "Title cannot be null");
        
        // Use Adventure legacy serializer for legacy color codes (§), MiniMessage for modern format
        if (title.contains("§") || title.contains("&")) {
            this.title = legacySerializer.deserialize(title);
        } else {
            this.title = miniMessage.deserialize(title);
        }
        
        return this;
    }
    
    @Override
    public CustomMenuBuilder viewPort(int viewPortRows) {
        if (viewPortRows < 1 || viewPortRows > 6) {
            throw new IllegalArgumentException("Viewport rows must be between 1 and 6");
        }
        this.viewPortRows = viewPortRows;
        this.hasExplicitViewport = true; // Track that viewport was explicitly set
        return this;
    }
    
    @Override
    public CustomMenuBuilder rows(int rows) {
        this.rows = rows;
        
        // Validate viewport requirements based on rows
        if (rows > 6) {
            // Rows > 6 require explicit viewport specification
            if (!hasExplicitViewport) {
                throw new IllegalStateException("Menu is oversized (" + rows + " rows) but no viewport specified. Use .viewport() to enable navigation.");
            }
            // Validate viewport is not larger than total rows
            if (rows < viewPortRows) {
                throw new IllegalArgumentException("Total rows (" + rows + ") must be at least viewport rows (" + viewPortRows + ")");
            }
        } else {
            // Rows <= 6: viewport is optional
            // If viewport was explicitly set, validate it
            if (hasExplicitViewport && rows < viewPortRows) {
                throw new IllegalArgumentException("Total rows (" + rows + ") must be at least viewport rows (" + viewPortRows + ")");
            }
        }
        
        return this;
    }
    
    @Override
    public CustomMenuBuilder anchor(AnchorPoint anchor) {
        this.anchor = Objects.requireNonNull(anchor, "Anchor point cannot be null");
        return this;
    }
    
    @Override
    public CustomMenuBuilder anchor(AnchorPoint.Vertical vertical, AnchorPoint.Horizontal horizontal) {
        this.anchor = AnchorPoint.of(
            Objects.requireNonNull(vertical, "Vertical anchor cannot be null"),
            Objects.requireNonNull(horizontal, "Horizontal anchor cannot be null")
        );
        return this;
    }
    
    @Override
    public CustomMenuBuilder columns(int columns) {
        if (columns < 9) {
            throw new IllegalArgumentException("Columns must be at least 9");
        }
        this.columns = columns;
        return this;
    }
    
    @Override
    public CustomMenuBuilder owner(Plugin plugin) {
        this.owner = Objects.requireNonNull(plugin, "Plugin cannot be null");
        return this;
    }
    
    @Override
    public CustomMenuBuilder addButton(MenuButton button, int row, int column) {
        Objects.requireNonNull(button, "Button cannot be null");
        validateVirtualCoordinates(row, column);
        
        virtualItems.computeIfAbsent(row, k -> new HashMap<>()).put(column, button);
        return this;
    }
    
    @Override
    public CustomMenuBuilder addButton(MenuButton button, int virtualSlot) {
        Objects.requireNonNull(button, "Button cannot be null");
        
        int row = virtualSlot / columns;
        int column = virtualSlot % columns;
        return addButton(button, row, column);
    }
    
    @Override
    public CustomMenuBuilder addButton(MenuButton button) {
        Objects.requireNonNull(button, "Button cannot be null");
        if (!button.hasSlot()) {
            throw new IllegalArgumentException("Button must have a slot assigned");
        }
        
        // For buttons with pre-assigned slots, place them in viewport space
        buttons.put(button.getSlot(), button);
        return this;
    }
    
    @Override
    public CustomMenuBuilder addItem(MenuItem item, int row, int column) {
        Objects.requireNonNull(item, "Item cannot be null");
        validateVirtualCoordinates(row, column);
        
        virtualItems.computeIfAbsent(row, k -> new HashMap<>()).put(column, item);
        return this;
    }
    
    @Override
    public CustomMenuBuilder addItem(MenuItem item, int virtualSlot) {
        Objects.requireNonNull(item, "Item cannot be null");
        
        int row = virtualSlot / columns;
        int column = virtualSlot % columns;
        return addItem(item, row, column);
    }
    
    @Override
    public CustomMenuBuilder addItem(ItemStack itemStack, int row, int column) {
        Objects.requireNonNull(itemStack, "ItemStack cannot be null");
        
        MenuItem item = MenuDisplayItem.builder(itemStack.getType())
            .name(itemStack.displayName())
            .build();
        
        return addItem(item, row, column);
    }
    
    @Override
    public CustomMenuBuilder fillEmpty(Material material) {
        this.fillEmptyItem = MenuDisplayItem.builder(material)
            .name("") // Empty name (builder automatically adds &r prefix)
            .build();
        return this;
    }
    
    @Override
    public CustomMenuBuilder fillEmpty(MenuItem item) {
        this.fillEmptyItem = Objects.requireNonNull(item, "Fill item cannot be null");
        return this;
    }
    
    @Override
    public CustomMenuBuilder fillEmpty(Material material, String displayName) {
        this.fillEmptyItem = MenuDisplayItem.builder(material)
            .name(displayName) // Builder automatically adds &r prefix
            .build();
        return this;
    }
    
    @Override
    public CustomMenuBuilder addScrollButtons() {
        this.addScrollButtons = true;
        // Default positions according to requirements:
        // Up/Down navigation: Column 0 (index 0)
        // Left/Right navigation: Bottom left and right corners
        int effectiveViewportRows = getEffectiveViewportRows();
        int bottomRow = (effectiveViewportRows - 1) * 9;
        this.scrollUpSlot = 0; // Column 0, top row
        this.scrollDownSlot = bottomRow; // Column 0, bottom row
        this.scrollLeftSlot = bottomRow; // Bottom-left corner
        this.scrollRightSlot = bottomRow + 8; // Bottom-right corner
        return this;
    }
    
    @Override
    public CustomMenuBuilder addScrollButtons(int upSlot, int downSlot, int leftSlot, int rightSlot) {
        this.addScrollButtons = true;
        this.scrollUpSlot = upSlot;
        this.scrollDownSlot = downSlot;
        this.scrollLeftSlot = leftSlot;
        this.scrollRightSlot = rightSlot;
        return this;
    }
    
    
    @Override
    public CustomMenuBuilder onScroll(ScrollHandler handler) {
        this.scrollHandler = Objects.requireNonNull(handler, "Scroll handler cannot be null");
        return this;
    }
    
    @Override
    public CustomMenuBuilder autoRefresh(int intervalSeconds) {
        if (intervalSeconds <= 0) {
            throw new IllegalArgumentException("Refresh interval must be positive");
        }
        this.autoRefreshInterval = intervalSeconds;
        return this;
    }
    
    @Override
    public CustomMenuBuilder dynamicContent(Supplier<MenuItem[]> provider) {
        this.dynamicContentProvider = Objects.requireNonNull(provider, "Content provider cannot be null");
        return this;
    }
    
    
    @Override
    public CustomMenuBuilder initialOffset(int rowOffset, int columnOffset) {
        if (rowOffset < 0 || columnOffset < 0) {
            throw new IllegalArgumentException("Initial offset cannot be negative");
        }
        this.initialRowOffset = rowOffset;
        this.initialColumnOffset = columnOffset;
        return this;
    }
    
    @Override
    public CustomMenuBuilder closeOnOutsideClick(boolean closeOnOutsideClick) {
        this.closeOnOutsideClick = closeOnOutsideClick;
        return this;
    }
    
    @Override
    public CustomMenuBuilder addBorder(Material borderMaterial) {
        this.borderItem = MenuDisplayItem.builder(borderMaterial)
            .name(Component.empty())
            .build();
        return this;
    }
    
    @Override
    public CustomMenuBuilder addBorder(MenuItem borderItem) {
        this.borderItem = Objects.requireNonNull(borderItem, "Border item cannot be null");
        return this;
    }
    
    @Override
    public CustomMenuBuilder parentMenu(String menuId) {
        this.parentMenuId = Objects.requireNonNull(menuId, "Parent menu ID cannot be null");
        return this;
    }
    
    @Override
    public CompletableFuture<Menu> buildAsync(Player player) {
        Objects.requireNonNull(player, "Player cannot be null");
        
        return buildAsync(player, false).thenCompose(menu -> {
            // Simple menu opening - no complex NavigationMode logic
            return menuService.openMenu(menu, player).thenApply(v -> menu);
        });
    }
    
    @Override
    public CompletableFuture<Menu> buildAsync() {
        return buildAsync(null, true);
    }
    
    private CompletableFuture<Menu> buildAsync(Player player, boolean allowNullPlayer) {
        return CompletableFuture.supplyAsync(() -> {
            // Generate unique menu ID
            String menuId = "custom-menu-" + UUID.randomUUID();
            
            // Use original title - breadcrumb generation is handled by openChildMenu()
            Component finalTitle = title;
            
            // Create the menu instance
            int effectiveViewportRows = getEffectiveViewportRows();
            DefaultCustomMenu menu = new DefaultCustomMenu(
                menuId,
                finalTitle,
                effectiveViewportRows,
                rows,
                columns,
                owner,
                player, // Now properly passes the player parameter
                fillEmptyItem // Pass fillEmpty reference for post-rendering pipeline
            );
            
            // Configure menu properties
            menu.getContext().setProperty("closeOnOutsideClick", closeOnOutsideClick);
            
            // Parent menu configuration for post-rendering pipeline
            if (parentMenuId != null) {
                menu.getContext().setProperty("parentMenuId", parentMenuId);
            }
            
            // Set anchor point
            menu.setAnchorPoint(anchor);
            
            // Add border if specified
            if (borderItem != null) {
                menu.addBorder(borderItem);
            }
            
            // **FIX**: Add virtual items AFTER menu is fully constructed
            virtualItems.forEach((row, columnMap) -> {
                columnMap.forEach((column, item) -> {
                    menu.setVirtualItem(item, row, column);
                });
            });
            
            // Add viewport buttons
            buttons.forEach((slot, button) -> menu.setButton(button, slot));
            
            // Automatically detect which dimension is oversized
            boolean verticallyOversized = rows > effectiveViewportRows;
            boolean horizontallyOversized = columns > 9; // Viewport columns is always 9 for Minecraft inventories
            boolean isOversized = verticallyOversized || horizontallyOversized;
            
            // Set up scroll buttons if explicitly requested OR if grid is oversized
            if (addScrollButtons || isOversized) {
                // Use default positioning if not explicitly set
                if (!addScrollButtons) {
                    // Smart positioning for oversized grids - only add buttons for oversized dimensions
                    int bottomRow = (effectiveViewportRows - 1) * 9;
                    if (verticallyOversized) {
                        scrollUpSlot = 0; // Column 0, top row
                        scrollDownSlot = bottomRow; // Column 0, bottom row
                    } else {
                        scrollUpSlot = -1; // Disable vertical navigation
                        scrollDownSlot = -1;
                    }
                    
                    if (horizontallyOversized) {
                        scrollLeftSlot = bottomRow; // Bottom-left corner
                        scrollRightSlot = bottomRow + 8; // Bottom-right corner
                    } else {
                        scrollLeftSlot = -1; // Disable horizontal navigation
                        scrollRightSlot = -1;
                    }
                }
                menu.setScrollButtons(scrollUpSlot, scrollDownSlot, scrollLeftSlot, scrollRightSlot);
                
                // Store dimension info for navigation button logic
                menu.getContext().setProperty("verticallyOversized", verticallyOversized);
                menu.getContext().setProperty("horizontallyOversized", horizontallyOversized);
                menu.getContext().setProperty("isOversized", isOversized);
                menu.getContext().setProperty("hasExplicitViewport", hasExplicitViewport); // Store for parent detection
            }
            
            // Configure options (simplified - wrapAround and viewport indicator removed)
            
            // Set scroll handler
            if (scrollHandler != null) {
                menu.getContext().setProperty("scrollHandler", scrollHandler);
            }
            
            // Set dynamic content provider
            if (dynamicContentProvider != null) {
                menu.setDynamicContentProvider(dynamicContentProvider);
            }
            
            // Enable auto-refresh
            if (autoRefreshInterval > 0) {
                menu.enableAutoRefresh(autoRefreshInterval);
            }
            
            // Set initial offset
            menu.setViewportOffset(initialRowOffset, initialColumnOffset);
            
            // Add parent menu back button if specified
            addParentMenuButton(menu);
            
            // Fill navigation row slots before rendering
            fillNavigationRow(menu);
            
            // Note: fillEmpty functionality is now handled by the post-rendering pipeline
            // in DefaultCustomMenu.applyPostRenderingItems() for better conflict resolution
            
            // Now that all configuration is complete, render the items
            // The new renderItems() method with post-rendering pipeline will handle fillEmpty properly
            menu.renderItems();
            
            return menu;
        });
    }
    
    /**
     * Gets the effective viewport rows - either explicitly set viewport or the menu rows (capped at 6).
     * @return effective viewport rows
     */
    private int getEffectiveViewportRows() {
        if (hasExplicitViewport) {
            return viewPortRows;
        }
        // When no explicit viewport, use menu rows but cap at 6 for standard inventory size
        return Math.min(rows, 6);
    }
    
    private void validateVirtualCoordinates(int row, int column) {
        if (row < 0 || row >= rows) {
            throw new IllegalArgumentException("Row " + row + " out of bounds (0-" + (rows - 1) + ")");
        }
        if (column < 0 || column >= columns) {
            throw new IllegalArgumentException("Column " + column + " out of bounds (0-" + (columns - 1) + ")");
        }
    }
    
    /**
     * Fills empty slots in the navigation row with appropriate material.
     * For normal menus: use fillMaterial from builder
     * For oversized menus (vertical OR horizontal): use black stained glass panes
     */
    private void fillNavigationRow(DefaultCustomMenu menu) {
        int effectiveViewportRows = getEffectiveViewportRows();
        int navigationRow = effectiveViewportRows - 1; // Last row is navigation row
        
        // Determine if menu is oversized (vertically OR horizontally)
        boolean verticallyOversized = rows > effectiveViewportRows;
        boolean horizontallyOversized = columns > 9; // Viewport columns is always 9 for Minecraft inventories
        boolean isOversized = verticallyOversized || horizontallyOversized;
        
        // Determine fill material based on menu size
        Material fillMaterial;
        if (isOversized) {
            // For oversized menus (vertical OR horizontal): use black stained glass panes
            fillMaterial = Material.BLACK_STAINED_GLASS_PANE;
        } else {
            // For normal menus: use the builder's fill material if set, otherwise no filling
            if (fillEmptyItem != null) {
                fillMaterial = fillEmptyItem.getDisplayItem().getType();
            } else {
                return; // No fill material specified, skip navigation row filling
            }
        }
        
        // Fill empty slots in the navigation row
        for (int slot = navigationRow * 9; slot < (navigationRow + 1) * 9; slot++) {
            if (menu.getItem(slot) == null && !buttons.containsKey(slot)) {
                // Don't overwrite navigation buttons or existing items
                MenuItem fillItem = MenuDisplayItem.builder(fillMaterial)
                    .name("") // Empty name to prevent text display
                    .build();
                menu.setItem(fillItem, slot);
            }
        }
    }
    
    /**
     * Adds parent menu back button if a parent menu ID is specified.
     * Simple and explicit - no complex auto-logic.
     */
    private void addParentMenuButton(DefaultCustomMenu menu) {
        if (parentMenuId != null) {
            int effectiveViewportRows = getEffectiveViewportRows();
            int backSlot = MenuButton.getBackSlot(effectiveViewportRows);
            System.out.println("[DEBUG BUTTON POSITIONING] CustomMenu back button calculation:");
            System.out.println("  - effectiveViewportRows: " + effectiveViewportRows);
            System.out.println("  - getBackNavigationSlot(" + effectiveViewportRows + ") = " + backSlot);
            System.out.println("  - getBackSlot(" + effectiveViewportRows + ") = " + MenuButton.getBackSlot(effectiveViewportRows));
            System.out.println("  - getCloseSlot(" + effectiveViewportRows + ") = " + MenuButton.getCloseSlot(effectiveViewportRows));
            
            if (menu.getItem(backSlot) == null && !buttons.containsKey(backSlot)) {
                MenuButton backButton = MenuButton.createBackButtonForParentMenu(parentMenuId, menuService);
                menu.setButton(backButton, backSlot);
                System.out.println("  - Back button added at slot: " + backSlot);
            }
        }
    }
    
}