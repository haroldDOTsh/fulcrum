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
            .build();
    }
    
    private int showMainMenu(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            ctx.getSource().getSender().sendMessage(Component.text("This command can only be used by players!", NamedTextColor.RED));
            return 0;
        }
        
        menuService.createMenuBuilder()
            .title("Menu API Demo")
            .viewPort(4)
            .rows(4)
            .fillEmpty(Material.BLACK_STAINED_GLASS_PANE)
            
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
            
            // Close button
            .addButton(MenuButton.builder(Material.BARRIER)
                .name("<red>Close")
                .onClick(p -> p.closeInventory())
                .build(), 2, 4)
                
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
    
    /**
     * CustomMenuBuilder demonstration
     */
    private void showCustomMenu(Player player) {
        menuService.createMenuBuilder()
            .title("Custom Menu Demo")
            .viewPort(5)
            .rows(8)
            .columns(9)
            
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
            
            // Navigation back
            .addButton(MenuButton.builder(Material.ARROW)
                .name("<yellow>Back to Main")
                .onClick(p -> p.performCommand("menudemo"))
                .build(), 7, 0)
            
            // Close button
            .addButton(MenuButton.builder(Material.BARRIER)
                .name("<red>Close")
                .onClick(p -> p.closeInventory())
                .build(), 7, 8)
            
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
            
            // Add navigation buttons
            .addNavigationButtons()
            
            // Add page indicator
            .addPageIndicator(49)
            
            // Control buttons
            .addButton(MenuButton.builder(Material.ARROW)
                .name("<yellow>Back to Main Menu")
                .onClick(p -> p.performCommand("menudemo"))
                .build(), 45)
            
            .addButton(MenuButton.builder(Material.BOOK)
                .name("<gold>List Menu Info")
                .lore("<white>• " + items.size() + " items with pagination")
                .lore("<white>• Automatic navigation")
                .lore("<white>• Item transformation")
                .lore("<white>• Border decoration")
                .skipClickPrompt()
                .build(), 53)
                
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