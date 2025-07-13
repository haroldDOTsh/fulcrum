package sh.harold.fulcrum.api.message;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.message.command.MessageReloadCommand;
import sh.harold.fulcrum.api.message.util.DefaultTagFormatter;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;

public class MessageFeature implements PluginFeature {

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        YamlMessageService service = new YamlMessageService(plugin.getDataFolder().toPath().resolve("lang"));
        service.setTagFormatter(new DefaultTagFormatter());
        
        // Register MessageService in the DependencyContainer instead of using static setter
        container.register(MessageService.class, service);
        
        // Keep backward compatibility by also setting static service
        Message.setMessageService(service);

        CommandRegistrar.register(MessageReloadCommand.create(service));
    }

    @Override
    public void shutdown() {
        // No-op
    }
    
    @Override
    public int getPriority() {
        return 1; // Highest priority - loads first
    }
}
