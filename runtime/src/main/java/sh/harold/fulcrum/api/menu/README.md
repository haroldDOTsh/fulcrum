# Menu API

Inventory-based GUI system with automatic pagination and parent-child navigation.

## Quick Start

```java
// Basic menu
menuService.createMenuBuilder()
    .title("My Menu")
    .rows(3)
    .addButton(
        MenuButton.builder(Material.DIAMOND)
            .name("&aClick Me")
            .onClick(p -> p.sendMessage("Clicked!"))
            .build(), 
        1, 1)
    .buildAsync(player);

// List menu with pagination
menuService.createListMenu()
    .title("&bPlayer List")
    .rows(6)
    .addBorder(Material.BLUE_STAINED_GLASS_PANE)
    .addItems(onlinePlayers, player -> 
        MenuDisplayItem.builder(Material.PLAYER_HEAD)
            .name("&e" + player.getName())
            .build())
    .buildAsync(player);
```

## Menu Types

### CustomMenuBuilder
Position-based menus with viewport support for scrollable content.

```java
menuService.createMenuBuilder()
    .title("&6Shop")
    .rows(10)        // Virtual size
    .viewPort(6)     // Visible rows
    .columns(12)     // Horizontal scrolling
    .anchor(AnchorPoint.TOP_LEFT)
    
    // Fixed UI controls (use .slot())
    .addButton(MenuButton.builder(Material.COMPASS)
        .name("&eSearch")
        .slot(8)  // Stays fixed during scrolling
        .onClick(this::openSearch)
        .build())
    
    // Scrollable content (use coordinates)
    .addButton(itemButton, 3, 4)  // Scrolls with content
    .fillEmpty(Material.BLACK_STAINED_GLASS_PANE)
    .buildAsync(player);
```

### ListMenuBuilder
Automatic pagination for item lists.

```java
menuService.createListMenu()
    .title("&aItem List")
    .rows(6)
    .addBorder(Material.GREEN_STAINED_GLASS_PANE)
    .addItems(items)  // Auto-paginated
    .addButton(closeButton, 49)  // Persistent across pages
    .buildAsync(player);
```

## Menu Navigation

### Parent-Child Relationships

```java
// Register parent menu
Menu mainMenu = menuService.createMenuBuilder()
    .title("Main Menu")
    .addButton(shopButton, 1, 1)
    .build();
menuService.registerMenuInstance("main", mainMenu);

// Child menu with automatic back button
menuService.createMenuBuilder()
    .title("Shop")
    .parentMenu("main")  // Adds back button
    .addButton(buyButton, 2, 2)
    .buildAsync(player);
```

### Menu Instance Management

```java
// Register for later use
menuService.registerMenuInstance("shop", shopMenu);

// Open registered menu
menuService.openMenuInstance("shop", player);

// Check if exists
if (menuService.hasMenuInstance("shop")) {
    // ...
}
```

## MenuButton

Interactive elements with click handlers.

```java
MenuButton.builder(Material.EMERALD)
    .name("&aBuy Item")
    .secondary("&7Cost: 100 coins")
    .description("&7Click to purchase")
    .amount(5)
    .onClick(p -> purchaseItem(p))
    .onClick(ClickType.RIGHT, p -> showInfo(p))
    .sound(Sound.UI_BUTTON_CLICK)
    .cooldown(Duration.ofSeconds(3))
    .slot(22)  // Auto-anchored if set
    .build();
```

## MenuDisplayItem

Non-interactive display elements.

```java
MenuDisplayItem.builder(Material.BOOK)
    .name("&eInformation")
    .secondary("&7Server Stats")
    .description("&7Players: " + playerCount)
    .lore("&7Line 1", "&7Line 2")
    .amount(1)
    .slot(4)  // Auto-anchored if set
    .build();
```

## Scrollable Menus

### Viewport System

```java
menuService.createMenuBuilder()
    .title("Large Inventory")
    .viewPort(6)    // Player sees 6 rows
    .rows(15)       // Virtual: 15 rows
    .columns(12)    // Virtual: 12 columns
    
    // Items beyond viewport require scrolling
    .addButton(hiddenButton, 10, 10)
    
    // Add scroll controls
    .addScrollButtons()
    .onScroll((player, oldRow, oldCol, newRow, newCol) -> {
        // Handle scroll event
    })
    .buildAsync(player);
```

### Anchoring System

**Automatic anchoring based on placement method:**
- `.slot(n)` → Fixed in viewport (doesn't scroll)
- `.addButton(btn, row, col)` → Scrolls with content

```java
// Fixed navigation (slot-based)
.addButton(MenuButton.builder(Material.BARRIER)
    .name("&cClose")
    .slot(49)  // Always visible at bottom center
    .build())

// Scrollable content (coordinate-based)
.addButton(itemButton, 5, 3)  // Moves with scroll
```

## Dynamic Content

### Auto-refresh

```java
menuService.createMenuBuilder()
    .title("Live Stats")
    .autoRefresh(5)  // Refresh every 5 seconds
    .dynamicContent(() -> generateItems())
    .buildAsync(player);
```

### Page Change Handler

```java
menuService.createListMenu()
    .title("Pages")
    .onPageChange((player, oldPage, newPage) -> {
        player.sendMessage("Page " + newPage);
    })
    .buildAsync(player);
```

## MenuService Methods

### Creation
- `createMenuBuilder()` - Custom positioned menu
- `createListMenu()` - Paginated list menu

### Control
- `openMenu(menu, player)` - Open a menu
- `closeMenu(player)` - Close current menu
- `refreshMenu(player)` - Update content
- `hasMenuOpen(player)` - Check if open
- `getOpenMenu(player)` - Get current menu

### Instance Management
- `registerMenuInstance(id, menu)` - Save for reuse
- `openMenuInstance(id, player)` - Open saved menu
- `getMenuInstance(id)` - Retrieve saved menu
- `hasMenuInstance(id)` - Check if exists

### Templates
- `getMenuRegistry()` - Access template registry
- `openMenuTemplate(id, player)` - Open from template

## Complete Examples

### Shop Menu

```java
public void createShop(Player player) {
    // Main shop menu
    Menu shop = menuService.createMenuBuilder()
        .title("&6Shop")
        .rows(6)
        .fillEmpty(Material.GRAY_STAINED_GLASS_PANE)
        
        // Categories
        .addButton(createCategory("Weapons", Material.DIAMOND_SWORD), 1, 2)
        .addButton(createCategory("Armor", Material.DIAMOND_CHESTPLATE), 1, 4)
        .addButton(createCategory("Tools", Material.DIAMOND_PICKAXE), 1, 6)
        
        // Fixed controls
        .addButton(MenuButton.builder(Material.EMERALD)
            .name("&aBalance: $" + getBalance(player))
            .slot(45)
            .build())
        .addButton(MenuButton.builder(Material.BARRIER)
            .name("&cClose")
            .slot(49)
            .onClick(p -> p.closeInventory())
            .build())
        
        .build();
    
    menuService.registerMenuInstance("shop", shop);
    menuService.openMenu(shop, player);
}

private MenuButton createCategory(String name, Material icon) {
    return MenuButton.builder(icon)
        .name("&e" + name)
        .onClick(p -> openCategory(p, name))
        .build();
}
```

### Player List with Actions

```java
public void showPlayerList(Player viewer) {
    List<MenuButton> playerButtons = Bukkit.getOnlinePlayers().stream()
        .map(player -> MenuButton.builder(Material.PLAYER_HEAD)
            .name("&e" + player.getName())
            .secondary("&7Level " + player.getLevel())
            .description(
                "&7World: " + player.getWorld().getName(),
                "&7Health: " + (int)player.getHealth() + "/" + (int)player.getMaxHealth()
            )
            .onClick(ClickType.LEFT, p -> p.teleport(player))
            .onClick(ClickType.RIGHT, p -> openPlayerMenu(p, player))
            .build())
        .collect(Collectors.toList());
    
    menuService.createListMenu()
        .title("&bOnline Players")
        .rows(6)
        .addBorder(Material.BLUE_STAINED_GLASS_PANE)
        .addItems(playerButtons)
        .emptyMessage(Component.text("No players online"))
        .buildAsync(viewer);
}
```

### Large Scrollable Inventory

```java
public void createLargeInventory(Player player) {
    menuService.createMenuBuilder()
        .title("&5Treasure Vault")
        .viewPort(6)
        .rows(20)
        .columns(15)
        .anchor(AnchorPoint.CENTER)
        
        // Fixed search button
        .addButton(MenuButton.builder(Material.COMPASS)
            .name("&eSearch Items")
            .slot(8)
            .onClick(this::openSearch)
            .build())
        
        // Add many items in grid
        for (int row = 0; row < 20; row++) {
            for (int col = 0; col < 15; col++) {
                if (hasItemAt(row, col)) {
                    addButton(getItemAt(row, col), row, col);
                }
            }
        }
        
        .addScrollButtons()
        .buildAsync(player);
}