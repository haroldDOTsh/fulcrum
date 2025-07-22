# Fulcrum Module API

The public API for creating external Fulcrum modules. This module provides the core interfaces and contracts that external module developers need to integrate with the Fulcrum platform.

## Purpose

This API module is designed to provide a clean, stable interface for external module developers who want to create plugins that integrate with Fulcrum's module system. It contains only the essential interfaces and annotations needed for module development, with no runtime dependencies.

## Target Audience

- **External module developers** who want to create Fulcrum-compatible modules
- **Plugin developers** who need to integrate with Fulcrum's module system
- **Third-party developers** building extensions for Fulcrum-powered servers

## Key Features

- **Lightweight**: No runtime dependencies - perfect for external use
- **Stable API**: Provides consistent interfaces across Fulcrum versions
- **Module Integration**: Core contracts for module lifecycle management
- **Dependency Management**: Support for module dependencies and load ordering
- **Metadata Support**: Rich module information and configuration options

## Usage

### Adding to Your Project

Add the Fulcrum Module API to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("sh.harold.fulcrum:module-api:1.1.0")
}
```

### Creating a Fulcrum Module

```java
import sh.harold.fulcrum.api.module.FulcrumModule;
import sh.harold.fulcrum.api.module.ModuleInfo;

@ModuleInfo(
    name = "MyModule",
    version = "1.0.0",
    author = "YourName",
    description = "A sample Fulcrum module"
)
public class MyModule implements FulcrumModule {
    
    @Override
    public void onEnable() {
        // Module initialization logic
        getLogger().info("MyModule has been enabled!");
    }
    
    @Override
    public void onDisable() {
        // Module cleanup logic
        getLogger().info("MyModule has been disabled!");
    }
}
```

### Module Dependencies

Specify dependencies on other modules:

```java
@ModuleInfo(
    name = "AdvancedModule",
    version = "2.0.0",
    dependencies = {"CoreModule", "DataModule"},
    softDependencies = {"OptionalModule"}
)
public class AdvancedModule implements FulcrumModule {
    // Implementation
}
```

## API Structure

The API is organized into the following packages:

- **Core Module Interface**: Base contracts for module lifecycle
- **Metadata System**: Annotations for module information and configuration
- **Dependency Resolution**: Support for module dependencies and load ordering
- **Service Integration**: Interfaces for integrating with Fulcrum services

## Best Practices

1. **Keep modules lightweight** - Only include necessary dependencies
2. **Use proper lifecycle management** - Clean up resources in `onDisable()`
3. **Declare dependencies correctly** - Use `dependencies` for required modules, `softDependencies` for optional ones
4. **Provide good metadata** - Include clear name, version, and description
5. **Handle errors gracefully** - Don't crash the server if your module fails

## Module Lifecycle

Fulcrum modules follow this lifecycle:

1. **Discovery** - Fulcrum scans for modules and reads metadata
2. **Dependency Resolution** - Dependencies are resolved and load order determined  
3. **Loading** - Module classes are loaded and instantiated
4. **Enabling** - `onEnable()` is called in dependency order
5. **Runtime** - Module operates normally
6. **Disabling** - `onDisable()` is called in reverse dependency order

## Integration with Fulcrum Services

Your modules can integrate with Fulcrum's built-in services:

```java
public class IntegratedModule implements FulcrumModule {
    
    @Override
    public void onEnable() {
        // Access Fulcrum services (examples - actual API may vary)
        // DataService dataService = Fulcrum.getService(DataService.class);
        // MessageService messageService = Fulcrum.getService(MessageService.class);
    }
}
```

## Version Compatibility

This API follows semantic versioning:
- **Major versions** may contain breaking changes
- **Minor versions** add new features while maintaining compatibility
- **Patch versions** contain bug fixes and improvements

Current version: **1.1.0**

## Support

For questions about module development:
1. Check the official Fulcrum documentation
2. Review example modules in the Fulcrum repository
3. Open an issue on the Fulcrum GitHub repository

## License

This API is part of the Fulcrum project and follows the same licensing terms.