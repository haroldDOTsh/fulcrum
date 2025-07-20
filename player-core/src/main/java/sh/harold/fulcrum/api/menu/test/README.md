# Menu API Demo System

This package contains a comprehensive demonstration of the Fulcrum Menu API system, showcasing both CustomMenuBuilder and ListMenuBuilder functionality.

## Overview

The Menu API Demo provides interactive examples that demonstrate:

- **CustomMenuBuilder**: Viewport-based menus with virtual coordinates and scrolling
- **ListMenuBuilder**: Paginated menus with automatic item transformation and navigation
- **Component System**: MenuButton and MenuDisplayItem usage with click handlers
- **Security Features**: Cooldowns, permission checks, and rate limiting
- **Modern Paper Commands**: Brigadier command integration

## Files

### MenuDemoCommand.java

The main demonstration command that provides:

- **Main Menu**: Overview of available demos with navigation buttons
- **Custom Menu Demo**: Shows viewport system, virtual coordinates, and button interactions
- **List Menu Demo**: Demonstrates pagination, item transformation, and automatic navigation

## Usage

### Command Registration

The demo command uses the modern Paper command API and should be registered in a feature class:

```java
@Override
protected void registerCommands(CommandRegistrar registrar) {
    MenuDemoCommand menuDemoCommand = new MenuDemoCommand(menuService);
    registrar.register(menuDemoCommand.build());
}
```

### Permission

The command requires the permission: `fulcrum.menu.demo`

### Commands

- `/menudemo` - Opens the main demo menu
- `/menudemo custom` - Directly opens the custom menu demo
- `/menudemo list` - Directly opens the list menu demo

## Custom Menu Demo Features

The custom menu demonstration showcases:

### Viewport System
```java
menuService.createMenuBuilder()
    .title("Custom Menu Demo")
    .viewPort(5)           // 5 rows visible
    .rows(8)               // 8 virtual rows total
    .columns(9)            // Standard 9 columns
```

### Virtual Coordinates
Items can be placed anywhere in the virtual space:
```java
.addButton(button, 2, 1)  // Row 2, Column 1
.addItem(item, 6, 4)      // Row 6, Column 4
```

### Scroll Navigation
```java
.addScrollButtons()       // Automatic scroll button placement
```

### Button Interactions
- **Success Action**: Demonstrates basic click handling
- **Info Action**: Shows informational messages
- **Warning Action**: Displays warning messages  
- **Cooldown Button**: 5-second cooldown demonstration

## List Menu Demo Features

The list menu demonstration showcases:

### Automatic Pagination
```java
menuService.createListMenu()
    .title("List Menu Demo")
    .rows(6)                    // 6-row inventory
    .addBorder(Material.GRAY_STAINED_GLASS_PANE, " ")
```

### Item Transformation
```java
.addItems(items, item -> MenuButton.builder(item.material())
    .name("<" + item.color() + ">" + item.name())
    .lore("<gray>Category: " + item.category())
    .onClick(p -> p.sendMessage("Selected: " + item.name()))
    .build())
```

### Navigation Controls
```java
.addNavigationButtons()     // Previous/Next page buttons
.addPageIndicator(49)       // Current page display
```

### Border Decoration
```java
.addBorder(Material.GRAY_STAINED_GLASS_PANE, " ")
```

## API Examples

### Basic Button Creation
```java
MenuButton.builder(Material.EMERALD)
    .name("<green>Success Action")
    .lore("<gray>Click for a success message")
    .onClick(p -> p.sendMessage("Success!"))
    .build()
```

### Cooldown Implementation
```java
MenuButton.builder(Material.CLOCK)
    .name("<light_purple>Cooldown Button")
    .cooldown(Duration.ofSeconds(5))
    .onClick(p -> p.sendMessage("Cooldown activated!"))
    .build()
```

### Display Item Creation
```java
MenuDisplayItem.builder(Material.DIAMOND)
    .name("<gold>Information")
    .lore("<gray>This is a display-only item")
    .build()
```

### Virtual Positioning
```java
.addButton(button, virtualRow, virtualColumn)
.addItem(item, virtualRow, virtualColumn)
```

## Demo Data Generation

The demo includes a `generateDemoItems()` method that creates sample data with:

- 47 items (to demonstrate pagination)
- 5 categories: Weapon, Tool, Material, Food, Armor
- 5 rarity levels: Common, Uncommon, Rare, Epic, Legendary
- Color-coded display based on rarity
- Random values for demonstration

## Integration

To integrate the menu demo into your plugin:

1. **Add to MenuFeature**:
```java
@Override
protected void registerCommands(CommandRegistrar registrar) {
    MenuDemoCommand menuDemoCommand = new MenuDemoCommand(menuService);
    registrar.register(menuDemoCommand.build());
}
```

2. **Set Permission**: Grant `fulcrum.menu.demo` to users
3. **Use Command**: Players can run `/menudemo` to test the system

## Educational Value

This demo serves as both:

- **Validation**: Ensures all menu system components work correctly
- **Documentation**: Living examples of how to use the Menu API
- **Reference**: Developers can copy patterns for their own menus

## Dependencies

The demo requires:
- Fulcrum Menu API (`menu-api` module)
- Adventure Components (for text formatting)
- Paper API (for modern command system)
- Bukkit/Spigot (for inventory and player interactions)

## Notes

- The demo uses MiniMessage formatting for colors and text styling
- All buttons include appropriate lore and click prompts
- Error handling demonstrates proper exception management
- The system automatically handles inventory cleanup and event registration