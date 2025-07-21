# Fulcrum Menu System Documentation

A simple, powerful menu system with clean builder patterns and explicit parent-child relationships.

## Table of Contents
1. [Examples: Simple to Complex](#examples-simple-to-complex)
2. [Anchor System](#anchor-system)
3. [MenuService](#menuservice)
4. [CustomMenuBuilder](#custommenubuilder)
5. [ListMenuBuilder](#listmenubuilder)
6. [MenuButton](#menubutton)
7. [MenuDisplayItem](#menudisplayitem)

---

## Examples: Simple to Complex

### Example 1: Basic Menu
The simplest possible menu with one button.

```java
private void createBasicMenu(Player player) {
    menuService.createMenuBuilder()
        .title("My First Menu")
        .rows(3)
        .addButton(
            MenuButton.builder(Material.DIAMOND)
                .name("Click Me!")
                .onClick(p -> p.sendMessage("Hello World!"))
                .build(), 
            1, 1) // row 1, column 1
        .buildAsync(player);
}
```

### Example 2: Menu with Back Button
Shows how `.parentMenu()` automatically creates a back button.

```java
private void createMainMenu(Player player) {
    menuService.createMenuBuilder()
        .title("Main Menu")
        .rows(3)
        .addButton(
            MenuButton.builder(Material.CHEST)
                .name("Open Shop")
                .onClick(this::createShopMenu) // calls method below
                .build(), 
            1, 1)
        .buildAsync(player)
        .thenAccept(menu -> {
            // Register so shop can reference it as parent
            ((DefaultMenuService) menuService).registerMenuInstance("main", menu);
        });
}

private void createShopMenu(Player player) {
    menuService.createMenuBuilder()
        .title("Shop")
        .rows(3)
        .parentMenu("main") // Automatically adds back button!
        .addButton(
            MenuButton.builder(Material.EMERALD)
                .name("Buy Item")
                .onClick(p -> p.sendMessage("Item purchased!"))
                .build(),
            1, 1)
        .buildAsync(player);
}
```

### Example 3: Using Separate Button Variables
Shows creating buttons as separate variables vs inline.

```java
private void createAdvancedMenu(Player player) {
    // Create buttons as separate variables
    MenuButton buyButton = MenuButton.builder(Material.EMERALD)
        .name("&aBuy")
        .secondary("&7Purchase this item")
        .description("&7Costs 100 coins")
        .onClick(p -> purchaseItem(p))
        .sound(Sound.UI_BUTTON_CLICK)
        .build();
    
    MenuButton sellButton = MenuButton.builder(Material.GOLD_INGOT)
        .name("&6Sell")
        .secondary("&7Sell your items")
        .onClick(p -> openSellMenu(p))
        .build();
    
    MenuDisplayItem infoItem = MenuDisplayItem.builder(Material.BOOK)
        .name("&eShop Information")
        .secondary("&7Welcome to the shop!")
        .description("&7Buy and sell items here")
        .build();
    
    menuService.createMenuBuilder()
        .title("&6Advanced Shop")
        .rows(6)
        .fillEmpty(Material.BLACK_STAINED_GLASS_PANE)
        
        // Place the pre-created items
        .addItem(infoItem, 0, 4)
        .addButton(buyButton, 2, 2)
        .addButton(sellButton, 2, 6)
        
        .buildAsync(player);
}
```

### Example 4: List Menu with Pagination
Shows automatic pagination for large lists.

```java
private void createPlayerList(Player player) {
    List<MenuDisplayItem> playerItems = new ArrayList<>();
    
    // Convert all online players to menu items
    for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
        playerItems.add(
            MenuDisplayItem.builder(Material.PLAYER_HEAD)
                .name("&e" + onlinePlayer.getName())
                .secondary("&7Level " + onlinePlayer.getLevel())
                .description("&7World: " + onlinePlayer.getWorld().getName())
                .build()
        );
    }
    
    menuService.createListMenu()
        .title("&bPlayer List")
        .rows(6)
        .addBorder(Material.BLUE_STAINED_GLASS_PANE)
        .addItems(playerItems) // Automatically paginated!
        .buildAsync(player);
}
```

### Example 5: Large Menu with Scrolling and Fixed Controls
Shows oversize menus with both scrollable content and fixed UI controls.

```java
private void createLargeMenu(Player player) {
    menuService.createMenuBuilder()
        .title("&5Magic Items Collection")
        .viewPort(6) // Player sees 6 rows
        .rows(10) // But menu actually has 10 rows (scrolling!)
        .columns(12) // And 12 columns (horizontal scrolling!)
        .fillEmpty(Material.PURPLE_STAINED_GLASS_PANE)
        
        // Fixed UI controls (slot-based = automatically anchored)
        .addButton(
            MenuButton.builder(Material.COMPASS)
                .name("&eSearch Magic Items")
                .secondary("&7Find items quickly")
                .slot(8) // Top-right - stays fixed during scrolling
                .onClick(this::openMagicSearch)
                .build())
                
        .addButton(
            MenuButton.builder(Material.BARRIER)
                .name("&c✖ Close")
                .slot(49) // Bottom-center - always accessible
                .onClick(p -> p.closeInventory())
                .build())
        
        // Scrollable magic items (coordinate-based = scrolls with content)
        .addButton(
            MenuButton.builder(Material.NETHER_STAR)
                .name("&dHidden Treasure")
                .description("&7You found the secret!")
                .onClick(p -> p.sendMessage("&dSecret discovered!"))
                .build(),
            8, 10) // Row 8, Column 10 (requires scrolling to see)
        
        .addButton(
            MenuButton.builder(Material.ENCHANTED_BOOK)
                .name("&bSpell Book")
                .onClick(p -> p.sendMessage("&bYou learned a new spell!"))
                .build(),
            1, 1) // Scrollable with content
        
        .addButton(
            MenuButton.builder(Material.POTION)
                .name("&cHealth Potion")
                .onClick(p -> p.setHealth(20.0))
                .build(),
            2, 3) // Scrollable with content
        
        .buildAsync(player);
}
```

> **Note**: This example demonstrates the simplified anchor system:
> - **Search** and **Close** buttons use `.slot()` and stay fixed in the viewport
> - **Magic items** use coordinates `(row, col)` and scroll with the content
> - No need for explicit `.anchor()` calls!

---

## Anchor System

The anchor system provides two intuitive ways to control element positioning and behavior in menus:

1. **Slot-based Anchoring** - Buttons with `.slot()` automatically stay fixed during scrolling
2. **Coordinate-based Placement** - Buttons placed at `(row, col)` scroll with menu content
3. **Viewport Anchoring** - Control initial positioning of the viewport in oversized menus

### Understanding the Two Placement Types

The menu system now automatically handles anchoring based on how you place buttons:

#### Slot-based Buttons (Automatically Anchored)

When you assign a button to a specific slot using `.slot()`, it automatically becomes anchored and stays fixed in that viewport position while content scrolls around it. Perfect for UI controls, navigation, and status displays.

```java
// This button stays fixed at slot 49 (bottom-right) during all scrolling
MenuButton fixedButton = MenuButton.builder(Material.COMPASS)
    .name("&eSearch")
    .description("&7I stay here while content scrolls!")
    .slot(49)              // Automatically anchored - no .anchor() needed!
    .onClick(this::openSearch)
    .build();

menuService.createMenuBuilder()
    .title("Mixed Content Demo")
    .rows(10)              // Large scrollable area
    .viewPort(6)           // Player sees 6 rows
    .addButton(fixedButton) // No coordinates needed - uses slot(49)
    // ... add scrollable content ...
    .buildAsync(player);
```

#### Coordinate-based Buttons (Scroll with Content)

When you place a button using coordinates `(row, col)`, it becomes part of the scrollable content area and moves with other content during scrolling. Perfect for menu items, content, and dynamic elements.

```java
// This button scrolls with the content
MenuButton contentButton = MenuButton.builder(Material.DIAMOND)
    .name("&bScrollable Item")
    .description("&7I move with the content!")
    .onClick(this::handleClick)
    .build();

menuService.createMenuBuilder()
    .title("Mixed Content Demo")
    .rows(10)
    .viewPort(6)
    .addButton(contentButton, 3, 4) // Scrolls with content at row 3, col 4
    .buildAsync(player);
```

#### Common Fixed UI Elements (Slot-based)

```java
private MenuButton createSearchButton() {
    return MenuButton.builder(Material.COMPASS)
        .name("&eSearch")
        .secondary("&7Find items quickly")
        .slot(8)           // Top-right corner - automatically anchored
        .onClick(this::openSearchInterface)
        .build();
}

private MenuButton createCartButton() {
    return MenuButton.builder(Material.CHEST)
        .name("&aShopping Cart")
        .secondary("&7Items: " + getCartSize())
        .slot(53)          // Bottom-right corner - automatically anchored
        .onClick(this::openShoppingCart)
        .build();
}

private MenuDisplayItem createStatusIndicator() {
    return MenuDisplayItem.builder(Material.EMERALD)
        .name("&aOnline")
        .secondary("&7Server Status")
        .slot(4)           // Top center - automatically anchored
        .build();
}
```

### Viewport Anchoring (Initial Positioning)

Viewport anchoring controls where the player initially starts viewing in an oversized menu. Instead of always starting at the top-left, you can position the viewport at any anchor point within the virtual content area.

#### Basic Viewport Anchoring

```java
menuService.createMenuBuilder()
    .title("Large Content Area")
    .rows(15)              // Virtual content: 15 rows
    .viewPort(6)           // Visible area: 6 rows
    .columns(12)           // Virtual content: 12 columns (horizontal scrolling)
    .anchor(AnchorPoint.BOTTOM_RIGHT)  // Start viewing from bottom-right
    
    // Content throughout the large area
    .addButton(importantButton, 14, 11)  // Bottom-right content (immediately visible)
    .addButton(otherButton, 0, 0)        // Top-left content (requires scrolling)
    
    .buildAsync(player);
```

#### AnchorPoint Values

The `AnchorPoint` enum provides nine positioning options:

| AnchorPoint | Description | Use Case |
|-------------|-------------|----------|
| `TOP_LEFT` | Default position | Standard menus, reading order |
| `TOP_CENTER` | Top edge, horizontally centered | Wide menus with central focus |
| `TOP_RIGHT` | Top edge, right-aligned | Right-to-left interfaces |
| `CENTER_LEFT` | Vertically centered, left edge | Tall menus with left navigation |
| `CENTER` | Complete center of content | Radial layouts, centered content |
| `CENTER_RIGHT` | Vertically centered, right edge | Right-side focus areas |
| `BOTTOM_LEFT` | Bottom edge, left-aligned | Footer-style interfaces |
| `BOTTOM_CENTER` | Bottom edge, horizontally centered | Bottom-heavy layouts |
| `BOTTOM_RIGHT` | Bottom edge, right-aligned | Traditional UI patterns |

### Advanced Anchoring Examples

#### Shop with Fixed UI Elements

```java
private void createAdvancedShop(Player player) {
    menuService.createMenuBuilder()
        .title("&6Advanced Shop")
        .rows(12)          // Large content area
        .viewPort(6)       // Standard viewing window
        .columns(11)       // Wide content for categories
        .anchor(AnchorPoint.TOP_LEFT)  // Start from top
        
        // Fixed UI elements (automatically anchored via .slot())
        .addButton(MenuButton.builder(Material.COMPASS)
            .name("&eSearch Items")
            .slot(8)       // Top-right corner - automatically anchored
            .onClick(this::openSearch)
            .build())
            
        .addButton(MenuButton.builder(Material.CHEST)
            .name("&aCart (" + getCartItems(player) + ")")
            .slot(53)      // Bottom-right corner - automatically anchored
            .onClick(this::openCart)
            .build())
            
        .addButton(MenuButton.builder(Material.EMERALD)
            .name("&aBalance: $" + getBalance(player))
            .slot(45)      // Bottom-left corner - automatically anchored
            .build())
        
        // Scrollable shop content (coordinates = scrollable)
        .addButton(createShopItem("Weapons", Material.DIAMOND_SWORD), 0, 1)
        .addButton(createShopItem("Armor", Material.DIAMOND_CHESTPLATE), 0, 2)
        .addButton(createShopItem("Tools", Material.DIAMOND_PICKAXE), 0, 3)
        // ... many more items throughout the large area ...
        .addButton(createShopItem("Special", Material.NETHER_STAR), 11, 10)
        
        .buildAsync(player);
}
```

#### Map/World Viewer with Navigation

```java
private void createWorldViewer(Player player) {
    menuService.createMenuBuilder()
        .title("&b🗺️ World Map")
        .rows(20)          // Huge virtual map area
        .columns(20)       // Square map
        .viewPort(6)       // Normal viewing window
        .anchor(AnchorPoint.CENTER)  // Start from center of map
        
        // Fixed navigation controls (automatically anchored via .slot())
        .addButton(MenuButton.builder(Material.ARROW)
            .name("&7⬆ North")
            .slot(1)       // Top center - automatically anchored
            .onClick(p -> scrollDirection(p, "north"))
            .build())
            
        .addButton(MenuButton.builder(Material.ARROW)
            .name("&7⬇ South")
            .slot(46)      // Bottom center - automatically anchored
            .onClick(p -> scrollDirection(p, "south"))
            .build())
            
        .addButton(MenuButton.builder(Material.COMPASS)
            .name("&eRecenter")
            .slot(22)      // True center - automatically anchored
            .onClick(p -> recenterView(p))
            .build())
            
        // Map content spread across large area (coordinates = scrollable)
        .fillMapWithLocations()
        
        .buildAsync(player);
}
```

### Integration with Scrolling System

The simplified anchor system works seamlessly with the menu scrolling system:

#### How Button Placement Affects Scrolling

- **Slot-based buttons** (`.slot(X)`) remain fixed in their viewport positions during all scrolling
- **Coordinate-based buttons** (`row, col`) move with the content during scrolling
- **Mixed layouts** combine both fixed UI elements and scrollable content naturally

#### List Menu Auto-Anchoring

List menus automatically use slot-based placement for their navigation controls:

```java
menuService.createListMenu()
    .title("Player List")
    .rows(6)
    .addItems(playerItems)
    // Navigation buttons are automatically slot-based (fixed):
    // - Previous Page button (slot 45) - automatically anchored
    // - Page indicator (slot 49) - automatically anchored
    // - Next Page button (slot 53) - automatically anchored
    .buildAsync(player);
```

#### Scrolling Behavior Examples

```java
menuService.createMenuBuilder()
    .title("Mixed Layout Demo")
    .rows(10)
    .viewPort(6)
    
    // These buttons stay fixed during scrolling (slot-based)
    .addButton(MenuButton.builder(Material.COMPASS)
        .name("&eSearch")
        .slot(8)  // Fixed at top-right
        .build())
        
    .addButton(MenuButton.builder(Material.BARRIER)
        .name("&cClose")
        .slot(49) // Fixed at bottom-center
        .build())
    
    // These buttons scroll with content (coordinate-based)
    .addButton(contentButton1, 0, 1)  // Scrolls with content
    .addButton(contentButton2, 5, 3)  // Scrolls with content
    .addButton(contentButton3, 8, 7)  // Initially off-screen, visible when scrolled
    
    .buildAsync(player);
```

## MenuService

The main entry point for creating menus.

### Methods

**`createMenuBuilder()`**
Creates a new CustomMenuBuilder for positioned menus.
- Returns: `CustomMenuBuilder`
- Use when: You want precise control over item placement

**`createListMenu()`**
Creates a new ListMenuBuilder for paginated lists.
- Returns: `ListMenuBuilder` 
- Use when: You have a list of items that should be automatically paginated

**`openMenu(Menu menu, Player player)`**
Opens a menu for a specific player.
- Returns: `CompletableFuture<Void>`
- Use when: You have a pre-built menu to open

**`closeMenu(Player player)`**
Closes the currently open menu for a player.
- Returns: `boolean` (true if menu was closed)

**`hasMenuOpen(Player player)`**
Checks if a player has any menu open.
- Returns: `boolean`

**`refreshMenu(Player player)`**
Updates the contents of a player's current menu.
- Returns: `boolean` (true if menu was refreshed)

---

## CustomMenuBuilder

Creates menus with precise positioning and custom layouts.

### Basic Configuration

**`.title(String title)`**
Sets the menu title. Supports color codes (`&6Gold`).
```java
.title("&6My Shop")
```

**`.rows(int rows)`**
Sets the number of rows (1-6 for normal inventories).
```java
.rows(6) // Standard double chest size
```

**`.viewPort(int rows)`**
Sets the visible viewport size when using oversize menus.
```java
.viewPort(6) // Player sees 6 rows
.rows(10)    // But menu actually has 10 rows
```

**`.columns(int columns)`**
Sets the number of columns (9 = normal, 12+ = scrolling).
```java
.columns(12) // Enables horizontal scrolling
```

### Content Placement

**`.addButton(MenuButton button, int row, int column)`**
Places a clickable button at specific coordinates (0-based).
```java
.addButton(myButton, 0, 0)    // Top-left corner
.addButton(myButton, 2, 4)    // Row 2, center column
```

**`.addItem(MenuDisplayItem item, int row, int column)`**
Places a display item at specific coordinates.
```java
.addItem(infoItem, 1, 4) // Row 1, center
```

### Menu Relationships

**`.parentMenu(String menuId)`**
Creates an automatic back button that navigates to the specified parent menu.
```java
.parentMenu("main-menu") // Adds back button automatically
```

### Styling

**`.fillEmpty(Material material)`**
Fills all empty slots with the specified material.
```java
.fillEmpty(Material.BLACK_STAINED_GLASS_PANE)
```

**`.addBorder(Material material)`**
Adds a border around the menu edges.
```java
.addBorder(Material.GRAY_STAINED_GLASS_PANE)
```

### Advanced Features

**`.anchor(AnchorPoint anchor)`**
Sets where oversize menus start displaying.
```java
.anchor(AnchorPoint.BOTTOM) // Start viewing from bottom
.anchor(AnchorPoint.CENTRE) // Start from center
```

**`.autoRefresh(int intervalSeconds)`**
Automatically refreshes menu content.
```java
.autoRefresh(5) // Refresh every 5 seconds
```

### Building

**`.buildAsync(Player player)`**
Builds and opens the menu for the player.
- Returns: `CompletableFuture<Menu>`

**`.buildAsync()`**
Builds the menu without opening it.
- Returns: `CompletableFuture<Menu>`

---

## ListMenuBuilder

Creates paginated menus that automatically handle large lists of items.

### Basic Configuration

**`.title(String title)`**
Sets the menu title with color code support.
```java
.title("&bPlayer List")
```

**`.rows(int rows)`**
Sets menu height (1-6 rows).
```java
.rows(6)
```

### Content

**`.addItems(Collection<MenuItem> items)`**
Adds a collection of items that will be automatically paginated.
```java
List<MenuDisplayItem> items = getMyItems();
.addItems(items)
```

**`.addItems(Collection<T> objects, Function<T, MenuItem> transformer)`**
Converts objects to menu items and adds them.
```java
.addItems(
    Bukkit.getOnlinePlayers(),
    player -> MenuDisplayItem.builder(Material.PLAYER_HEAD)
        .name(player.getName())
        .build()
)
```

### Persistent Buttons

**`.addButton(MenuButton button, int slot)`**
Adds a button that appears on all pages at a specific slot.
```java
.addButton(MenuButton.CLOSE, 49) // Close button on all pages
```

### Styling

**`.addBorder(Material material)`**
Adds a border that appears on all pages.
```java
.addBorder(Material.ORANGE_STAINED_GLASS_PANE)
```

**`.fillEmpty(Material material)`**
Fills empty slots on all pages.
```java
.fillEmpty(Material.BLACK_STAINED_GLASS_PANE)
```

### Pagination

**`.initialPage(int page)`**
Sets which page to show first (1-based).
```java
.initialPage(2) // Start on page 2
```

**`.contentSlots(int startSlot, int endSlot)`**
Defines which slots are used for paginated content.
```java
.contentSlots(9, 44) // Slots 9-44 for content
```

---

## MenuButton

Interactive buttons that players can click.

### Creating Buttons

**`MenuButton.builder(Material material)`**
Starts building a new button.
```java
MenuButton.builder(Material.DIAMOND)
```

### Text & Display

**`.name(String name)`**
Sets the button's display name. Supports color codes.
```java
.name("&aConfirm")
.name("<green>Confirm</green>")
```

**`.secondary(String text)`**
Adds a secondary subtitle in gray text.
```java
.secondary("&7Click to confirm purchase")
```

**`.description(String text)`**
Adds description lines that automatically wrap.
```java
.description("&7This will purchase the item")
.description("&7and add it to your inventory")
```

**`.lore(String... lines)`**
Adds raw lore lines (alternative to `.description()`).
```java
.lore("&7Line 1", "&7Line 2", "&7Line 3")
```

### Click Handling

**`.onClick(Consumer<Player> handler)`**
Sets what happens when the button is clicked.
```java
.onClick(player -> player.sendMessage("Clicked!"))
.onClick(player -> {
    player.sendMessage("Hello!");
    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1, 1);
})
```

**`.onClick(ClickType clickType, Consumer<Player> handler)`**
Sets handlers for specific click types.
```java
.onClick(ClickType.LEFT, player -> buyItem(player))
.onClick(ClickType.RIGHT, player -> showItemInfo(player))
```

### Audio

**`.sound(Sound sound)`**
Plays a sound when clicked.
```java
.sound(Sound.UI_BUTTON_CLICK)
.sound(Sound.ENTITY_PLAYER_LEVELUP)
```

### Properties

**`.amount(int amount)`**
Sets the stack size (1-64).
```java
.amount(5) // Shows as stack of 5
```

**`.slot(int slot)`**
Pre-assigns a slot position. **Buttons with `.slot()` are automatically anchored** and remain fixed in that viewport position during scrolling.
```java
.slot(22) // Automatically anchored at slot 22 - stays fixed during scrolling
.slot(49) // Bottom-center - perfect for close/back buttons
```

> **Automatic Anchoring**: Any button using `.slot()` is automatically anchored and will stay fixed in that viewport position while other content scrolls around it. This eliminates the need for redundant `.anchor()` calls.

**`.cooldown(Duration duration)`**
Adds a cooldown between clicks.
```java
.cooldown(Duration.ofSeconds(3)) // 3-second cooldown
```

---

## MenuDisplayItem

Non-interactive items for decoration or information display.

### Creating Display Items

**`MenuDisplayItem.builder(Material material)`**
Starts building a new display item.
```java
MenuDisplayItem.builder(Material.BOOK)
```

### Text & Display

**`.name(String name)`**
Sets the item's display name.
```java
.name("&eInformation")
```

**`.secondary(String text)`**
Adds a gray subtitle.
```java
.secondary("&7Server Statistics")
```

**`.description(String text)`**
Adds description text with automatic wrapping.
```java
.description("&7Players online: " + Bukkit.getOnlinePlayers().size())
```

**`.lore(String... lines)`**
Adds raw lore lines.
```java
.lore("&7Line 1", "&7Line 2")
```

### Properties

**`.amount(int amount)`**
Sets the stack size display.
```java
.amount(10)
```

**`.slot(int slot)`**
Pre-assigns a slot position. **Display items with `.slot()` are automatically anchored** and remain fixed in that viewport position during scrolling.
```java
.slot(4) // Center of top row - automatically anchored
.slot(22) // Center position - stays fixed during scrolling
```

> **Automatic Anchoring**: Any display item using `.slot()` is automatically anchored and will stay fixed in that viewport position while other content scrolls around it.

---

## Best Practices

### Text Formatting
**Preferred**: Use `.secondary()` and `.description()` for better formatting
```java
.name("&aConfirm Purchase")
.secondary("&7Click to buy this item")
.description("&7This will cost 100 coins")
```

**Avoid**: Using `.lore()` for everything
```java
.lore("&7Click to buy this item", "&7This will cost 100 coins")
```

### Button Creation
**For simple buttons**: Create inline
```java
.addButton(
    MenuButton.builder(Material.EMERALD)
        .name("Quick Action")
        .onClick(p -> doSomething())
        .build(),
    1, 1)
```

**For complex buttons**: Create as variables
```java
MenuButton complexButton = MenuButton.builder(Material.DIAMOND)
    .name("Complex Action")
    .secondary("Multiple features")
    .description("This button does many things")
    .onClick(ClickType.LEFT, p -> doAction1())
    .onClick(ClickType.RIGHT, p -> doAction2())
    .cooldown(Duration.ofSeconds(5))
    .sound(Sound.UI_BUTTON_CLICK)
    .build();

.addButton(complexButton, 2, 2)
```

### Menu Organization
- Use descriptive menu IDs: `"main-menu"`, `"player-settings"`, `"shop-armor"`
- Register parent menus before creating children
- Keep menu hierarchies shallow (2-3 levels max)
- Use consistent styling across related menus