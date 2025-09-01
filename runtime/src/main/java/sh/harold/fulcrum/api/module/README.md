# Module API

External module system for extending Fulcrum.

## Creating a Module

```java
import sh.harold.fulcrum.api.module.FulcrumModule;
import sh.harold.fulcrum.api.module.ModuleInfo;
import sh.harold.fulcrum.api.module.FulcrumPlatform;

@ModuleInfo(
    name = "MyModule",
    description = "A sample Fulcrum module",
    dependsOn = {"CoreModule", "DataModule"}  // Optional dependencies
)
public class MyModule implements FulcrumModule {
    
    @Override
    public void onEnable(FulcrumPlatform platform) {
        platform.getLogger().info("MyModule enabled!");
        
        // Access services
        MessageService messages = platform.getService(MessageService.class);
        if (messages != null) {
            // Use service
        }
    }
    
    @Override
    public void onDisable() {
        // Cleanup
    }
}
```

## Environment-Aware Bootstrapper

```java
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.bootstrap.PluginProviderContext;
import sh.harold.fulcrum.api.module.FulcrumEnvironment;
import org.bukkit.plugin.java.JavaPlugin;

public class MyModuleBootstrapper implements PluginBootstrap {
    
    @Override
    public void bootstrap(BootstrapContext context) {
        // Check if enabled in this environment
        if (!FulcrumEnvironment.isThisModuleEnabled()) {
            context.getLogger().info("MyModule disabled in this environment");
            return;
        }
        
        context.getLogger().info("MyModule bootstrap completed");
    }
    
    @Override
    public JavaPlugin createPlugin(PluginProviderContext context) {
        return new MyModulePlugin();
    }
}
```

## Configuration

`paper-plugin.yml`:
```yaml
name: MyModule
version: 1.0.0
main: com.example.MyModulePlugin
bootstrapper: com.example.MyModuleBootstrapper
api-version: '1.21'
```

## Service Integration

```java
@Override
public void onEnable(FulcrumPlatform platform) {
    // Get service directly
    MessageService messages = platform.getService(MessageService.class);
    
    // Or use Optional
    platform.getOptionalService(DataService.class)
        .ifPresent(dataService -> {
            // Use data service
        });
}
```

## Module Lifecycle

1. **Discovery** - Scan and read metadata
2. **Dependency Resolution** - Determine load order
3. **Loading** - Instantiate module classes
4. **Enabling** - Call `onEnable()` in dependency order
5. **Runtime** - Normal operation
6. **Disabling** - Call `onDisable()` in reverse order

## Dependencies

Specify module dependencies:

```java
@ModuleInfo(
    name = "AdvancedModule",
    description = "Module with dependencies",
    dependsOn = {"CoreModule", "DataModule"}
)
```

Modules are loaded in dependency order automatically.