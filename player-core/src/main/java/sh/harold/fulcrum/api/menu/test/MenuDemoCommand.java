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
import sh.harold.fulcrum.api.menu.component.MenuButton;
import sh.harold.fulcrum.api.menu.component.MenuDisplayItem;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static io.papermc.paper.command.brigadier.Commands.literal;

/**
 * Simple demonstration command for the Menu API system using modern Paper commands.
 * Shows both CustomMenu and ListMenu examples.
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
            .then(literal("custom")
                .executes(this::showCustomMenuExample))
            .then(literal("list")
                .executes(this::showListMenuExample))
            .then(literal("navigation")
                .executes(this::showNavigationRefinementDemo))
            .then(literal("oversized")
                .executes(this::showOversizedMenuDemo))
            .build();
    }
    
    private int showMainMenu(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            ctx.getSource().getSender().sendMessage(Component.text("This command can only be used by players!", NamedTextColor.RED));
            return 0;
        }
        
        menuService.createMenuBuilder()
            .title("Menu API Demo")
            .fillEmpty(Material.BLACK_STAINED_GLASS_PANE)
            .rows(4)
            
            // Custom Menu Demo button
            .addButton(MenuButton.builder(Material.CRAFTING_TABLE)
                .name("<aqua>Custom Menu Demo")
                .lore("<gray>Demonstrates CustomMenuBuilder:")
                .lore("<white>• Custom layouts & anchoring")
                .lore("<white>• Button interactions")
                .lore("<white>• Viewport system")
                .lore("")
                .lore("<yellow>Click to test!")
                .onClick(this::showCustomMenu)
                .build(), 1, 2)

            // List Menu Demo button
            .addButton(MenuButton.builder(Material.BOOK)
                .name("<aqua>List Menu Demo")
                .lore("<gray>Demonstrates ListMenuBuilder:")
                .lore("<white>• Pagination system")
                .lore("<white>• Item transformation")
                .lore("<white>• Navigation controls")
                .lore("")
                .lore("<yellow>Click to test!")
                .onClick(this::showListMenu)
                .build(), 1, 6)

            .buildAsync(player);
        
        return Command.SINGLE_SUCCESS;
    }
    
    private int showCustomMenuExample(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            ctx.getSource().getSender().sendMessage(Component.text("This command can only be used by players!", NamedTextColor.RED));
            return 0;
        }
        
        showCustomMenu(player);
        return Command.SINGLE_SUCCESS;
    }
    
    private int showListMenuExample(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            ctx.getSource().getSender().sendMessage(Component.text("This command can only be used by players!", NamedTextColor.RED));
            return 0;
        }
        
        showListMenu(player);
        return Command.SINGLE_SUCCESS;
    }
    
    private int showNavigationRefinementDemo(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            ctx.getSource().getSender().sendMessage(Component.text("This command can only be used by players!", NamedTextColor.RED));
            return 0;
        }
        
        showNavigationRefinements(player);
        return Command.SINGLE_SUCCESS;
    }
    
    private int showOversizedMenuDemo(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            ctx.getSource().getSender().sendMessage(Component.text("This command can only be used by players!", NamedTextColor.RED));
            return 0;
        }
        
        showOversizedMenus(player);
        return Command.SINGLE_SUCCESS;
    }
    
    /**
     * CustomMenuBuilder demonstration
     */
    private void showCustomMenu(Player player) {
        menuService.createMenuBuilder()
            .title("Custom Menu Demo")
            .fillEmpty(Material.GRAY_STAINED_GLASS_PANE)
            .viewPort(6)
            .rows(8)
            
            // Header info item
            .addItem(MenuDisplayItem.builder(Material.DIAMOND)
                .name("<gold>Custom Menu Features")
                .lore("<gray>This demonstrates:")
                .lore("<white>• Viewport system")
                .lore("<white>• Virtual coordinates")
                .lore("<white>• Button interactions")
                .lore("<white>• Cooldown system")
                .build(), 0, 4)
            
            // Action buttons in a row
            .addButton(MenuButton.builder(Material.EMERALD)
                .name("<green>Success Action")
                .lore("<gray>Click for a success message")
                .onClick(p -> p.sendMessage(Component.text("✓ Success! This button worked perfectly.", NamedTextColor.GREEN)))
                .build(), 2, 1)
            
            .addButton(MenuButton.builder(Material.GOLD_INGOT)
                .name("<yellow>Info Action")
                .lore("<gray>Click for information")
                .onClick(p -> p.sendMessage(Component.text("ℹ Info: You are viewing the custom menu demo.", NamedTextColor.YELLOW)))
                .build(), 2, 3)
            
            .addButton(MenuButton.builder(Material.REDSTONE)
                .name("<red>Warning Action")
                .lore("<gray>Click for a warning message")
                .onClick(p -> p.sendMessage(Component.text("⚠ Warning: This is just a demo!", NamedTextColor.RED)))
                .build(), 2, 5)
            
            .addButton(MenuButton.builder(Material.CLOCK)
                .name("<light_purple>Cooldown Button")
                .lore("<gray>Has a 5-second cooldown")
                .lore("<gray>Try clicking it multiple times!")
                .cooldown(Duration.ofSeconds(5))
                .onClick(p -> p.sendMessage(Component.text("⏰ Cooldown activated! Wait 5 seconds.", NamedTextColor.LIGHT_PURPLE)))
                .build(), 2, 7)
            
            // Scattered demo items throughout the virtual space
            .addItem(MenuDisplayItem.builder(Material.APPLE)
                .name("<red>Virtual Item 1")
                .lore("<gray>Located at (4, 0)")
                .build(), 4, 0)
            
            .addItem(MenuDisplayItem.builder(Material.GOLDEN_APPLE)
                .name("<yellow>Virtual Item 2") 
                .lore("<gray>Located at (4, 8)")
                .build(), 4, 8)
            
            .addItem(MenuDisplayItem.builder(Material.ENCHANTED_GOLDEN_APPLE)
                .name("<gold>Virtual Item 3")
                .lore("<gray>Located at (6, 4)")
                .build(), 6, 4)
                        
            
            // Add scroll buttons for navigation
            .addScrollButtons()
                
            .buildAsync(player);
    }
    
    /**
     * ListMenuBuilder demonstration
     */
    private void showListMenu(Player player) {
        // Generate sample data
        List<DemoItem> items = generateDemoItems();
        
        menuService.createListMenu()
            .title("List Menu Demo")
            .rows(6)
            .addBorder(Material.BLACK_STAINED_GLASS_PANE, " ")
            
            // Transform each item to a clickable button
            .addItems(items, item -> MenuButton.builder(item.material())
                .name("<" + item.color().toString().toLowerCase() + ">" + item.name())
                .lore("<gray>Category: " + item.category())
                .lore("<gray>Rarity: " + item.rarity())
                .lore("<gray>Value: " + item.value())
                .lore("")
                .lore("<yellow>Left-click: Select")
                .lore("<yellow>Right-click: Info")
                .onClick(p -> {
                    p.sendMessage(Component.text("Selected: " + item.name(), NamedTextColor.GREEN));
                })
                .build())


                
            .buildAsync(player);
    }
    
    /**
     * Generate sample data for the list menu
     */
    private List<DemoItem> generateDemoItems() {
        List<DemoItem> items = new ArrayList<>();
        String[] categories = {"Weapon", "Tool", "Material", "Food", "Armor"};
        String[] rarities = {"Common", "Uncommon", "Rare", "Epic", "Legendary"};
        NamedTextColor[] colors = {
            NamedTextColor.WHITE, NamedTextColor.GREEN, 
            NamedTextColor.BLUE, NamedTextColor.DARK_PURPLE, 
            NamedTextColor.GOLD
        };
        Material[] materials = {
            Material.WOODEN_SWORD, Material.IRON_PICKAXE, Material.GOLD_INGOT,
            Material.BREAD, Material.IRON_CHESTPLATE, Material.DIAMOND,
            Material.EMERALD, Material.APPLE, Material.SHIELD, Material.BOW,
            Material.ARROW, Material.STONE, Material.COAL, Material.REDSTONE,
            Material.IRON_SWORD, Material.DIAMOND_PICKAXE, Material.COOKED_BEEF,
            Material.GOLDEN_CHESTPLATE, Material.CROSSBOW, Material.SPECTRAL_ARROW
        };
        
        for (int i = 0; i < 47; i++) { // Generate enough items to show pagination
            String category = categories[i % categories.length];
            String rarity = rarities[i % rarities.length];
            
            items.add(new DemoItem(
                category + " Item #" + (i + 1),
                category,
                rarity,
                (int)(Math.random() * 100) + 1,
                "A sample " + rarity.toLowerCase() + " " + category.toLowerCase() + " item for testing.",
                colors[i % colors.length],
                materials[i % materials.length]
            ));
        }
        
        return items;
    }
    
    /**
     * Demonstrates navigation refinements - smart dimension detection and button hiding
     */
    private void showNavigationRefinements(Player player) {
        menuService.createMenuBuilder()
            .title("Navigation Refinements Demo")
            .fillEmpty(Material.GRAY_STAINED_GLASS_PANE)
            .viewPort(6)
            .rows(6) // Only vertically oversized, not horizontally
            .columns(9)
            
            // Add items to demonstrate the virtual grid
            .addItem(MenuDisplayItem.builder(Material.EMERALD)
                .name("<green>Virtual Item 1")
                .lore("<gray>This menu is only vertically oversized")
                .lore("<gray>You should only see up/down navigation")
                .lore("<gray>No left/right navigation buttons")
                .build(), 0, 4)
                
            .addItem(MenuDisplayItem.builder(Material.DIAMOND)
                .name("<aqua>Virtual Item 2")
                .lore("<gray>Test scrolling to bottom")
                .lore("<gray>Navigation buttons should hide at limits")
                .build(), 5, 4)
                
            .addItem(MenuDisplayItem.builder(Material.GOLD_INGOT)
                .name("<yellow>Scroll Test")
                .lore("<gray>When you reach scroll limits:")
                .lore("<white>• Buttons disappear completely")
                .lore("<white>• No gray stained glass panes")
                .lore("<white>• Bottom row has black glass")
                .build(), 2, 4)
            
            // This will trigger automatic scroll buttons for vertical-only navigation
            .addScrollButtons()
                
            .buildAsync(player);
    }
    
    /**
     * Demonstrates oversized menu features including horizontally oversized menus
     */
    private void showOversizedMenus(Player player) {
        menuService.createMenuBuilder()
            .title("Oversized Menu Demo")
            .fillEmpty(Material.GRAY_STAINED_GLASS_PANE)
            .viewPort(6)
            .rows(8) // Vertically oversized
            .columns(15) // Horizontally oversized
            
            // Add items across the large virtual space
            .addItem(MenuDisplayItem.builder(Material.COMPASS)
                .name("<gold>Navigation Guide")
                .lore("<gray>This menu is oversized in BOTH dimensions")
                .lore("<white>• Rows: 8 (viewport: 6)")
                .lore("<white>• Columns: 15 (viewport: 9)")
                .lore("<gray>You should see both up/down AND left/right navigation")
                .build(), 0, 0)
                
            .addItem(MenuDisplayItem.builder(Material.MAP)
                .name("<blue>Far Top Right")
                .lore("<gray>Located at (0, 14)")
                .lore("<gray>Use horizontal navigation to reach")
                .build(), 0, 14)
                
            .addItem(MenuDisplayItem.builder(Material.ENDER_PEARL)
                .name("<light_purple>Bottom Left Corner")
                .lore("<gray>Located at (7, 0)")
                .lore("<gray>Use vertical navigation to reach")
                .build(), 7, 0)
                
            .addItem(MenuDisplayItem.builder(Material.NETHER_STAR)
                .name("<white>Far Bottom Right")
                .lore("<gray>Located at (7, 14)")
                .lore("<gray>Ultimate navigation test!")
                .lore("<gray>Requires both vertical and horizontal scrolling")
                .build(), 7, 14)
                
            .addItem(MenuDisplayItem.builder(Material.BEACON)
                .name("<yellow>Center Marker")
                .lore("<gray>Located at (4, 7)")
                .lore("<gray>Center of the virtual space")
                .build(), 4, 7)
            
            // This will trigger automatic scroll buttons for both dimensions
            .addScrollButtons()
                
            .buildAsync(player);
    }
    
    /**
     * Demo item record for list menu
     */
    public record DemoItem(
        String name,
        String category,
        String rarity,
        int value,
        String description,
        NamedTextColor color,
        Material material
    ) {}
}