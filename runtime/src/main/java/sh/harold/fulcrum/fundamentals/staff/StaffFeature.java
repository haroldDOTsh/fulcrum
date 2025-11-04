package sh.harold.fulcrum.fundamentals.staff;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.fundamentals.actionflag.ActionFlagService;
import sh.harold.fulcrum.fundamentals.staff.command.LoopCommand;
import sh.harold.fulcrum.fundamentals.staff.command.SudoCommand;
import sh.harold.fulcrum.fundamentals.staff.command.VanishCommand;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;

import java.util.Optional;

/**
 * Wires up staff-specific tooling such as /vanish.
 */
public final class StaffFeature implements PluginFeature {
    private StaffVanishService vanishService;

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        ActionFlagService flagService = resolveActionFlagService(container);
        if (flagService == null) {
            throw new IllegalStateException("ActionFlagService not available; StaffFeature requires ActionFlagFeature");
        }

        vanishService = new StaffVanishService(flagService);
        container.register(StaffVanishService.class, vanishService);
        Optional.ofNullable(ServiceLocatorImpl.getInstance())
                .ifPresent(locator -> locator.registerService(StaffVanishService.class, vanishService));

        CommandRegistrar.register(new VanishCommand(vanishService).build());
        CommandRegistrar.register(new SudoCommand().build());
        CommandRegistrar.register(new LoopCommand(plugin).build());
        plugin.getLogger().info("[STAFF] Staff feature initialized");
    }

    @Override
    public void shutdown() {
        Optional.ofNullable(ServiceLocatorImpl.getInstance())
                .ifPresent(locator -> locator.unregisterService(StaffVanishService.class));
        if (vanishService != null) {
            vanishService.shutdown();
            vanishService = null;
        }
    }

    @Override
    public int getPriority() {
        return 140; // Must run after ActionFlagFeature (120) so the service is available
    }

    private ActionFlagService resolveActionFlagService(DependencyContainer container) {
        return container.getOptional(ActionFlagService.class)
                .orElseGet(() -> Optional.ofNullable(ServiceLocatorImpl.getInstance())
                        .flatMap(locator -> locator.findService(ActionFlagService.class))
                        .orElse(null));
    }
}
