package sh.harold.fulcrum.api.menu.impl;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import sh.harold.fulcrum.api.menu.ListMenuBuilder;
import sh.harold.fulcrum.api.menu.Menu;
import sh.harold.fulcrum.api.menu.component.MenuButton;
import sh.harold.fulcrum.api.menu.component.MenuDisplayItem;
import sh.harold.fulcrum.api.menu.component.MenuItem;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Default implementation of ListMenuBuilder.
 * Builds paginated menus with automatic navigation.
 */
public class DefaultListMenuBuilder implements ListMenuBuilder {
    
    private final DefaultMenuService menuService;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    
    private Component title = Component.text("Menu");
    private int rows = 6;
    private final List<MenuItem> items = new ArrayList<>();
    private final Map<Integer, MenuButton> buttons = new HashMap<>();
    private MenuItem borderItem;
    private Component emptyMessage;
    private PageChangeHandler pageChangeHandler;
    private int initialPage = 1;
    private int contentStartSlot = -1;
    private int contentEndSlot = -1;
    private boolean closeOnOutsideClick = true;
    private int autoRefreshInterval = 0;
    
    // Navigation
    private boolean addNavigation = false;
    private int previousSlot = -1;
    private int nextSlot = -1;
    private MenuButton previousButton;
    private MenuButton nextButton;
    private int pageIndicatorSlot = -1;
    
    public DefaultListMenuBuilder(DefaultMenuService menuService) {
        this.menuService = Objects.requireNonNull(menuService, "MenuService cannot be null");
    }
    
    @Override
    public ListMenuBuilder title(Component title) {
        this.title = Objects.requireNonNull(title, "Title cannot be null");
        return this;
    }
    
    @Override
    public ListMenuBuilder title(String title) {
        this.title = miniMessage.deserialize(Objects.requireNonNull(title, "Title cannot be null"));
        return this;
    }
    
    @Override
    public ListMenuBuilder rows(int rows) {
        if (rows < 1 || rows > 6) {
            throw new IllegalArgumentException("Rows must be between 1 and 6");
        }
        this.rows = rows;
        return this;
    }
    
    @Override
    public ListMenuBuilder addBorder(Material borderMaterial) {
        this.borderItem = MenuDisplayItem.builder(borderMaterial)
            .name(Component.empty())
            .build();
        return this;
    }
    
    @Override
    public ListMenuBuilder addBorder(MenuItem borderItem) {
        this.borderItem = Objects.requireNonNull(borderItem, "Border item cannot be null");
        return this;
    }
    
    @Override
    public ListMenuBuilder addBorder(Material borderMaterial, String borderName) {
        this.borderItem = MenuDisplayItem.builder(borderMaterial)
            .name(borderName)
            .build();
        return this;
    }
    
    @Override
    public ListMenuBuilder addButton(MenuButton button, int slot) {
        Objects.requireNonNull(button, "Button cannot be null");
        if (slot < 0 || slot >= rows * 9) {
            throw new IllegalArgumentException("Slot out of bounds: " + slot);
        }
        buttons.put(slot, button);
        return this;
    }
    
    @Override
    public ListMenuBuilder addButton(MenuButton button) {
        Objects.requireNonNull(button, "Button cannot be null");
        if (!button.hasSlot()) {
            throw new IllegalArgumentException("Button must have a slot assigned");
        }
        return addButton(button, button.getSlot());
    }
    
    @Override
    public ListMenuBuilder addButtons(MenuButton... buttons) {
        for (MenuButton button : buttons) {
            addButton(button);
        }
        return this;
    }
    
    @Override
    public ListMenuBuilder addItems(Collection<? extends MenuItem> items) {
        this.items.addAll(Objects.requireNonNull(items, "Items cannot be null"));
        return this;
    }
    
    @Override
    public <T> ListMenuBuilder addItems(Collection<T> items, Function<T, MenuItem> transformer) {
        Objects.requireNonNull(items, "Items cannot be null");
        Objects.requireNonNull(transformer, "Transformer cannot be null");
        
        for (T item : items) {
            MenuItem menuItem = transformer.apply(item);
            if (menuItem != null) {
                this.items.add(menuItem);
            }
        }
        return this;
    }
    
    @Override
    public ListMenuBuilder addItems(MenuItem... items) {
        this.items.addAll(Arrays.asList(items));
        return this;
    }
    
    @Override
    public ListMenuBuilder addItemStacks(Collection<ItemStack> items) {
        Objects.requireNonNull(items, "Items cannot be null");
        
        for (ItemStack item : items) {
            if (item != null) {
                this.items.add(MenuDisplayItem.builder(item.getType())
                    .name(item.displayName())
                    .build());
            }
        }
        return this;
    }
    
    @Override
    public ListMenuBuilder initialPage(int page) {
        if (page < 1) {
            throw new IllegalArgumentException("Page must be at least 1");
        }
        this.initialPage = page;
        return this;
    }
    
    @Override
    public ListMenuBuilder contentSlots(int startSlot, int endSlot) {
        if (startSlot < 0 || endSlot >= rows * 9 || startSlot > endSlot) {
            throw new IllegalArgumentException("Invalid content slot range");
        }
        this.contentStartSlot = startSlot;
        this.contentEndSlot = endSlot;
        return this;
    }
    
    @Override
    public ListMenuBuilder addNavigationButtons() {
        this.addNavigation = true;
        // Default positions: bottom left and right corners
        int lastRow = (rows - 1) * 9;
        this.previousSlot = lastRow;
        this.nextSlot = lastRow + 8;
        return this;
    }
    
    @Override
    public ListMenuBuilder addNavigationButtons(int previousSlot, int nextSlot) {
        if (previousSlot < 0 || previousSlot >= rows * 9 || 
            nextSlot < 0 || nextSlot >= rows * 9) {
            throw new IllegalArgumentException("Navigation button slots out of bounds");
        }
        this.addNavigation = true;
        this.previousSlot = previousSlot;
        this.nextSlot = nextSlot;
        return this;
    }
    
    @Override
    public ListMenuBuilder navigationButtons(MenuButton previousButton, MenuButton nextButton) {
        this.previousButton = Objects.requireNonNull(previousButton, "Previous button cannot be null");
        this.nextButton = Objects.requireNonNull(nextButton, "Next button cannot be null");
        this.addNavigation = true;
        
        // Use button slots if available
        if (previousButton.hasSlot()) {
            this.previousSlot = previousButton.getSlot();
        }
        if (nextButton.hasSlot()) {
            this.nextSlot = nextButton.getSlot();
        }
        
        return this;
    }
    
    @Override
    public ListMenuBuilder addPageIndicator(int slot) {
        if (slot < 0 || slot >= rows * 9) {
            throw new IllegalArgumentException("Page indicator slot out of bounds");
        }
        this.pageIndicatorSlot = slot;
        return this;
    }
    
    @Override
    public ListMenuBuilder emptyMessage(Component emptyMessage) {
        this.emptyMessage = Objects.requireNonNull(emptyMessage, "Empty message cannot be null");
        return this;
    }
    
    @Override
    public ListMenuBuilder onPageChange(PageChangeHandler handler) {
        this.pageChangeHandler = Objects.requireNonNull(handler, "Page change handler cannot be null");
        return this;
    }
    
    @Override
    public ListMenuBuilder autoRefresh(int intervalSeconds) {
        if (intervalSeconds <= 0) {
            throw new IllegalArgumentException("Refresh interval must be positive");
        }
        this.autoRefreshInterval = intervalSeconds;
        return this;
    }
    
    @Override
    public ListMenuBuilder closeOnOutsideClick(boolean closeOnOutsideClick) {
        this.closeOnOutsideClick = closeOnOutsideClick;
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
            String menuId = "list-menu-" + UUID.randomUUID();
            
            // Create the menu instance
            DefaultListMenu menu = new DefaultListMenu(
                menuId,
                title,
                rows,
                menuService.getPlugin(),
                player // Now properly passes the player parameter
            );
            
            // Configure menu properties
            menu.getContext().setProperty("closeOnOutsideClick", closeOnOutsideClick);
            
            // Add border if specified
            if (borderItem != null) {
                menu.addBorderItem(borderItem);
            }
            
            // Set content slot range if specified
            if (contentStartSlot >= 0 && contentEndSlot >= 0) {
                menu.setContentSlotRange(contentStartSlot, contentEndSlot);
            }
            
            // Add persistent buttons
            buttons.forEach((slot, button) -> menu.setPersistentButton(button, slot));
            
            // Add navigation buttons if requested
            if (addNavigation) {
                MenuButton prevButton = previousButton != null ? previousButton : MenuButton.PREVIOUS_PAGE;
                MenuButton nextButton = this.nextButton != null ? this.nextButton : MenuButton.NEXT_PAGE;
                menu.setNavigationButtons(prevButton, previousSlot, nextButton, nextSlot);
            }
            
            // Set page indicator
            if (pageIndicatorSlot >= 0) {
                menu.setPageIndicatorSlot(pageIndicatorSlot);
            }
            
            // Set empty message
            if (emptyMessage != null) {
                menu.setEmptyMessage(emptyMessage);
            }
            
            // Add items
            menu.addContentItems(items);
            
            // Set page change handler
            if (pageChangeHandler != null) {
                menu.getContext().setProperty("pageChangeHandler", pageChangeHandler);
            }
            
            // Enable auto-refresh
            if (autoRefreshInterval > 0) {
                menu.enableAutoRefresh(autoRefreshInterval);
            }
            
            // Navigate to initial page
            menu.navigateToPage(initialPage);
            
            // Now that all configuration is complete, render the items
            // Call renderItems() directly instead of update() since menu isn't open yet
            menu.renderItems();
            
            return menu;
        });
    }
}