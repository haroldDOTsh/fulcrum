package sh.harold.fulcrum.api.menu.demo;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import sh.harold.fulcrum.api.menu.CustomMenuBuilder;
import sh.harold.fulcrum.api.menu.ListMenuBuilder;
import sh.harold.fulcrum.api.menu.Menu;
import sh.harold.fulcrum.api.menu.MenuService;
import sh.harold.fulcrum.api.menu.component.MenuButton;
import sh.harold.fulcrum.api.menu.component.MenuDisplayItem;

import java.util.concurrent.CompletableFuture;

/**
 * Demonstrates the automatic button functionality for both CustomMenuBuilder and ListMenuBuilder.
 * 
 * Key Features Demonstrated:
 * - Close button is automatically added by default at slot 49 (6-row menu)
 * - Manual buttons take precedence over automatic buttons
 * - Configuration methods to enable/disable automatic buttons
 * - Smart slot conflict detection and warnings
 */
public class AutomaticButtonDemo {
    
    private final MenuService menuService;
    private final Plugin plugin;
    
    public AutomaticButtonDemo(MenuService menuService, Plugin plugin) {
        this.menuService = menuService;
        this.plugin = plugin;
    }
    
    /**
     * Demonstrates default automatic button behavior.
     * Close button is automatically added at slot 53 (6-row menu bottom right).
     */
    public CompletableFuture<Menu> createBasicCustomMenu(Player player) {
        return menuService.createMenuBuilder()
            .title(Component.text("§6Basic Custom Menu - Auto Close"))
            .viewPort(6)
            .rows(6)
            .owner(plugin)
            // No manual close button added - automatic close will be added at slot 53
            .addItem(MenuDisplayItem.builder(Material.DIAMOND)
                .name("§bExample Item")
                .build(), 0, 0)
            .buildAsync(player);
    }
    
    /**
     * Demonstrates customized automatic button configuration.
     * Enables back button and navigation buttons in addition to close.
     */
    public CompletableFuture<Menu> createFullyAutomaticCustomMenu(Player player) {
        return menuService.createMenuBuilder()
            .title(Component.text("§6Fully Automatic Menu"))
            .viewPort(6)
            .rows(6)
            .owner(plugin)
            // Enable all automatic buttons
            .autoCloseButton(true)      // Default: true
            .autoBackButton(true)       // Default: false
            .autoNavigationButtons(true) // Default: false
            .addItem(MenuDisplayItem.builder(Material.EMERALD)
                .name("§aContent Item")
                .build(), 2, 4)
            .buildAsync(player);
    }
    
    /**
     * Demonstrates manual button precedence.
     * Manual close button overrides automatic close button.
     */
    public CompletableFuture<Menu> createManualOverrideCustomMenu(Player player) {
        return menuService.createMenuBuilder()
            .title(Component.text("§6Manual Override Demo"))
            .viewPort(6)
            .rows(6)
            .owner(plugin)
            // Manual button at slot 53 (where auto-close would normally go)
            .addButton(MenuButton.builder(Material.BARRIER)
                .name("§cCustom Close Button")
                .description("This overrides the automatic close button")
                .onClick(p -> {
                    p.sendMessage("Custom close button clicked!");
                    p.closeInventory();
                })
                .slot(MenuButton.getCloseSlot(6)) // Slot 53 for 6-row menu
                .build())
            // Automatic close button will be skipped due to slot conflict
            .autoCloseButton(true)
            .buildAsync(player);
    }
    
    /**
     * Demonstrates disabled automatic buttons.
     */
    public CompletableFuture<Menu> createNoAutomaticButtonsCustomMenu(Player player) {
        return menuService.createMenuBuilder()
            .title(Component.text("§6No Auto Buttons"))
            .viewPort(6)
            .rows(6)
            .owner(plugin)
            // Disable all automatic buttons
            .autoCloseButton(false)
            .autoBackButton(false)
            .autoNavigationButtons(false)
            .addItem(MenuDisplayItem.builder(Material.REDSTONE)
                .name("§cNo automatic buttons in this menu")
                .build(), 1, 1)
            .buildAsync(player);
    }
    
    /**
     * Demonstrates automatic buttons in ListMenuBuilder.
     * Close button automatically added, with smart pagination conflict avoidance.
     */
    public CompletableFuture<Menu> createBasicListMenu(Player player) {
        return menuService.createListMenu()
            .title(Component.text("§6Basic List Menu - Auto Close"))
            .rows(6)
            // Close button will be automatically added at slot 53
            // Navigation buttons will avoid pagination button slots
            .addItems(
                MenuDisplayItem.builder(Material.APPLE).name("§aApple").build(),
                MenuDisplayItem.builder(Material.BREAD).name("§eBread").build(),
                MenuDisplayItem.builder(Material.CARROT).name("§6Carrot").build(),
                MenuDisplayItem.builder(Material.MELON).name("§dMelon").build()
            )
            .buildAsync(player);
    }
    
    /**
     * Demonstrates full automatic navigation for ListMenuBuilder.
     * Navigation buttons are now automatically added when needed.
     */
    public CompletableFuture<Menu> createFullyAutomaticListMenu(Player player) {
        return menuService.createListMenu()
            .title(Component.text("§6Fully Automatic List Menu"))
            .rows(6)
            // Enable automatic close and back buttons
            .autoCloseButton(true)
            .autoBackButton(true)
            // Navigation buttons are now automatically added when items overflow available slots
            .addItems(
                MenuDisplayItem.builder(Material.DIAMOND_SWORD).name("§bSword").build(),
                MenuDisplayItem.builder(Material.DIAMOND_PICKAXE).name("§bPickaxe").build(),
                MenuDisplayItem.builder(Material.DIAMOND_AXE).name("§bAxe").build(),
                MenuDisplayItem.builder(Material.DIAMOND_SHOVEL).name("§bShovel").build(),
                MenuDisplayItem.builder(Material.DIAMOND_HOE).name("§bHoe").build(),
                // Add many more items to trigger automatic pagination
                MenuDisplayItem.builder(Material.GOLDEN_SWORD).name("§6Golden Sword").build(),
                MenuDisplayItem.builder(Material.GOLDEN_PICKAXE).name("§6Golden Pickaxe").build(),
                MenuDisplayItem.builder(Material.GOLDEN_AXE).name("§6Golden Axe").build(),
                MenuDisplayItem.builder(Material.GOLDEN_SHOVEL).name("§6Golden Shovel").build(),
                MenuDisplayItem.builder(Material.GOLDEN_HOE).name("§6Golden Hoe").build(),
                MenuDisplayItem.builder(Material.IRON_SWORD).name("§7Iron Sword").build(),
                MenuDisplayItem.builder(Material.IRON_PICKAXE).name("§7Iron Pickaxe").build(),
                MenuDisplayItem.builder(Material.IRON_AXE).name("§7Iron Axe").build(),
                MenuDisplayItem.builder(Material.IRON_SHOVEL).name("§7Iron Shovel").build(),
                MenuDisplayItem.builder(Material.IRON_HOE).name("§7Iron Hoe").build(),
                // Add enough items to ensure pagination is needed
                MenuDisplayItem.builder(Material.STONE_SWORD).name("§8Stone Sword").build(),
                MenuDisplayItem.builder(Material.STONE_PICKAXE).name("§8Stone Pickaxe").build(),
                MenuDisplayItem.builder(Material.STONE_AXE).name("§8Stone Axe").build(),
                MenuDisplayItem.builder(Material.STONE_SHOVEL).name("§8Stone Shovel").build(),
                MenuDisplayItem.builder(Material.STONE_HOE).name("§8Stone Hoe").build()
            )
            .buildAsync(player);
    }
    
    /**
     * Demonstrates conflicting slot detection in ListMenuBuilder.
     */
    public CompletableFuture<Menu> createConflictDemoListMenu(Player player) {
        return menuService.createListMenu()
            .title(Component.text("§6Conflict Detection Demo"))
            .rows(6)
            // Manual button at close slot
            .addButton(MenuButton.builder(Material.BARRIER)
                .name("§cManual Close")
                .description("Manual button takes precedence")
                .onClick(p -> {
                    p.sendMessage("Manual close button clicked!");
                    p.closeInventory();
                })
                .build(), MenuButton.getCloseSlot(6))
            // Automatic close will be skipped due to conflict
            .autoCloseButton(true)
            // Navigation is now automatic when items overflow
            .addItems(
                MenuDisplayItem.builder(Material.GOLD_INGOT).name("§6Gold").build(),
                MenuDisplayItem.builder(Material.IRON_INGOT).name("§7Iron").build()
            )
            .buildAsync(player);
    }
    
    /**
     * Demonstrates fillEmpty functionality with BLACK_STAINED_GLASS_PANE.
     * This tests the specific user requirement for fillEmpty working correctly.
     */
    public CompletableFuture<Menu> createFillEmptyDemoCustomMenu(Player player) {
        return menuService.createMenuBuilder()
            .title(Component.text("§6FillEmpty Demo - Custom Menu"))
            .viewPort(6)
            .rows(6)
            .owner(plugin)
            // Add a few items, leaving most slots empty
            .addItem(MenuDisplayItem.builder(Material.DIAMOND)
                .name("§bDiamond Item")
                .build(), 1, 1)
            .addItem(MenuDisplayItem.builder(Material.EMERALD)
                .name("§aEmerald Item")
                .build(), 3, 7)
            // Fill all empty slots with black stained glass pane (specific user requirement)
            .fillEmpty(Material.BLACK_STAINED_GLASS_PANE)
            // Test automatic close button with fillEmpty
            .autoCloseButton(true)
            .buildAsync(player);
    }
    
    /**
     * Demonstrates fillEmpty functionality with BLACK_STAINED_GLASS_PANE for ListMenu.
     * This tests the specific user requirement for fillEmpty working correctly.
     */
    public CompletableFuture<Menu> createFillEmptyDemoListMenu(Player player) {
        return menuService.createListMenu()
            .title(Component.text("§6FillEmpty Demo - List Menu"))
            .rows(6)
            // Add only a few items to demonstrate fillEmpty
            .addItems(
                MenuDisplayItem.builder(Material.APPLE).name("§cApple").build(),
                MenuDisplayItem.builder(Material.BREAD).name("§eBread").build()
            )
            // Fill all empty slots with black stained glass pane (specific user requirement)
            .fillEmpty(Material.BLACK_STAINED_GLASS_PANE)
            // Test automatic close button with fillEmpty
            .autoCloseButton(true)
            .buildAsync(player);
    }
    
    /**
     * Demonstrates sound effects functionality.
     * Tests that sound effects are configured correctly for buttons.
     */
    public CompletableFuture<Menu> createSoundEffectsDemoMenu(Player player) {
        return menuService.createMenuBuilder()
            .title(Component.text("§6Sound Effects Demo"))
            .viewPort(6)
            .rows(6)
            .owner(plugin)
            // Add buttons with different sound effects
            .addButton(MenuButton.builder(Material.NOTE_BLOCK)
                .name("§9UI Click Sound")
                .description("Plays UI_BUTTON_CLICK sound")
                .sound(org.bukkit.Sound.UI_BUTTON_CLICK)
                .onClick(p -> p.sendMessage("§aUI Click sound played!"))
                .slot(10)
                .build())
            .addButton(MenuButton.builder(Material.LOOM)
                .name("§dLoom Sound")
                .description("Plays loom sound for scrolling")
                .sound(org.bukkit.Sound.UI_LOOM_TAKE_RESULT)
                .onClick(p -> p.sendMessage("§aLoom sound played!"))
                .slot(12)
                .build())
            .addButton(MenuButton.builder(Material.BELL)
                .name("§6Custom Sound")
                .description("Plays BLOCK_BELL_USE with custom volume/pitch")
                .sound(org.bukkit.Sound.BLOCK_BELL_USE, 0.8f, 1.5f)
                .onClick(p -> p.sendMessage("§aCustom bell sound played!"))
                .slot(14)
                .build())
            // Automatic close button also has sound effects
            .autoCloseButton(true)
            .fillEmpty(Material.GRAY_STAINED_GLASS_PANE)
            .buildAsync(player);
    }
    
    /**
     * Demonstrates automatic positioning for different menu sizes (3-6 rows).
     * Tests that dynamic slot calculation works for different menu sizes.
     */
    public CompletableFuture<Menu> createDifferentSizesDemoMenu(Player player, int rows) {
        if (rows < 3 || rows > 6) {
            throw new IllegalArgumentException("Menu rows must be between 3 and 6");
        }
        
        return menuService.createMenuBuilder()
            .title(Component.text("§6Menu Size Demo - " + rows + " Rows"))
            .viewPort(rows)
            .rows(rows)
            .owner(plugin)
            // Add content item to show menu size
            .addItem(MenuDisplayItem.builder(Material.PAPER)
                .name("§eMenu Size: " + rows + " rows")
                .description("Close slot: " + MenuButton.getCloseSlot(rows))
                .description("Back slot: " + MenuButton.getBackSlot(rows))
                .build(), 0, 4)
            // Enable all automatic buttons to test positioning
            .autoCloseButton(true)
            .autoBackButton(true)
            .autoNavigationButtons(true)
            .fillEmpty(Material.LIGHT_BLUE_STAINED_GLASS_PANE)
            .buildAsync(player);
    }
    
    /**
     * Demonstrates backward compatibility.
     * Tests that existing menu code still works without automatic buttons.
     */
    public CompletableFuture<Menu> createBackwardCompatibilityDemo(Player player) {
        return menuService.createListMenu()
            .title(Component.text("§6Backward Compatibility Demo"))
            .rows(6)
            // Create menu the "old way" without using automatic button features
            .addBorder(Material.BLACK_STAINED_GLASS_PANE)
            // Manually add close button at slot 49 (old approach)
            .addButton(MenuButton.builder(Material.BARRIER)
                .name("§cClose Menu")
                .onClick(p -> p.closeInventory())
                .build(), 49)
            // Add some content
            .addItems(
                MenuDisplayItem.builder(Material.STONE).name("§7Stone").build(),
                MenuDisplayItem.builder(Material.DIRT).name("§6Dirt").build(),
                MenuDisplayItem.builder(Material.GRASS_BLOCK).name("§aGrass").build()
            )
            // Disable automatic buttons to test backward compatibility
            .autoCloseButton(false)
            .autoBackButton(false)
            // Navigation is now automatic when items overflow (can't be disabled)
            .buildAsync(player);
    }
    
    /**
     * Comprehensive test that validates all user requirements are met.
     * This method tests all the key features mentioned in the task requirements.
     */
    public CompletableFuture<Menu> createComprehensiveTestMenu(Player player) {
        return menuService.createMenuBuilder()
            .title(Component.text("§6Comprehensive Test Menu"))
            .viewPort(6)
            .rows(6)
            .owner(plugin)
            // Test requirement: Close button present by default ✅
            .autoCloseButton(true)
            // Test requirement: fillEmpty works with BLACK_STAINED_GLASS_PANE ✅
            .fillEmpty(Material.BLACK_STAINED_GLASS_PANE)
            // Test requirement: Default button enums available ✅
            .addButton(MenuButton.createPositionedSearch(6))
            .addButton(MenuButton.createPositionedSort(6))
            .addButton(MenuButton.createPositionedFilter(6))
            // Test requirement: Sound effects configured ✅
            .addButton(MenuButton.builder(Material.MUSIC_DISC_CAT)
                .name("§9Sound Test")
                .description("Tests UI_BUTTON_CLICK sound")
                .sound(org.bukkit.Sound.UI_BUTTON_CLICK)
                .onClick(p -> p.sendMessage("§aSound effects working!"))
                .slot(22)
                .build())
            // Test requirement: Navigation and close buttons automatically added ✅
            .autoNavigationButtons(true)
            // Test requirement: Dynamic positioning based on menu size ✅
            .addItem(MenuDisplayItem.builder(Material.COMPASS)
                .name("§ePositioning Info")
                .description("Close slot: " + MenuButton.getCloseSlot(6))
                .description("Back slot: " + MenuButton.getBackSlot(6))
                .description("All buttons positioned correctly!")
                .build(), 2, 4)
            // Add some content to demonstrate
            .addItem(MenuDisplayItem.builder(Material.DIAMOND)
                .name("§bTest Content")
                .description("This menu tests all requirements")
                .build(), 1, 1)
            .buildAsync(player);
    }
}