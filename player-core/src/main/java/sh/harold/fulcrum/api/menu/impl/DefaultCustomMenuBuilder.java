package sh.harold.fulcrum.api.menu.impl;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import sh.harold.fulcrum.api.menu.*;
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
    private boolean wrapAround = false;
    private ScrollHandler scrollHandler;
    private int autoRefreshInterval = 0;
    private Supplier<MenuItem[]> dynamicContentProvider;
    private int viewportIndicatorSlot = -1;
    private int initialRowOffset = 0;
    private int initialColumnOffset = 0;
    private boolean closeOnOutsideClick = true;
    
    // Automatic button configuration
    private boolean autoCloseButton = true; // Default enabled
    private boolean autoBackButton = false; // Default disabled
    private boolean autoNavigationButtons = false; // Default disabled
    
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
        this.title = miniMessage.deserialize(Objects.requireNonNull(title, "Title cannot be null"));
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
    public CustomMenuBuilder wrapAround(boolean wrapAround) {
        this.wrapAround = wrapAround;
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
    public CustomMenuBuilder addViewportIndicator(int slot) {
        int effectiveViewportRows = getEffectiveViewportRows();
        if (slot < 0 || slot >= effectiveViewportRows * 9) {
            throw new IllegalArgumentException("Viewport indicator slot out of bounds");
        }
        this.viewportIndicatorSlot = slot;
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
    public CustomMenuBuilder autoCloseButton(boolean enabled) {
        this.autoCloseButton = enabled;
        return this;
    }
    
    @Override
    public CustomMenuBuilder autoBackButton(boolean enabled) {
        this.autoBackButton = enabled;
        return this;
    }
    
    @Override
    public CustomMenuBuilder autoNavigationButtons(boolean enabled) {
        this.autoNavigationButtons = enabled;
        return this;
    }
    
    @Override
    public CompletableFuture<Menu> buildAsync(Player player) {
        Objects.requireNonNull(player, "Player cannot be null");
        
        return buildAsync(player, false).thenCompose(menu -> {
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
            
            // Generate breadcrumb title if this is a child menu
            Component finalTitle = generateBreadcrumbTitle(player);
            
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
            
            // Store automatic button configuration for post-rendering pipeline
            menu.getContext().setProperty("autoCloseButton", autoCloseButton);
            menu.getContext().setProperty("autoBackButton", autoBackButton);
            menu.getContext().setProperty("autoNavigationButtons", autoNavigationButtons);
            
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
            
            // Configure options
            menu.setWrapAround(wrapAround);
            
            // Set viewport indicator
            if (viewportIndicatorSlot >= 0) {
                menu.setViewportIndicatorSlot(viewportIndicatorSlot);
            }
            
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
            
            // Add automatic buttons FIRST (these are persistent UI elements)
            addAutomaticButtons(menu);
            
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
     * Adds automatic buttons to the menu based on configuration.
     * Checks if slots are occupied before adding automatic buttons.
     * Manual additions take precedence over automatic buttons.
     */
    private void addAutomaticButtons(DefaultCustomMenu menu) {
        int effectiveViewportRows = getEffectiveViewportRows();
        
        // Add close button if enabled (default: enabled)
        if (autoCloseButton) {
            int closeSlot = MenuButton.getCloseSlot(effectiveViewportRows);
            if (menu.getItem(closeSlot) == null && !buttons.containsKey(closeSlot)) {
                MenuButton closeButton = MenuButton.createPositionedClose(effectiveViewportRows);
                menu.setButton(closeButton, closeSlot);
            }
        }
        
        // FIXED: Back button logic for child menus
        // Check if player has an open menu (this new menu will be a child)
        boolean hasParentMenu = false;
        if (menu.getViewer().isPresent()) {
            Player viewer = menu.getViewer().get();
            hasParentMenu = menuService.getOpenMenu(viewer).isPresent();
        }
        
        if (hasParentMenu) {
            // Child menu - ALWAYS add back button with highest priority
            int backSlot = MenuButton.getBackSlot(effectiveViewportRows);
            MenuButton backButton = MenuButton.createPositionedBack(effectiveViewportRows);
            menu.setButton(backButton, backSlot); // Force override any existing item at this slot
        } else if (autoBackButton) {
            // Root menu with back button enabled - add if slot is available
            int backSlot = MenuButton.getBackSlot(effectiveViewportRows);
            if (menu.getItem(backSlot) == null && !buttons.containsKey(backSlot)) {
                MenuButton backButton = MenuButton.createPositionedBack(effectiveViewportRows);
                menu.setButton(backButton, backSlot);
            }
        }
        
        // Add navigation buttons if enabled (default: disabled)
        if (autoNavigationButtons) {
            // Add up navigation button
            int upSlot = MenuButton.getUpNavigationSlot(effectiveViewportRows);
            if (menu.getItem(upSlot) == null && !buttons.containsKey(upSlot)) {
                MenuButton upButton = MenuButton.createPositionedUpNavigation(effectiveViewportRows);
                menu.setButton(upButton, upSlot);
            }
            
            // Add down navigation button
            int downSlot = MenuButton.getDownNavigationSlot(effectiveViewportRows);
            if (menu.getItem(downSlot) == null && !buttons.containsKey(downSlot)) {
                MenuButton downButton = MenuButton.createPositionedDownNavigation(effectiveViewportRows);
                menu.setButton(downButton, downSlot);
            }
            
            // Add search, sort, filter, forward buttons on bottom row
            addNavigationButton(menu, MenuButton::createPositionedSearch, MenuButton.getSearchSlot(effectiveViewportRows), "search");
            addNavigationButton(menu, MenuButton::createPositionedSort, MenuButton.getSortSlot(effectiveViewportRows), "sort");
            addNavigationButton(menu, MenuButton::createPositionedFilter, MenuButton.getFilterSlot(effectiveViewportRows), "filter");
            addNavigationButton(menu, MenuButton::createPositionedForward, MenuButton.getForwardSlot(effectiveViewportRows), "forward");
            addNavigationButton(menu, MenuButton::createPositionedBackNavigation, MenuButton.getBackNavigationSlot(effectiveViewportRows), "back navigation");
        }
    }
    
    /**
     * Helper method to add a navigation button if the slot is available.
     */
    private void addNavigationButton(DefaultCustomMenu menu, java.util.function.Function<Integer, MenuButton> buttonFactory, int slot, String buttonName) {
        if (menu.getItem(slot) == null && !buttons.containsKey(slot)) {
            int effectiveViewportRows = getEffectiveViewportRows();
            MenuButton button = buttonFactory.apply(effectiveViewportRows);
            menu.setButton(button, slot);
        }
    }
    
    /**
     * Generates breadcrumb title for child menus.
     * Format: "Parent -> Child" for child menus, original title for root menus.
     *
     * @param player the player context to check for current menu
     * @return the generated breadcrumb title
     */
    private Component generateBreadcrumbTitle(Player player) {
        // Check if player has a current menu that would be the parent
        if (player != null) {
            // Try to get current open menu through menu service
            Optional<Menu> currentMenuOpt = menuService.getOpenMenu(player);
            if (currentMenuOpt.isPresent()) {
                // This new menu will be a child of the current menu
                Component parentTitle = currentMenuOpt.get().getTitle();
                return Component.text()
                    .append(parentTitle)
                    .append(Component.text(" -> "))
                    .append(title)
                    .build();
            }
        }
        
        // No parent menu found, return original title
        return title;
    }
}