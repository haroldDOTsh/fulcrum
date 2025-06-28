package sh.harold.fulcrum.features.message;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.message.DefaultTagFormatter;
import sh.harold.fulcrum.api.message.Message;
import sh.harold.fulcrum.lifecycle.PluginFeature;

public class MessageFeature implements PluginFeature {

    @Override
    public void initialize(JavaPlugin plugin) {
        YamlMessageService service = new YamlMessageService(plugin.getDataFolder().toPath().resolve("lang"));
        service.setTagFormatter(new DefaultTagFormatter());
        Message.setMessageService(service);
    }

    @Override
    public void shutdown() {
        // No-op
    }
}
