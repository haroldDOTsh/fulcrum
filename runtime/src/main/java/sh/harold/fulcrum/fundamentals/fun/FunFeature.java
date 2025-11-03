package sh.harold.fulcrum.fundamentals.fun;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.fundamentals.fun.command.KaboomCommand;
import sh.harold.fulcrum.fundamentals.fun.quickmaths.QuickMathsCommand;
import sh.harold.fulcrum.fundamentals.fun.quickmaths.QuickMathsManager;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.minigame.MinigameEngine;

import java.util.function.Supplier;

/**
 * Bundles lightweight "fun" commands such as Kaboom and Quick Maths.
 */
public final class FunFeature implements PluginFeature, Listener {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private JavaPlugin plugin;
    private QuickMathsManager quickMathsManager;

    @Override
    public int getPriority() {
        return 60;
    }

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        Supplier<MinigameEngine> engineSupplier = () -> container.getOptional(MinigameEngine.class)
                .orElseGet(() -> ServiceLocatorImpl.getInstance() != null
                        ? ServiceLocatorImpl.getInstance().findService(MinigameEngine.class).orElse(null)
                        : null);
        this.quickMathsManager = new QuickMathsManager(plugin, engineSupplier);

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        CommandRegistrar.register(new KaboomCommand().build());
        CommandRegistrar.register(new QuickMathsCommand(quickMathsManager).build());
        plugin.getLogger().info("[FUNDAMENTALS] Fun commands ready");
    }

    @Override
    public void shutdown() {
        HandlerList.unregisterAll(this);
        quickMathsManager = null;
        plugin = null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAsyncChat(AsyncChatEvent event) {
        if (quickMathsManager == null) {
            return;
        }
        String plain = PLAIN.serialize(event.originalMessage());
        quickMathsManager.handleChat(event.getPlayer(), plain);
    }
}
