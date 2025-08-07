# Fulcrum Module API

API for creating external Fulcrum modules.

## Usage

### Adding to Your Project

Add the Fulcrum Module API to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("sh.harold.fulcrum:module-api:1.1.0")
}
```

### Creating a Fulcrum Module

Create your module class:

```java
import sh.harold.fulcrum.api.module.FulcrumModule;
import sh.harold.fulcrum.api.module.ModuleInfo;
import sh.harold.fulcrum.api.module.FulcrumPlatform;

@ModuleInfo(
    name = "MyModule",
    description = "A sample Fulcrum module"
)
public class MyModule implements FulcrumModule {
    
    @Override
    public void onEnable(FulcrumPlatform platform) {
        // Module initialization logic
        platform.getLogger().info("MyModule has been enabled!");
    }
    
    @Override
    public void onDisable() {
        // Module cleanup logic
        System.out.println("MyModule has been disabled!");
    }
}
```

Create a bootstrapper class for environment checking:

```java
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.bootstrap.PluginProviderContext;
import sh.harold.fulcrum.api.module.FulcrumEnvironment;
import org.bukkit.plugin.java.JavaPlugin;

public class MyModuleBootstrapper implements PluginBootstrap {
    
    @Override
    public void bootstrap(BootstrapContext context) {
        // Check if this module should be enabled in the current environment
        if (!FulcrumEnvironment.isThisModuleEnabled()) {
            context.getLogger().info("MyModule is disabled in this environment");
            // Module will not be loaded
            return;
        }
        
        context.getLogger().info("MyModule bootstrap completed");
    }
    
    @Override
    public JavaPlugin createPlugin(PluginProviderContext context) {
        return new MyModulePlugin(); // Your main plugin class
    }
}
```

Configure your `paper-plugin.yml`:

```yaml
name: MyModule
version: 1.0.0
main: com.example.MyModulePlugin
bootstrapper: com.example.MyModuleBootstrapper
api-version: '1.21'
```

### Module Dependencies

Specify dependencies on other modules:

```java
@ModuleInfo(
    name = "AdvancedModule",
    description = "An advanced module with dependencies",
    dependsOn = {"CoreModule", "DataModule"}
)
public class AdvancedModule implements FulcrumModule {
    
    @Override
    public void onEnable(FulcrumPlatform platform) {
        // Implementation logic
    }
    
    @Override
    public void onDisable() {
        // Cleanup logic
    }
}
```

With corresponding bootstrapper:

```java
public class AdvancedModuleBootstrapper implements PluginBootstrap {
    
    @Override
    public void bootstrap(BootstrapContext context) {
        if (!FulcrumEnvironment.isThisModuleEnabled()) {
            context.getLogger().info("AdvancedModule disabled for current environment");
            return;
        }
        // Bootstrap logic
    }
    
    @Override
    public JavaPlugin createPlugin(PluginProviderContext context) {
        return new AdvancedModulePlugin();
    }
}
```

## API Structure

The API is organized into the following packages:

- **Core Module Interface**: Base contracts for module lifecycle
- **Metadata System**: Annotations for module information and configuration
- **Dependency Resolution**: Support for module dependencies and load ordering
- **Service Integration**: Interfaces for integrating with Fulcrum services


## Module Lifecycle

Fulcrum modules follow this lifecycle:

1. **Discovery** - Fulcrum scans for modules and reads metadata
2. **Dependency Resolution** - Dependencies are resolved and load order determined  
3. **Loading** - Module classes are loaded and instantiated
4. **Enabling** - `onEnable()` is called in dependency order
5. **Runtime** - Module operates normally
6. **Disabling** - `onDisable()` is called in reverse dependency order

## Integration with Fulcrum Services

Your modules can integrate with Fulcrum's built-in services through the [`FulcrumPlatform`](src/main/java/sh/harold/fulcrum/api/module/FulcrumPlatform.java:10):

```java
public class IntegratedModule implements FulcrumModule {
    
    @Override
    public void onEnable(FulcrumPlatform platform) {
        // Access Fulcrum services safely
        MessageService messageService = platform.getService(MessageService.class);
        if (messageService != null) {
            // Use the message service
        }
        
        // Or use Optional for safer handling
        platform.getOptionalService(DataService.class)
            .ifPresent(dataService -> {
                // Use the data service
            });
    }
    
    @Override
    public void onDisable() {
        // Cleanup logic
    }
}
```

## Environment-Aware Module Development

Modules should use [`FulcrumEnvironment.isThisModuleEnabled()`](src/main/java/sh/harold/fulcrum/api/module/FulcrumEnvironment.java:52) in their bootstrapper class to check if they should be active in the current environment. This enables graceful self-disabling based on server configuration:

```java
public class EnvironmentAwareBootstrapper implements PluginBootstrap {
    
    @Override
    public void bootstrap(BootstrapContext context) {
        // Check environment during bootstrap phase
        if (!FulcrumEnvironment.isThisModuleEnabled()) {
            context.getLogger().info("Module disabled for current environment");
            return; // Module will not be loaded
        }
        
        context.getLogger().info("Module enabled for current environment");
    }
    
    @Override
    public JavaPlugin createPlugin(PluginProviderContext context) {
        return new EnvironmentAwarePlugin();
    }
}
```

Configure in your `paper-plugin.yml`:

```yaml
bootstrapper: com.example.EnvironmentAwareBootstrapper
```