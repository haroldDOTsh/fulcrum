package sh.harold.fulcrum.playerdata;

import org.bukkit.plugin.java.JavaPlugin;

public final class PlayerDataPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(new PlayerDataLifecycleListener(), this);
    }
}
