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
    private final List<MenuItem> items = new ArrayList<>();
    private final Map<Integer, MenuButton> buttons = new HashMap<>();
    private Component title = Component.text("Menu");
    private int rows = 6;
    private MenuItem borderItem;
    private MenuItem fillEmptyItem;
    private Component emptyMessage;
    private PageChangeHandler pageChangeHandler;
    private int initialPage = 1;
    private int contentStartSlot = -1;
    private int contentEndSlot = -1;
    private boolean closeOnOutsideClick = true;
    private int autoRefreshInterval = 0;

    // Parent menu configuration for explicit navigation
    private String parentMenuId = null;

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
    public ListMenuBuilder parentMenu(String menuId) {
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
            String menuId = "list-menu-" + UUID.randomUUID();

            // Use original title - breadcrumb generation is handled by openChildMenu()
            Component finalTitle = title;

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
                // DEBUG LOG: Critical navigation setup diagnosis
                System.out.println("[NAVIGATION DEBUG] Builder detected needsNavigation=true:");
                System.out.println("  - totalSlots: " + totalSlots);
                System.out.println("  - reservedSlots: " + reservedSlots);
                System.out.println("  - availableContentSlots: " + availableContentSlots);
                System.out.println("  - items.size(): " + items.size());

                // Use bottom corners for previous/next navigation (anchored)
                int lastRow = (rows - 1) * 9;
                int prevSlot = lastRow; // Bottom-left corner
                int nextSlot = lastRow + 8; // Bottom-right corner

                System.out.println("  - prevSlot: " + prevSlot + " (occupied: " + buttons.containsKey(prevSlot) + ")");
                System.out.println("  - nextSlot: " + nextSlot + " (occupied: " + buttons.containsKey(nextSlot) + ")");

                // Only reserve slots if they aren't already occupied by manual buttons
                // No need to create dummy buttons - DefaultListMenu.updateNavigationButtons() will handle the actual creation
                boolean reservedPrevSlot = false;
                boolean reservedNextSlot = false;

                if (!buttons.containsKey(prevSlot)) {
                    reservedPrevSlot = true;
                }

                if (!buttons.containsKey(nextSlot)) {
                    reservedNextSlot = true;
                }

                System.out.println("  - reservedPrevSlot: " + reservedPrevSlot);
                System.out.println("  - reservedNextSlot: " + reservedNextSlot);

                // Reserve navigation button slots on the menu (no actual buttons created here)
                if (reservedPrevSlot || reservedNextSlot) {
                    System.out.println("  - Calling setNavigationButtons with slots: prev=" + (reservedPrevSlot ? prevSlot : -1) + ", next=" + (reservedNextSlot ? nextSlot : -1));
                    menu.setNavigationButtons(null, reservedPrevSlot ? prevSlot : -1, null, reservedNextSlot ? nextSlot : -1);
                } else {
                    System.out.println("  - No slots reserved, not calling setNavigationButtons");
                }
            } else {
                System.out.println("[NAVIGATION DEBUG] Builder determined needsNavigation=false");

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

            // Add parent menu back button if specified
            addParentMenuButton(menu);

            // Fill navigation row slots before rendering
            fillNavigationRow(menu);

            // Now that all configuration is complete, render the items
            // Call renderItems() directly instead of update() since menu isn't open yet
            menu.renderItems();

            return menu;
        });
    }

    /**
     * Fills empty slots in the navigation row with appropriate material.
     * For list menus (always ≤6 rows): use fillMaterial from builder
     */
    private void fillNavigationRow(DefaultListMenu menu) {
        int navigationRow = rows - 1; // Last row is navigation row

        // Determine fill material - for list menus ≤6 rows, use builder's fill material if set
        if (fillEmptyItem == null) {
            return; // No fill material specified, skip navigation row filling
        }

        Material fillMaterial = fillEmptyItem.getDisplayItem().getType();

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
     * Adds parent menu back button if a parent menu ID is specified and close button.
     * Close button is positioned right of back button (if back exists) or at standard close position.
     */
    private void addParentMenuButton(DefaultListMenu menu) {
        // Add back button if parent menu is specified
        if (parentMenuId != null) {
            int backSlot = MenuButton.getBackSlot(rows);
            System.out.println("[DEBUG BUTTON POSITIONING] ListMenu back button calculation:");
            System.out.println("  - rows: " + rows);
            System.out.println("  - getBackNavigationSlot(" + rows + ") = " + backSlot);
            System.out.println("  - getBackSlot(" + rows + ") = " + MenuButton.getBackSlot(rows));
            System.out.println("  - getCloseSlot(" + rows + ") = " + MenuButton.getCloseSlot(rows));

            if (!buttons.containsKey(backSlot)) {
                MenuButton backButton = MenuButton.createBackButtonForParentMenu(parentMenuId, menuService);
                menu.setPersistentButton(backButton, backSlot);
                System.out.println("  - Back button added at slot: " + backSlot);
            }
        }

        // Always add close button to list menus
        addCloseButton(menu);
    }

    /**
     * Adds close button to list menus.
     * Position close button right of back button (if back button exists) or at standard close position.
     */
    private void addCloseButton(DefaultListMenu menu) {
        int closeSlot;

        // Determine close button position based on whether back button exists
        if (parentMenuId != null) {
            // Back button exists, position close button right of it
            int backSlot = MenuButton.getBackSlot(rows);
            closeSlot = backSlot + 1; // Right of back button
        } else {
            // No back button, use standard close position
            closeSlot = MenuButton.getCloseSlot(rows);
        }

        System.out.println("[DEBUG BUTTON POSITIONING] ListMenu close button calculation:");
        System.out.println("  - rows: " + rows);
        System.out.println("  - parentMenuId exists: " + (parentMenuId != null));
        System.out.println("  - closeSlot: " + closeSlot);

        // Add close button if slot is not already occupied
        if (!buttons.containsKey(closeSlot)) {
            MenuButton closeButton = MenuButton.createCloseButton();
            menu.setPersistentButton(closeButton, closeSlot);
            System.out.println("  - Close button added at slot: " + closeSlot);
        } else {
            System.out.println("  - Close button slot " + closeSlot + " is already occupied, skipping");
        }
    }
}