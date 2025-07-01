package sh.harold.fulcrum.api.message;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.message.command.MessageReloadCommand;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.PluginFeature;

public class MessageFeature implements PluginFeature {

    @Override
    public void initialize(JavaPlugin plugin) {
        YamlMessageService service = new YamlMessageService(plugin.getDataFolder().toPath().resolve("lang"));
        service.setTagFormatter(new DefaultTagFormatter());
        Message.setMessageService(service);


        CommandRegistrar.register(MessageReloadCommand.create(service));
    }

    @Override
    public void shutdown() {
        // No-op
    }
}
