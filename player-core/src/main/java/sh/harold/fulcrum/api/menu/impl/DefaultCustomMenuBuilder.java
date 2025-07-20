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
    private int viewPortRows = 6;
    private int rows = 6;
    private int columns = 9;
    private AnchorPoint anchor = AnchorPoint.TOP_LEFT;
    private Plugin owner;
    
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
        return this;
    }
    
    @Override
    public CustomMenuBuilder rows(int rows) {
        if (rows < viewPortRows) {
            throw new IllegalArgumentException("Total rows must be at least viewport rows");
        }
        this.rows = rows;
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
            .name(Component.empty())
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
            .name(displayName)
            .build();
        return this;
    }
    
    @Override
    public CustomMenuBuilder addScrollButtons() {
        this.addScrollButtons = true;
        // Default positions
        int bottomRow = (viewPortRows - 1) * 9;
        this.scrollUpSlot = 1;
        this.scrollDownSlot = bottomRow + 1;
        this.scrollLeftSlot = bottomRow + 3;
        this.scrollRightSlot = bottomRow + 5;
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
        if (slot < 0 || slot >= viewPortRows * 9) {
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
            
            // Create the menu instance
            DefaultCustomMenu menu = new DefaultCustomMenu(
                menuId,
                title,
                viewPortRows,
                rows,
                columns,
                owner,
                player // Now properly passes the player parameter
            );
            
            // Configure menu properties
            menu.getContext().setProperty("closeOnOutsideClick", closeOnOutsideClick);
            
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
            
            // Fill empty slots if specified
            if (fillEmptyItem != null) {
                // Fill all empty viewport slots
                for (int slot = 0; slot < viewPortRows * 9; slot++) {
                    if (menu.getItem(slot) == null) {
                        menu.setItem(fillEmptyItem, slot);
                    }
                }
            }
            
            // Set up scroll buttons if requested
            if (addScrollButtons) {
                menu.setScrollButtons(scrollUpSlot, scrollDownSlot, scrollLeftSlot, scrollRightSlot);
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
            
            // Now that all configuration is complete, render the items
            // Call renderItems() directly instead of update() since menu isn't open yet
            menu.renderItems();
            
            return menu;
        });
    }
    
    private void validateVirtualCoordinates(int row, int column) {
        if (row < 0 || row >= rows) {
            throw new IllegalArgumentException("Row " + row + " out of bounds (0-" + (rows - 1) + ")");
        }
        if (column < 0 || column >= columns) {
            throw new IllegalArgumentException("Column " + column + " out of bounds (0-" + (columns - 1) + ")");
        }
    }
}