package sh.harold.fulcrum.api.menu.test;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.menu.MenuService;
import sh.harold.fulcrum.api.menu.MenuRegistry;
import sh.harold.fulcrum.api.menu.component.MenuButton;
import sh.harold.fulcrum.api.menu.component.MenuDisplayItem;
import sh.harold.fulcrum.api.menu.impl.DefaultMenuService;

import static io.papermc.paper.command.brigadier.Commands.literal;

/**
 * Demonstrates the new refactored menu system with explicit .parentMenu() API.
 * 
 * Key Benefits:
 * - No more complex auto-logic or NavigationMode
 * - Simple and explicit parent-child relationships
 * - Just use .parentMenu("menu-id") to add back buttons
 * - Clean, predictable API
 */
public final class MenuDemoCommand {
    
    private final MenuService menuService;
    
    public MenuDemoCommand(MenuService menuService) {
        this.menuService = menuService;
    }
    
    public LiteralCommandNode<CommandSourceStack> build() {
        return literal("menudemo")
            .requires(source -> source.getSender().hasPermission("fulcrum.menu.demo"))
            .executes(this::showMainMenu)
            .build();
    }
    
    private int showMainMenu(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            ctx.getSource().getSender().sendMessage(Component.text("This command can only be used by players!", NamedTextColor.RED));
            return 0;
        }
        
        // Create and register main menu instance - this enables child menus to reference it
        menuService.createMenuBuilder()
            .title("&6✨ New Simplified Menu API Demo")
            .rows(6)
            .fillEmpty(Material.BLACK_STAINED_GLASS_PANE)
            
            // Header explaining the new system
            .addItem(MenuDisplayItem.builder(Material.EMERALD)
                .name("&rRefactored Menu System")
                .secondary("&7No more auto-this auto-that!")
                .description("&7Simple .parentMenu() method")
                .description("&7Clean and explicit relationships")
                .description("&7No complex NavigationMode logic")
                .build(), 0, 4)
            
            // Shop menu example
            .addButton(MenuButton.builder(Material.CHEST)
                .name("Shop Menu")
                .secondary("&7Click to open shop")
                .description("&7This will have a back button")
                .description("&7Uses: .parentMenu(\"main-menu\")")
                .onClick(this::openShopMenu)
                .build(), 2, 2)
            
            // Settings menu example
            .addButton(MenuButton.builder(Material.REDSTONE_TORCH)
                .name("&c⚙ Settings Menu")
                .secondary("&7Click to open settings")
                .description("&7This will also have a back button")
                .description("&7Uses: .parentMenu(\"main-menu\")")
                .onClick(this::openSettingsMenu)
                .build(), 2, 4)
            
            // List menu example
            .addButton(MenuButton.builder(Material.BOOK)
                .name("&e📚 Items List")
                .secondary("&7Click to browse items")
                .description("&7Demonstrates ListMenu with parent")
                .description("&7Uses: .parentMenu(\"main-menu\")")
                .onClick(this::openItemsList)
                .build(), 2, 6)
            
            // Formatting test menu - NEW: Dual approach testing
            .addButton(MenuButton.builder(Material.ENCHANTED_BOOK)
                .name("&d✨ Formatting Test")
                .secondary("&7Test dual approach formatting")
                .description("&7Legacy codes + Adventure API")
                .description("&7Tests italic formatting fixes")
                .onClick(this::openFormattingTest)
                .build(), 4, 4)
            
            .buildAsync(player)
            .thenAccept(menu -> {
                // NEW: Register the menu instance so children can reference it
                ((sh.harold.fulcrum.api.menu.impl.DefaultMenuService) menuService)
                    .registerMenuInstance("main-menu", menu);
                System.out.println("[MENU DEMO] Main menu registered as instance: main-menu");
            });
            
        player.sendMessage(Component.text("✅ New API demo menu opened!", NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }
    
    /**
     * Shop menu - demonstrates CustomMenuBuilder with parent menu
     */
    private void openShopMenu(Player player) {
        menuService.createMenuBuilder()
            .title("&b🏪 Shop Menu")
            .rows(6)
            .parentMenu("main-menu")  // NEW API: Explicit parent - adds back button!
            .fillEmpty(Material.BLUE_STAINED_GLASS_PANE)
            
            .addItem(MenuDisplayItem.builder(Material.DIAMOND)
                .name("&b💎 Shop Items")
                .secondary("&7This menu has a back button!")
                .description("&7Added via .parentMenu(\"main-menu\")")
                .description("&7Click back button to return to main")
                .build(), 1, 4)
            
            // Sub-shop menu
            .addButton(MenuButton.builder(Material.GOLD_INGOT)
                .name("&6🏪 Premium Shop")
                .secondary("&7Open premium items")
                .description("&7This creates a 3-level hierarchy")
                .description("&7Uses: .parentMenu(\"shop-menu\")")
                .onClick(this::openPremiumShop)
                .build(), 2, 2)
            
            .addButton(MenuButton.builder(Material.EMERALD)
                .name("&a💰 Buy Something")
                .secondary("&7Test purchase action")
                .onClick(p -> p.sendMessage(Component.text("&a✅ Purchase successful!", NamedTextColor.GREEN)))
                .build(), 2, 6)
            
            .buildAsync(player)
            .thenAccept(menu -> {
                // NEW: Register the shop menu instance so children can reference it
                ((sh.harold.fulcrum.api.menu.impl.DefaultMenuService) menuService)
                    .registerMenuInstance("shop-menu", menu);
                System.out.println("[MENU DEMO] Shop menu registered as instance: shop-menu");
            });
    }
    
    /**
     * Premium shop - demonstrates 3-level menu hierarchy
     */
    private void openPremiumShop(Player player) {
        menuService.createMenuBuilder()
            .title("&6👑 Premium Shop")
            .rows(6)
            .parentMenu("shop-menu")  // NEW API: Back to shop menu
            .fillEmpty(Material.YELLOW_STAINED_GLASS_PANE)
            
            .addItem(MenuDisplayItem.builder(Material.NETHER_STAR)
                .name("&6👑 Premium Items")
                .secondary("&7Third level menu!")
                .description("&7Path: Main → Shop → Premium")
                .description("&7Back button returns to Shop")
                .build(), 1, 4)
            
            .addButton(MenuButton.builder(Material.BEACON)
                .name("&e⭐ VIP Package")
                .secondary("&7&o$99.99")
                .description("&7Premium membership benefits")
                .onClick(p -> p.sendMessage(Component.text("&e⭐ VIP package purchased!", NamedTextColor.YELLOW)))
                .build(), 2, 4)
            
            .buildAsync(player);
    }
    
    /**
     * Settings menu - demonstrates another branch of navigation
     */
    private void openSettingsMenu(Player player) {
        menuService.createMenuBuilder()
            .title("&c⚙ Settings")
            .rows(6)
            .parentMenu("main-menu")  // NEW API: Back to main menu
            .fillEmpty(Material.RED_STAINED_GLASS_PANE)
            
            .addItem(MenuDisplayItem.builder(Material.REDSTONE_TORCH)
                .name("&c⚙ Configuration")
                .secondary("&7Adjust your preferences")
                .description("&7Back button returns to main menu")
                .build(), 1, 4)
            
            .addButton(MenuButton.builder(Material.SPECTRAL_ARROW)
                .name("&a↑ Test Oversize Rows")
                .secondary("&7Test vertical scrolling")
                .description("&7with >6 rows")
                .onClick(this::showOversizeRowsMenu)
                .build(), 2, 2)
            
            .addButton(MenuButton.builder(Material.COMPASS)
                .name("&b→ Test Oversize Columns")
                .secondary("&7Test horizontal scrolling")
                .description("&7with >9 columns")
                .onClick(this::showOversizeColumnsMenu)
                .build(), 2, 6)
            
            .buildAsync(player)
            .thenAccept(menu -> {
                // Register the settings menu instance so children can reference it
                ((DefaultMenuService) menuService).registerMenuInstance("settings-menu", menu);
                System.out.println("[MENU DEMO] Settings menu registered as instance: settings-menu");
            });
    }
    
    /**
     * Items list - demonstrates ListMenuBuilder with parent menu
     */
    private void openItemsList(Player player) {
        System.out.println("[DEBUG LIST MENU] Starting openItemsList for player: " + player.getName());
        
        try {
            menuService.createListMenu()
                .title("📚 Items Collection")
                .rows(6)
                .parentMenu("main-menu")  // NEW API: Back to main menu
                .addBorder(Material.ORANGE_STAINED_GLASS_PANE)
                
                // Add sample items to demonstrate pagination
                .addItems(
                    MenuDisplayItem.builder(Material.APPLE).name("&cApple").secondary("&7A tasty red apple").build(),
                    MenuDisplayItem.builder(Material.BREAD).name("&eBread").secondary("&7Fresh baked bread").build(),
                    MenuDisplayItem.builder(Material.COOKED_BEEF).name("&4Steak").secondary("&7Perfectly cooked steak").build(),
                    MenuDisplayItem.builder(Material.GOLDEN_APPLE).name("&6Golden Apple").secondary("&7Magical healing fruit").build(),
                    MenuDisplayItem.builder(Material.CAKE).name("&fCake").secondary("&7Delicious birthday cake").build(),
                    MenuDisplayItem.builder(Material.COOKIE).name("&6Cookie").secondary("&7Chocolate chip cookie").build(),
                    MenuDisplayItem.builder(Material.MELON_SLICE).name("&aWatermelon").secondary("&7Juicy melon slice").build(),
                    MenuDisplayItem.builder(Material.CARROT).name("&6Carrot").secondary("&7Crunchy orange carrot").build(),
                    MenuDisplayItem.builder(Material.POTATO).name("&7Potato").secondary("&7Versatile root vegetable").build(),
                    MenuDisplayItem.builder(Material.BEETROOT).name("&5Beetroot").secondary("&7Sweet purple beetroot").build(),
                    MenuDisplayItem.builder(Material.APPLE).name("&cApple").secondary("&7A tasty red apple").build(),
                    MenuDisplayItem.builder(Material.BREAD).name("&eBread").secondary("&7Fresh baked bread").build(),
                    MenuDisplayItem.builder(Material.COOKED_BEEF).name("&4Steak").secondary("&7Perfectly cooked steak").build(),
                    MenuDisplayItem.builder(Material.GOLDEN_APPLE).name("&6Golden Apple").secondary("&7Magical healing fruit").build(),
                    MenuDisplayItem.builder(Material.CAKE).name("&fCake").secondary("&7Delicious birthday cake").build(),
                    MenuDisplayItem.builder(Material.COOKIE).name("&6Cookie").secondary("&7Chocolate chip cookie").build(),
                    MenuDisplayItem.builder(Material.MELON_SLICE).name("&aWatermelon").secondary("&7Juicy melon slice").build(),
                    MenuDisplayItem.builder(Material.CARROT).name("&6Carrot").secondary("&7Crunchy orange carrot").build(),
                    MenuDisplayItem.builder(Material.POTATO).name("&7Potato").secondary("&7Versatile root vegetable").build(),
                    MenuDisplayItem.builder(Material.BEETROOT).name("&5Beetroot").secondary("&7Sweet purple beetroot").build(),
                    MenuDisplayItem.builder(Material.APPLE).name("&cApple").secondary("&7A tasty red apple").build(),
                    MenuDisplayItem.builder(Material.BREAD).name("&eBread").secondary("&7Fresh baked bread").build(),
                    MenuDisplayItem.builder(Material.COOKED_BEEF).name("&4Steak").secondary("&7Perfectly cooked steak").build(),
                    MenuDisplayItem.builder(Material.GOLDEN_APPLE).name("&6Golden Apple").secondary("&7Magical healing fruit").build(),
                    MenuDisplayItem.builder(Material.CAKE).name("&fCake").secondary("&7Delicious birthday cake").build(),
                    MenuDisplayItem.builder(Material.COOKIE).name("&6Cookie").secondary("&7Chocolate chip cookie").build(),
                    MenuDisplayItem.builder(Material.MELON_SLICE).name("&aWatermelon").secondary("&7Juicy melon slice").build(),
                    MenuDisplayItem.builder(Material.CARROT).name("&6Carrot").secondary("&7Crunchy orange carrot").build(),
                    MenuDisplayItem.builder(Material.POTATO).name("&7Potato").secondary("&7Versatile root vegetable").build(),
                    MenuDisplayItem.builder(Material.BEETROOT).name("&5Beetroot").secondary("&7Sweet purple beetroot").build()
                )
                
                .buildAsync(player)
                .thenAccept(menu -> {
                    System.out.println("[DEBUG LIST MENU] Menu built successfully: " + menu.getId());
                })
                .exceptionally(throwable -> {
                    System.err.println("[DEBUG LIST MENU] Exception during buildAsync:");
                    throwable.printStackTrace();
                    player.sendMessage(Component.text("&c❌ Failed to open items list: " + throwable.getMessage(), NamedTextColor.RED));
                    return null;
                });
                
        } catch (Exception e) {
            System.err.println("[DEBUG LIST MENU] Exception in openItemsList:");
            e.printStackTrace();
            player.sendMessage(Component.text("&c❌ Error opening items list", NamedTextColor.RED));
        }
        
        System.out.println("[DEBUG LIST MENU] openItemsList method completed");
    }
    
    /**
     * Oversize rows menu - demonstrates vertical scrolling with >6 rows
     */
    private void showOversizeRowsMenu(Player player) {
        menuService.createMenuBuilder()
            .title("&a↑ Oversize Rows Test (8 rows)")
            .viewPort(6) // 6-row viewport
            .rows(8)     // 8 total rows (triggers vertical scrolling)
            .parentMenu("settings-menu")
            .fillEmpty(Material.GRAY_STAINED_GLASS_PANE)
            
            .addItem(MenuDisplayItem.builder(Material.EMERALD)
                .name("&a↑ Vertical Scrolling Test")
                .secondary("&78 rows with 6-row viewport")
                .description("&7This menu demonstrates vertical pagination")
                .description("&7Use up/down buttons to scroll through content")
                .description("&7Navigation row has black stained glass panes")
                .build(), 0, 4)
            
            // Row 1 - Test content
            .addButton(MenuButton.builder(Material.DIAMOND)
                .name("&bRow 1 - Diamond")
                .secondary("&7First row content")
                .onClick(p -> p.sendMessage(Component.text("&bClicked Row 1 Diamond!", NamedTextColor.AQUA)))
                .build(), 1, 1)
            
            .addButton(MenuButton.builder(Material.EMERALD)
                .name("&aRow 1 - Emerald")
                .secondary("&7First row content")
                .onClick(p -> p.sendMessage(Component.text("&aClicked Row 1 Emerald!", NamedTextColor.GREEN)))
                .build(), 1, 4)
            
            .addButton(MenuButton.builder(Material.GOLD_INGOT)
                .name("&6Row 1 - Gold")
                .secondary("&7First row content")
                .onClick(p -> p.sendMessage(Component.text("&6Clicked Row 1 Gold!", NamedTextColor.GOLD)))
                .build(), 1, 7)
            
            // Row 2 - Test content
            .addButton(MenuButton.builder(Material.IRON_INGOT)
                .name("&7Row 2 - Iron")
                .secondary("&7Second row content")
                .onClick(p -> p.sendMessage(Component.text("&7Clicked Row 2 Iron!", NamedTextColor.GRAY)))
                .build(), 2, 2)
            
            .addButton(MenuButton.builder(Material.COAL)
                .name("&8Row 2 - Coal")
                .secondary("&7Second row content")
                .onClick(p -> p.sendMessage(Component.text("&8Clicked Row 2 Coal!", NamedTextColor.DARK_GRAY)))
                .build(), 2, 6)
            
            // Row 3 - Test content
            .addButton(MenuButton.builder(Material.REDSTONE)
                .name("&cRow 3 - Redstone")
                .secondary("&7Third row content")
                .onClick(p -> p.sendMessage(Component.text("&cClicked Row 3 Redstone!", NamedTextColor.RED)))
                .build(), 3, 3)
            
            .addButton(MenuButton.builder(Material.LAPIS_LAZULI)
                .name("&9Row 3 - Lapis")
                .secondary("&7Third row content")
                .onClick(p -> p.sendMessage(Component.text("&9Clicked Row 3 Lapis!", NamedTextColor.BLUE)))
                .build(), 3, 5)
            
            // Row 4 (extends beyond viewport) - Test content
            .addButton(MenuButton.builder(Material.QUARTZ)
                .name("&fRow 4 - Quartz (Hidden)")
                .secondary("&7Fourth row - scroll down to see")
                .onClick(p -> p.sendMessage(Component.text("&fClicked Row 4 Quartz! (Was hidden)", NamedTextColor.WHITE)))
                .build(), 4, 1)
            
            .addButton(MenuButton.builder(Material.OBSIDIAN)
                .name("&5Row 4 - Obsidian (Hidden)")
                .secondary("&7Fourth row - scroll down to see")
                .onClick(p -> p.sendMessage(Component.text("&5Clicked Row 4 Obsidian! (Was hidden)", NamedTextColor.DARK_PURPLE)))
                .build(), 4, 8)
            
            // Row 7 (far bottom) - Test content
            .addButton(MenuButton.builder(Material.NETHER_STAR)
                .name("&eRow 7 - Nether Star (Deep)")
                .secondary("&7Bottom row - requires scrolling")
                .onClick(p -> p.sendMessage(Component.text("&eClicked Row 7 Nether Star! (Deep scroll)", NamedTextColor.YELLOW)))
                .build(), 7, 4)
            
            .buildAsync(player)
            .thenAccept(menu -> {
                // Register the menu instance so the settings menu can reference it
                ((DefaultMenuService) menuService).registerMenuInstance("oversize-rows-test", menu);
                System.out.println("[MENU DEMO] Oversize rows test menu registered as instance: oversize-rows-test");
            });
    }
    
    /**
     * Oversize columns menu - demonstrates horizontal scrolling with >9 columns
     */
    private void showOversizeColumnsMenu(Player player) {
        menuService.createMenuBuilder()
            .title("&b→ Oversize Columns Test (12 cols)")
            .viewPort(6)   // 6-row viewport
            .columns(12)   // 12 total columns (triggers horizontal scrolling)
            .parentMenu("settings-menu")
            .fillEmpty(Material.GRAY_STAINED_GLASS_PANE)
            
            .addItem(MenuDisplayItem.builder(Material.COMPASS)
                .name("&b→ Horizontal Scrolling Test")
                .secondary("&712 columns with 9-column viewport")
                .description("&7This menu demonstrates horizontal pagination")
                .description("&7Use left/right buttons to scroll through content")
                .description("&7Navigation row has black stained glass panes")
                .build(), 0, 6)
            
            // Row 1 - Extended content across 12 columns
            .addButton(MenuButton.builder(Material.RED_WOOL)
                .name("&cCol 1 - Red")
                .secondary("&7Column 1 content")
                .onClick(p -> p.sendMessage(Component.text("&cClicked Column 1 Red!", NamedTextColor.RED)))
                .build(), 1, 0)
            
            .addButton(MenuButton.builder(Material.ORANGE_WOOL)
                .name("&6Col 2 - Orange")
                .secondary("&7Column 2 content")
                .onClick(p -> p.sendMessage(Component.text("&6Clicked Column 2 Orange!", NamedTextColor.GOLD)))
                .build(), 1, 1)
            
            .addButton(MenuButton.builder(Material.YELLOW_WOOL)
                .name("&eCol 3 - Yellow")
                .secondary("&7Column 3 content")
                .onClick(p -> p.sendMessage(Component.text("&eClicked Column 3 Yellow!", NamedTextColor.YELLOW)))
                .build(), 1, 2)
            
            .addButton(MenuButton.builder(Material.LIME_WOOL)
                .name("&aCol 4 - Lime")
                .secondary("&7Column 4 content")
                .onClick(p -> p.sendMessage(Component.text("&aClicked Column 4 Lime!", NamedTextColor.GREEN)))
                .build(), 1, 3)
            
            .addButton(MenuButton.builder(Material.GREEN_WOOL)
                .name("&2Col 5 - Green")
                .secondary("&7Column 5 content")
                .onClick(p -> p.sendMessage(Component.text("&2Clicked Column 5 Green!", NamedTextColor.DARK_GREEN)))
                .build(), 1, 4)
            
            .addButton(MenuButton.builder(Material.CYAN_WOOL)
                .name("&3Col 6 - Cyan")
                .secondary("&7Column 6 content")
                .onClick(p -> p.sendMessage(Component.text("&3Clicked Column 6 Cyan!", NamedTextColor.DARK_AQUA)))
                .build(), 1, 5)
            
            .addButton(MenuButton.builder(Material.LIGHT_BLUE_WOOL)
                .name("&bCol 7 - Light Blue")
                .secondary("&7Column 7 content")
                .onClick(p -> p.sendMessage(Component.text("&bClicked Column 7 Light Blue!", NamedTextColor.AQUA)))
                .build(), 1, 6)
            
            .addButton(MenuButton.builder(Material.BLUE_WOOL)
                .name("&9Col 8 - Blue")
                .secondary("&7Column 8 content")
                .onClick(p -> p.sendMessage(Component.text("&9Clicked Column 8 Blue!", NamedTextColor.BLUE)))
                .build(), 1, 7)
            
            .addButton(MenuButton.builder(Material.PURPLE_WOOL)
                .name("&5Col 9 - Purple")
                .secondary("&7Column 9 content (last visible)")
                .onClick(p -> p.sendMessage(Component.text("&5Clicked Column 9 Purple!", NamedTextColor.DARK_PURPLE)))
                .build(), 1, 8)
            
            // Columns 10, 11, 12 (extend beyond viewport - require horizontal scrolling)
            .addButton(MenuButton.builder(Material.MAGENTA_WOOL)
                .name("&dCol 10 - Magenta (Hidden)")
                .secondary("&7Column 10 - scroll right to see")
                .onClick(p -> p.sendMessage(Component.text("&dClicked Column 10 Magenta! (Was hidden)", NamedTextColor.LIGHT_PURPLE)))
                .build(), 1, 9)
            
            .addButton(MenuButton.builder(Material.PINK_WOOL)
                .name("&dCol 11 - Pink (Hidden)")
                .secondary("&7Column 11 - scroll right to see")
                .onClick(p -> p.sendMessage(Component.text("&dClicked Column 11 Pink! (Was hidden)", NamedTextColor.LIGHT_PURPLE)))
                .build(), 1, 10)
            
            .addButton(MenuButton.builder(Material.WHITE_WOOL)
                .name("&fCol 12 - White (Hidden)")
                .secondary("&7Column 12 - scroll right to see")
                .onClick(p -> p.sendMessage(Component.text("&fClicked Column 12 White! (Was hidden)", NamedTextColor.WHITE)))
                .build(), 1, 11)
            
            // Row 2 - More extended content
            .addButton(MenuButton.builder(Material.DIAMOND_BLOCK)
                .name("&bDiamond Block (Col 10)")
                .secondary("&7Hidden column content")
                .onClick(p -> p.sendMessage(Component.text("&bClicked hidden Diamond Block!", NamedTextColor.AQUA)))
                .build(), 2, 9)
            
            .addButton(MenuButton.builder(Material.EMERALD_BLOCK)
                .name("&aEmerald Block (Col 11)")
                .secondary("&7Hidden column content")
                .onClick(p -> p.sendMessage(Component.text("&aClicked hidden Emerald Block!", NamedTextColor.GREEN)))
                .build(), 2, 10)
            
            .addButton(MenuButton.builder(Material.GOLD_BLOCK)
                .name("&6Gold Block (Col 12)")
                .secondary("&7Hidden column content")
                .onClick(p -> p.sendMessage(Component.text("&6Clicked hidden Gold Block!", NamedTextColor.GOLD)))
                .build(), 2, 11)
            
            .buildAsync(player)
            .thenAccept(menu -> {
                // Register the menu instance so the settings menu can reference it
                ((DefaultMenuService) menuService).registerMenuInstance("oversize-columns-test", menu);
                System.out.println("[MENU DEMO] Oversize columns test menu registered as instance: oversize-columns-test");
            });
    }
    
    /**
     * Formatting test menu - demonstrates dual approach (legacy + Adventure API)
     */
    private void openFormattingTest(Player player) {
        menuService.createMenuBuilder()
            .title("&d✨ Enhanced Formatting Test")
            .rows(6)
            .parentMenu("main-menu")
            .fillEmpty(Material.PURPLE_STAINED_GLASS_PANE)
            
            .addItem(MenuDisplayItem.builder(Material.ENCHANTED_BOOK)
                .name("&d✨ Dual Approach Formatting")
                .secondary("&7Testing enhanced italic fixes")
                .description("&7Both legacy compatibility and Adventure API")
                .build(), 0, 4)
            
            // Test 1: Legacy codes (&6Gold Text)
            .addButton(MenuButton.builder(Material.GOLD_INGOT)
                .name("&6Gold Text")
                .secondary("&7Legacy code: &6Gold Text")
                .description("&7Should display gold, non-italic")
                .description("&7Processed with dual approach")
                .onClick(p -> p.sendMessage(Component.text("✅ Legacy &6 code test successful!", NamedTextColor.GREEN)))
                .build(), 2, 1)
            
            // Test 2: MiniMessage format (<gold>Gold Text</gold>)
            .addButton(MenuButton.builder(Material.GOLDEN_APPLE)
                .name("<gold>Gold MiniMessage</gold>")
                .secondary("&7MiniMessage: <gold>Gold Text</gold>")
                .description("&7Should display gold, non-italic")
                .description("&7Adventure API formatting")
                .onClick(p -> p.sendMessage(Component.text("✅ MiniMessage <gold> test successful!", NamedTextColor.GREEN)))
                .build(), 2, 3)
            
            // Test 3: Reset codes (&r&6Gold Text)
            .addButton(MenuButton.builder(Material.GOLD_BLOCK)
                .name("&r&6Gold with Reset")
                .secondary("&7Manual reset: &r&6Gold Text")
                .description("&7Should display gold, non-italic")
                .description("&7Explicit reset compatibility")
                .onClick(p -> p.sendMessage(Component.text("✅ Manual reset &r test successful!", NamedTextColor.GREEN)))
                .build(), 2, 5)
            
            // Test 4: Mixed formatting (colors + bold)
            .addButton(MenuButton.builder(Material.EMERALD)
                .name("&a&lBold Green")
                .secondary("&7Mixed: &a&lBold Green")
                .description("&7Should display bold green, non-italic")
                .description("&7Adventure decoration handling")
                .onClick(p -> p.sendMessage(Component.text("✅ Mixed &a&l formatting test successful!", NamedTextColor.GREEN)))
                .build(), 2, 7)
            
            // Test 5: Complex MiniMessage
            .addButton(MenuButton.builder(Material.DIAMOND)
                .name("<blue><bold>Blue Bold</bold></blue>")
                .secondary("&7Complex: <blue><bold>Text</bold></blue>")
                .description("&7Should display bold blue, non-italic")
                .description("&7Full MiniMessage support")
                .onClick(p -> p.sendMessage(Component.text("✅ Complex MiniMessage test successful!", NamedTextColor.GREEN)))
                .build(), 4, 2)
            
            // Test 6: No formatting (plain text)
            .addButton(MenuButton.builder(Material.PAPER)
                .name("Plain Text")
                .secondary("&7Plain: Plain Text")
                .description("&7Should display white, non-italic")
                .description("&7Basic text with Adventure fix")
                .onClick(p -> p.sendMessage(Component.text("✅ Plain text test successful!", NamedTextColor.GREEN)))
                .build(), 4, 4)
            
            // Test 7: Already has reset prefix
            .addButton(MenuButton.builder(Material.BOOK)
                .name("<reset><yellow>Yellow Reset</reset>")
                .secondary("&7Pre-reset: <reset><yellow>Text</reset>")
                .description("&7Should display yellow, non-italic")
                .description("&7Handles existing reset tags")
                .onClick(p -> p.sendMessage(Component.text("✅ Pre-reset test successful!", NamedTextColor.GREEN)))
                .build(), 4, 6)
            
            .buildAsync(player)
            .thenAccept(menu -> {
                ((DefaultMenuService) menuService).registerMenuInstance("formatting-test", menu);
                System.out.println("[MENU DEMO] Formatting test menu registered as instance: formatting-test");
            });
    }
}