package sh.harold.fulcrum.api.menu.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.menu.MenuService;
import sh.harold.fulcrum.api.menu.TabbedMenuBuilder;
import sh.harold.fulcrum.api.menu.component.MenuButton;
import sh.harold.fulcrum.api.menu.component.MenuDisplayItem;
import sh.harold.fulcrum.api.menu.component.MenuItem;
import sh.harold.fulcrum.api.rank.RankUtils;
import sh.harold.fulcrum.message.Message;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static io.papermc.paper.command.brigadier.Commands.literal;

/**
 * Showcase command that spawns various menu presets for verification.
 */
public final class MenuDebugCommand {

    private static final Material[] DEMO_MATERIALS = {
            Material.DIAMOND_SWORD, Material.NETHERITE_PICKAXE, Material.GOLDEN_APPLE,
            Material.FISHING_ROD, Material.COMPASS, Material.TNT, Material.ELYTRA,
            Material.TRIDENT, Material.SHULKER_BOX, Material.SPYGLASS, Material.LODESTONE,
            Material.BEACON, Material.HONEY_BLOCK, Material.SLIME_BLOCK, Material.AMETHYST_SHARD
    };

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withLocale(Locale.US)
            .withZone(ZoneId.systemDefault());

    private final Supplier<MenuService> menuServiceSupplier;

    public MenuDebugCommand(Supplier<MenuService> menuServiceSupplier) {
        this.menuServiceSupplier = menuServiceSupplier;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return literal("menudebug")
                .requires(stack -> RankUtils.isStaff(stack.getSender()))
                .then(literal("TABBED").executes(context ->
                        execute(context.getSource(), this::openTabbedMenu)))
                .then(literal("DYNAMIC").executes(context ->
                        execute(context.getSource(), this::openDynamicMenu)))
                .then(literal("BIG").executes(context ->
                        execute(context.getSource(), this::openBigMenu)))
                .build();
    }

    private int execute(CommandSourceStack source,
                        BiConsumer<Player, MenuService> opener) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can run /menudebug.", NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        MenuService menuService = menuServiceSupplier.get();
        if (menuService == null) {
            Message.error("menu.debug.unavailable")
                    .skipTranslation()
                    .send(player);
            return Command.SINGLE_SUCCESS;
        }
        opener.accept(player, menuService);
        return Command.SINGLE_SUCCESS;
    }

    private void openTabbedMenu(Player player, MenuService menuService) {
        menuService.createTabbedMenu()
                .title(Component.text("Fulcrum Tabs", NamedTextColor.AQUA))
                .contentRows(3)
                .divider(Material.CYAN_STAINED_GLASS_PANE)
                .defaultTab("cosmetics")
                .tab(tab -> tab
                        .id("cosmetics")
                        .name(Component.text("Cosmetics", NamedTextColor.LIGHT_PURPLE))
                        .icon(Material.ENDER_CHEST)
                        .lore(Component.text("Preview pets, mounts, and sparkles."))
                        .divider(Material.PURPLE_STAINED_GLASS_PANE)
                        .items(createCosmeticAnchors())
                        .items(() -> createCosmeticItems(player)))
                .tab(tab -> tab
                        .id("friends")
                        .name(Component.text("Friends", NamedTextColor.GOLD))
                        .icon(Material.PLAYER_HEAD)
                        .divider(Material.ORANGE_STAINED_GLASS_PANE)
                        .items(createFriendControls())
                        .items(() -> buildFriendEntries(player)))
                .tab(tab -> tab
                        .id("empty")
                        .name(Component.text("Empty", NamedTextColor.RED))
                        .icon(Material.BARRIER)
                        .emptyMessage(Component.text("Nobody home right now!", NamedTextColor.GRAY)))
                .onTabChange((viewer, oldTab, newTab) ->
                        Message.debug("menu.tabbed.swap", oldTab, newTab)
                                .skipTranslation()
                                .send(viewer))
                .tabScroller(TabbedMenuBuilder.TabScrollerConfig.defaults())
                .buildAsync(player);
    }

    private void openDynamicMenu(Player player, MenuService menuService) {
        menuService.createListMenu()
                .title(Component.text("Live Diagnostics", NamedTextColor.GREEN))
                .rows(6)
                .addBorder(Material.GREEN_STAINED_GLASS_PANE)
                .addButton(MenuButton.builder(Material.BARRIER)
                        .name("&cClose")
                        .slot(MenuButton.getCloseSlot(6))
                        .onClick(Player::closeInventory)
                        .build())
                .addItems(buildDynamicEntries(player))
                .emptyMessage(Component.text("No metrics recorded.", NamedTextColor.GRAY))
                .buildAsync(player);
    }

    private void openBigMenu(Player player, MenuService menuService) {
        var random = new Random();
        var builder = menuService.createMenuBuilder()
                .title(Component.text("Mega Scroll Demo", NamedTextColor.AQUA))
                .viewPort(6)
                .rows(12)
                .columns(12)
                .fillEmpty(Material.BLACK_STAINED_GLASS_PANE)
                .addScrollButtons()
                .addButton(MenuButton.builder(Material.BARRIER)
                        .name("&cClose")
                        .slot(MenuButton.getCloseSlot(6))
                        .onClick(Player::closeInventory)
                        .build());

        for (int row = 0; row < 12; row++) {
            for (int col = 0; col < 12; col++) {
                Material icon = DEMO_MATERIALS[random.nextInt(DEMO_MATERIALS.length)];
                builder.addItem(MenuDisplayItem.builder(icon)
                        .name(Component.text("Slot " + row + "," + col, NamedTextColor.YELLOW))
                        .description("Scroll to explore the full virtual grid.")
                        .build(), row, col);
            }
        }

        builder.buildAsync(player);
    }

    private Collection<MenuItem> createCosmeticAnchors() {
        List<MenuItem> anchors = new ArrayList<>();
        anchors.add(MenuButton.builder(Material.HOPPER)
                .name("&eSort A-Z")
                .slot(18)
                .onClick(player -> Message.info("menu.debug.sort").skipTranslation().send(player))
                .build());
        anchors.add(MenuButton.builder(Material.NAME_TAG)
                .name("&bFilter Unlocks")
                .slot(26)
                .onClick(player -> Message.info("menu.debug.filter").skipTranslation().send(player))
                .build());
        return anchors;
    }

    private Collection<MenuItem> createFriendControls() {
        List<MenuItem> anchors = new ArrayList<>();
        anchors.add(MenuButton.builder(Material.COMPARATOR)
                .name("&eSort Online First")
                .slot(18)
                .onClick(player -> Message.info("menu.debug.sort.online").skipTranslation().send(player))
                .build());
        anchors.add(MenuButton.builder(Material.BOOK)
                .name("&bPrivacy Settings")
                .slot(26)
                .onClick(player -> Message.info("menu.debug.privacy").skipTranslation().send(player))
                .build());
        return anchors;
    }

    private Collection<? extends MenuItem> createCosmeticItems(Player requester) {
        List<MenuItem> items = new ArrayList<>();
        items.add(MenuDisplayItem.builder(Material.BLAZE_POWDER)
                .name(Component.text("Particle Trails", NamedTextColor.GOLD))
                .description("Switch between seasonal and legacy particle stacks.")
                .build());
        items.add(MenuDisplayItem.builder(Material.SADDLE)
                .name(Component.text("Mounts", NamedTextColor.BLUE))
                .description("Classic horses, futuristic hoverboards, and more.")
                .build());
        items.add(MenuDisplayItem.builder(Material.SLIME_BALL)
                .name(Component.text("Gadgets", NamedTextColor.GREEN))
                .description("Launch pads, fireworks, and lobby shenanigans.")
                .build());
        items.add(MenuDisplayItem.builder(Material.ENDER_PEARL)
                .name(Component.text("Teleports", NamedTextColor.LIGHT_PURPLE))
                .description("Instant-travel runes to favorite hubs.")
                .build());
        return items;
    }

    private Collection<? extends MenuItem> buildFriendEntries(Player player) {
        return player.getServer().getOnlinePlayers().stream()
                .map(target -> MenuButton.builder(Material.PLAYER_HEAD)
                        .name(Component.text(target.getName(), NamedTextColor.YELLOW))
                        .secondary("&7Click to message")
                        .description("Latency: " + target.getPing() + "ms")
                        .onClick(p -> Message.info("Sent a wave to {arg0}", target.getName())
                                .skipTranslation()
                                .send(p))
                        .build())
                .limit(12)
                .toList();
    }

    private Collection<? extends MenuItem> buildDynamicEntries(Player player) {
        List<MenuItem> items = new ArrayList<>();
        items.add(MenuDisplayItem.builder(Material.CLOCK)
                .name(Component.text("Server Time", NamedTextColor.GOLD))
                .description(TIME_FORMATTER.format(Instant.now()))
                .build());
        items.add(MenuDisplayItem.builder(Material.REDSTONE)
                .name(Component.text("TPS", NamedTextColor.RED))
                .description(String.format(Locale.US, "%.2f", Bukkit.getServer().getTPS()[0]))
                .build());
        items.add(MenuDisplayItem.builder(Material.LAPIS_LAZULI)
                .name(Component.text("Online Players", NamedTextColor.BLUE))
                .description(String.valueOf(Bukkit.getOnlinePlayers().size()))
                .build());
        items.add(MenuDisplayItem.builder(Material.PAPER)
                .name(Component.text("You", NamedTextColor.AQUA))
                .description("Ping: " + player.getPing() + "ms")
                .build());
        items.add(MenuDisplayItem.builder(Material.COMPASS)
                .name(Component.text("Location", NamedTextColor.YELLOW))
                .description(String.format(Locale.US, "%.1f / %.1f / %.1f",
                        player.getLocation().getX(),
                        player.getLocation().getY(),
                        player.getLocation().getZ()))
                .build());
        items.add(MenuDisplayItem.builder(Material.BLUE_ICE)
                .name(Component.text("Heap", NamedTextColor.AQUA))
                .description(String.format(Locale.US, "%.2f MB used",
                        (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024d / 1024d))
                .build());
        items.add(MenuDisplayItem.builder(Material.NETHER_STAR)
                .name(Component.text("Uptime", NamedTextColor.LIGHT_PURPLE))
                .description((System.currentTimeMillis() / 1000) + "s since launch")
                .build());
        items.add(MenuDisplayItem.builder(Material.EXPERIENCE_BOTTLE)
                .name(Component.text("Player XP", NamedTextColor.YELLOW))
                .description(player.getLevel() + " levels")
                .build());
        items.add(MenuDisplayItem.builder(Material.CHEST)
                .name(Component.text("Inventory Slots", NamedTextColor.GOLD))
                .description(String.valueOf(player.getInventory().getSize()))
                .build());
        items.add(MenuDisplayItem.builder(Material.REDSTONE_LAMP)
                .name(Component.text("World Time", NamedTextColor.RED))
                .description(String.valueOf(player.getWorld().getTime()))
                .build());
        items.add(MenuDisplayItem.builder(Material.TARGET)
                .name(Component.text("Chunk", NamedTextColor.GRAY))
                .description(player.getChunk().getX() + ", " + player.getChunk().getZ())
                .build());
        items.add(MenuDisplayItem.builder(Material.SPYGLASS)
                .name(Component.text("Facing", NamedTextColor.BLUE))
                .description(player.getFacing().name())
                .build());
        for (int i = 0; i < 25; i++) {
            items.add(MenuDisplayItem.builder(Material.PAPER)
                    .name(Component.text("Log Entry #" + (i + 1), NamedTextColor.WHITE))
                    .description("Event recorded at " + TIME_FORMATTER.format(Instant.now().minusSeconds(i * 42L)))
                    .build());
        }
        return items;
    }
}
