package sh.harold.fulcrum.host.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.host.api.HostMenuContribution;
import sh.harold.fulcrum.host.api.HostMenuRenderFrame;
import sh.harold.fulcrum.host.api.HostMenuSlot;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class PaperHostMenuRuntime implements Listener, AutoCloseable {
    private static final int CHEST_SLOTS = 54;

    private final JavaPlugin plugin;
    private final String baseSessionId;
    private final PaperHostMenuController controller;
    private final List<PaperHostMenuCommand> commands = new ArrayList<>();

    public PaperHostMenuRuntime(
            JavaPlugin plugin,
            Collection<HostMenuContribution> contributions,
            String baseSessionId,
            Clock clock) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.baseSessionId = PaperArtifactNames.requireNonBlank(baseSessionId, "baseSessionId");
        this.controller = new PaperHostMenuController(contributions, clock);
    }

    public boolean hasCommandAliases() {
        return !controller.commandAliases().isEmpty();
    }

    public void registerWithServer() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        CommandMap commandMap = plugin.getServer().getCommandMap();
        for (String alias : controller.commandAliases()) {
            PaperHostMenuCommand command = new PaperHostMenuCommand(alias, this);
            if (commandMap.register("fulcrum", command) && commandMap.getCommand(alias) == command) {
                commands.add(command);
            } else {
                command.unregister(commandMap);
                plugin.getLogger().warning("could not register Paper host menu command alias /" + alias);
            }
        }
    }

    boolean executeMenuCommand(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }
        String rawCommand = PaperHostMenuController.rawCommand(label, args);
        Optional<PaperHostMenuController.OpenedMenu> opened =
                controller.open(viewerId(player), sessionId(player), rawCommand);
        if (opened.isEmpty()) {
            sender.sendMessage("No host menu contribution handles /" + label + ".");
            return true;
        }
        open(player, opened.orElseThrow());
        return true;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        InventoryHolder holder = topInventory.getHolder(false);
        if (!(holder instanceof PaperHostMenuHolder menuHolder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getRawSlot() < 0 || event.getRawSlot() >= topInventory.getSize()) {
            return;
        }
        HumanEntity viewer = event.getView().getPlayer();
        if (!(viewer instanceof Player player)) {
            return;
        }
        Optional<PaperHostMenuController.OpenedMenu> next = controller.click(menuHolder.activeMenu(), event.getRawSlot());
        if (next.isEmpty()) {
            refusal(menuHolder.activeMenu(), event.getRawSlot()).ifPresent(player::sendMessage);
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> open(player, next.orElseThrow()));
    }

    @Override
    public void close() {
        HandlerList.unregisterAll(this);
        CommandMap commandMap = plugin.getServer().getCommandMap();
        for (PaperHostMenuCommand command : commands) {
            command.unregister(commandMap);
        }
        commands.clear();
    }

    private void open(Player player, PaperHostMenuController.OpenedMenu opened) {
        PaperHostMenuHolder holder = new PaperHostMenuHolder(opened);
        Inventory inventory = plugin.getServer().createInventory(holder, CHEST_SLOTS, opened.frame().title());
        holder.bindInventory(inventory);
        render(inventory, opened.frame());
        sendMessages(player, opened.frame());
        player.openInventory(inventory);
    }

    private static void render(Inventory inventory, HostMenuRenderFrame frame) {
        inventory.clear();
        for (HostMenuSlot slot : frame.slots()) {
            if (slot.slot() < inventory.getSize()) {
                inventory.setItem(slot.slot(), item(slot));
            }
        }
    }

    private static ItemStack item(HostMenuSlot slot) {
        Material material = material(slot.itemKey(), slot.enabled());
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Component.text(slot.label()));
            List<Component> lore = new ArrayList<>();
            slot.refusalReason().map(Component::text).ifPresent(lore::add);
            if (slot.enabled() && slot.actionId().isPresent()) {
                lore.add(Component.text("CLICK to select!"));
            }
            if (!lore.isEmpty()) {
                meta.lore(lore);
            }
        });
        return item;
    }

    private static Material material(String itemKey, boolean enabled) {
        String normalized = itemKey.toUpperCase(Locale.ROOT);
        int namespaceSeparator = normalized.indexOf(':');
        if (namespaceSeparator >= 0 && namespaceSeparator < normalized.length() - 1) {
            normalized = normalized.substring(namespaceSeparator + 1);
        }
        Material material = Material.matchMaterial(normalized);
        if (material != null) {
            return material;
        }
        return enabled ? Material.PAPER : Material.BARRIER;
    }

    private static void sendMessages(Player player, HostMenuRenderFrame frame) {
        for (String message : frame.messages()) {
            player.sendMessage(message);
        }
        frame.refusalReason().ifPresent(player::sendMessage);
    }

    private static Optional<String> refusal(PaperHostMenuController.OpenedMenu menu, int slot) {
        HostMenuSlot clicked = menu.slotsByIndex().get(slot);
        return clicked == null ? Optional.empty() : clicked.refusalReason();
    }

    private String sessionId(Player player) {
        return baseSessionId + ":" + player.getUniqueId();
    }

    private static String viewerId(Player player) {
        return player.getUniqueId().toString();
    }

    private static final class PaperHostMenuCommand extends Command {
        private final PaperHostMenuRuntime runtime;

        private PaperHostMenuCommand(String alias, PaperHostMenuRuntime runtime) {
            super(alias, "Fulcrum host menu contribution", "/" + alias, List.of());
            this.runtime = Objects.requireNonNull(runtime, "runtime");
        }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            return runtime.executeMenuCommand(sender, commandLabel, args);
        }
    }
}
