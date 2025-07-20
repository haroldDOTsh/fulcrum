package sh.harold.fulcrum.api.menu.impl;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.Sound;
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
    private MenuItem fillEmptyItem;
    private Component emptyMessage;
    private PageChangeHandler pageChangeHandler;
    private int initialPage = 1;
    private int contentStartSlot = -1;
    private int contentEndSlot = -1;
    private boolean closeOnOutsideClick = true;
    private int autoRefreshInterval = 0;
    
    // Automatic button configuration
    private boolean autoCloseButton = true; // Default enabled
    private boolean autoBackButton = false; // Default disabled
    
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
    public ListMenuBuilder fillEmpty(Material material) {
        this.fillEmptyItem = MenuDisplayItem.builder(material)
            .name("") // Empty name (builder automatically adds &r prefix)
            .build();
        return this;
    }
    
    @Override
    public ListMenuBuilder fillEmpty(MenuItem item) {
        this.fillEmptyItem = Objects.requireNonNull(item, "Fill item cannot be null");
        return this;
    }
    
    @Override
    public ListMenuBuilder fillEmpty(Material material, String displayName) {
        this.fillEmptyItem = MenuDisplayItem.builder(material)
            .name(displayName) // Builder automatically adds &r prefix
            .build();
        return this;
    }
    
    @Override
    public ListMenuBuilder autoCloseButton(boolean enabled) {
        this.autoCloseButton = enabled;
        return this;
    }
    
    @Override
    public ListMenuBuilder autoBackButton(boolean enabled) {
        this.autoBackButton = enabled;
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
            
            // Generate breadcrumb title if this is a child menu
            Component finalTitle = generateBreadcrumbTitle(player);
            
            // Create the menu instance
            DefaultListMenu menu = new DefaultListMenu(
                menuId,
                finalTitle,
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
            
            // Automatic navigation detection for list menus
            // Calculate available content slots
            int totalSlots = rows * 9;
            int reservedSlots = 0;
            
            // Reserve bottom row for navigation buttons when needed
            reservedSlots += 9; // Bottom row is always reserved for automatic navigation
            
            // Account for border and existing buttons
            if (borderItem != null) {
                reservedSlots += (2 * rows) + (2 * (9 - 2)); // Border around edges
            }
            reservedSlots += buttons.size(); // Manually added buttons
            
            int availableContentSlots = totalSlots - reservedSlots;
            boolean needsNavigation = items.size() > availableContentSlots;
            
            // Automatically add navigation buttons if needed
            if (needsNavigation) {
                // Use bottom corners for previous/next navigation (anchored)
                int lastRow = (rows - 1) * 9;
                int prevSlot = lastRow; // Bottom-left corner
                int nextSlot = lastRow + 8; // Bottom-right corner
                
                // Only add if slots aren't already occupied by manual buttons
                // Create navigation buttons and set them up properly
                MenuButton prevButton = null;
                MenuButton nextButton = null;
                
                if (!buttons.containsKey(prevSlot)) {
                    // FIXED: Use ARROW material instead of dye
                    prevButton = MenuButton.builder(Material.ARROW)
                        .name("<red>Previous Page") // Builder automatically adds &r prefix
                        .secondary("Go to the previous page")
                        .sound(Sound.ITEM_BOOK_PAGE_TURN)
                        .slot(prevSlot)
                        .anchor() // Anchor navigation buttons (non-scrolling)
                        .onClick(p -> {
                            // Navigation will be handled by DefaultListMenu.updateNavigationButtons()
                            // This is just a placeholder - the actual click handler is set in updateNavigationButtons()
                        })
                        .build();
                }
                
                if (!buttons.containsKey(nextSlot)) {
                    // FIXED: Use ARROW material instead of dye
                    nextButton = MenuButton.builder(Material.ARROW)
                        .name("<green>Next Page") // Builder automatically adds &r prefix
                        .secondary("Go to the next page")
                        .sound(Sound.ITEM_BOOK_PAGE_TURN)
                        .slot(nextSlot)
                        .anchor() // Anchor navigation buttons (non-scrolling)
                        .onClick(p -> {
                            // Navigation will be handled by DefaultListMenu.updateNavigationButtons()
                            // This is just a placeholder - the actual click handler is set in updateNavigationButtons()
                        })
                        .build();
                }
                
                // Set navigation buttons on the menu
                if (prevButton != null || nextButton != null) {
                    menu.setNavigationButtons(prevButton, prevSlot, nextButton, nextSlot);
                }
                
                // Add page indicator in top row, slot 4 (5th slot in first row)
                int pageIndicatorSlot = 4; // Top row, index 4
                if (!buttons.containsKey(pageIndicatorSlot)) {
                    menu.setPageIndicatorSlot(pageIndicatorSlot);
                }
            }
            
            // Set empty message
            if (emptyMessage != null) {
                menu.setEmptyMessage(emptyMessage);
            }
            
            // Add items
            menu.addContentItems(items);
            
            // Set fill empty item if specified
            if (fillEmptyItem != null) {
                menu.setFillEmptyItem(fillEmptyItem);
            }
            
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
            
            // Add automatic buttons if enabled
            addAutomaticButtons(menu);
            
            // Now that all configuration is complete, render the items
            // Call renderItems() directly instead of update() since menu isn't open yet
            menu.renderItems();
            
            return menu;
        });
    }
    
    /**
     * Adds automatic buttons to the menu based on configuration.
     * Checks if slots are occupied before adding automatic buttons.
     * Manual additions take precedence over automatic buttons.
     * Note: For list menus, we need to avoid conflicts with existing pagination buttons.
     * FIXED: Parent-child timing resolution - detect parent menus during build process.
     */
    private void addAutomaticButtons(DefaultListMenu menu) {
        // FIXED: Detect if this menu will be a child menu by checking current open menu
        // This resolves the timing issue where parent-child relationships aren't established yet
        boolean isChildMenu = detectChildMenu(menu.getViewer().orElse(null));
        
        // Add close button if enabled (default: enabled)
        if (autoCloseButton) {
            int closeSlot = MenuButton.getCloseSlot(rows);
            if (!buttons.containsKey(closeSlot)) {
                MenuButton closeButton = MenuButton.createPositionedClose(rows);
                menu.setPersistentButton(closeButton, closeSlot);
            } else {
                // Log warning if slot is occupied
                System.out.println("Warning: Automatic close button slot " + closeSlot + " is already occupied. Skipping automatic close button.");
            }
        }
        
        // Add back button if enabled (default: disabled) OR if this is a child menu
        if (autoBackButton || isChildMenu) {
            int backSlot = MenuButton.getBackSlot(rows);
            if (!buttons.containsKey(backSlot)) {
                MenuButton backButton = MenuButton.createPositionedBack(rows);
                menu.setPersistentButton(backButton, backSlot);
            } else {
                // Log warning if slot is occupied
                System.out.println("Warning: Automatic back button slot " + backSlot + " is already occupied. Skipping automatic back button.");
            }
        }
        
        // Note: Removed the old check for menu.getParent().isPresent() as it's timing-dependent
        // The isChildMenu detection above handles this case properly during build time
    }
    
    /**
     * Detects if this menu will be a child menu by checking if the player has a current menu open.
     * FIXED: Early parent menu detection to resolve timing issues.
     *
     * @param player the player context to check for current menu
     * @return true if this menu will be a child menu, false if it's a root menu
     */
    private boolean detectChildMenu(Player player) {
        // Check if player has a current menu that would be the parent
        if (player != null) {
            try {
                // Try to get current open menu through menu service
                Optional<Menu> currentMenuOpt = menuService.getOpenMenu(player);
                return currentMenuOpt.isPresent(); // If player has an open menu, this new menu will be a child
            } catch (Exception e) {
                // If we can't determine, assume it's not a child menu
                System.err.println("Could not detect child menu status: " + e.getMessage());
                return false;
            }
        }
        
        // No player context, assume it's not a child menu
        return false;
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