package sh.harold.fulcrum;

import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.Yaml;

import java.io.*;

/**
 * Loads the runtime environment configuration from environment.yml, generating a stub if missing.
 */
public final class EnvironmentLoader {
    private EnvironmentLoader() {}

    public static RuntimeEnvironment load(JavaPlugin plugin) throws IOException {
        File file = new File(plugin.getDataFolder(), "environment.yml");

        if (!file.exists()) {
            plugin.getDataFolder().mkdirs();
            try (PrintWriter out = new PrintWriter(file)) {
                out.println("runtimes:");
                out.println("  example:");
                out.println("    map: \"example_world.zip\"");
                out.println("    modules:");
                out.println("      - ExampleModule");
            }
            plugin.getLogger().info("Generated default environment.yml.");
        }

        try (InputStream in = new FileInputStream(file)) {
            // Use the default Constructor (no-arg) for SnakeYAML 2.x, then map manually
            Yaml yaml = new Yaml();
            var raw = yaml.load(in);
            if (raw instanceof java.util.Map<?, ?> map) {
                Object runtimesObj = map.get("runtimes");
                if (runtimesObj instanceof java.util.Map<?, ?> runtimesMap) {
                    java.util.Map<String, RuntimeProfile> runtimes = new java.util.HashMap<>();
                    for (var entry : runtimesMap.entrySet()) {
                        String key = entry.getKey().toString();
                        Object value = entry.getValue();
                        if (value instanceof java.util.Map<?, ?> profileMap) {
                            Object mapObj = profileMap.get("map");
                            String mapName = mapObj != null ? mapObj.toString() : "";
                            Object modulesObj = profileMap.get("modules");
                            java.util.List<String> modules = new java.util.ArrayList<>();
                            if (modulesObj instanceof java.util.List<?> list) {
                                for (Object mod : list) {
                                    modules.add(mod.toString());
                                }
                            }
                            runtimes.put(key, new RuntimeProfile(mapName, modules));
                        }
                    }
                    return new RuntimeEnvironment(runtimes);
                }
            }
            throw new IOException("Invalid environment.yml structure");
        }
    }
}
