package sh.harold.fulcrum.command.runtime;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.lifecycle.PluginFeature;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class CommandFeature implements PluginFeature {
    @Override
    public void initialize(JavaPlugin plugin) {
        var logger = plugin.getLogger();
        plugin.getLifecycleManager().registerEventHandler(io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS, event -> {
            Set<Class<?>> commandClasses = CommandScanner.findCommandClasses();
            List<Object> definitions = new ArrayList<>();
            for (Class<?> clazz : commandClasses) {
                try {
                    // Use reflection to avoid direct cross-module references
                    Class<?> annClass = Class.forName("sh.harold.fulcrum.command.annotations.Command");
                    Object cmdAnn = null;
                    for (var ann : clazz.getAnnotations()) {
                        if (ann.annotationType().getName().equals(annClass.getName())) {
                            cmdAnn = ann;
                            break;
                        }
                    }
                    if (cmdAnn == null) continue;
                    if (!Class.forName("sh.harold.fulcrum.command.CommandExecutor").isAssignableFrom(clazz)) {
                        logger.warning("[command-core] Skipping class (not a CommandExecutor): " + clazz.getSimpleName());
                        continue;
                    }
                    clazz.getDeclaredConstructor();
                    // Build CommandDefinition reflectively
                    var defCtor = Class.forName("sh.harold.fulcrum.command.CommandDefinition")
                            .getConstructor(String.class, String[].class, Class.class);
                    String name = (String) cmdAnn.getClass().getMethod("value").invoke(cmdAnn);
                    Object def = defCtor.newInstance(
                            name,
                            new String[0],
                            clazz
                    );
                    definitions.add(def);
                    logger.info("[command-core] Registered command class: " + clazz.getSimpleName() + " (" + name + ")");
                } catch (NoSuchMethodException e) {
                    logger.warning("[command-core] Skipping class (no no-arg constructor): " + clazz.getSimpleName());
                } catch (Exception e) {
                    logger.warning("[command-core] Malformed command class: " + clazz.getSimpleName() + " - " + e.getMessage());
                }
            }
            if (!definitions.isEmpty()) {
                try {
                    // Pass the event registrar directly to the bridge for registration
                    sh.harold.fulcrum.command.runtime.CommandRegistrationBridge.registerCommands(plugin, definitions, event.registrar());
                } catch (Exception e) {
                    logger.log(java.util.logging.Level.WARNING, "[command-core] Failed to register commands", e);
                }
            }
        });
    }
}
