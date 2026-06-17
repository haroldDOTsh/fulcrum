package sh.harold.fulcrum.host.paper;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.host.tick.HostMainThread;

import java.util.Objects;

public final class PaperHostMainThread implements HostMainThread {
    private final JavaPlugin plugin;

    public PaperHostMainThread(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public boolean isMainThread() {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public void execute(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, Objects.requireNonNull(task, "task"));
    }
}
