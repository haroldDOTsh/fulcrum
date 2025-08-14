package sh.harold.fulcrum.registry.console;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Launcher that runs the registry service in a separate process with proper console interaction
 */
public class InteractiveLauncher {
    
    public static void main(String[] args) throws IOException, InterruptedException {
        // Find the JAR file
        Path jarPath = findJarFile();
        if (jarPath == null) {
            System.err.println("Could not find registry service JAR file. Please build the project first.");
            System.exit(1);
        }
        
        // Build command
        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElse("java"));
        
        // Add JVM options
        command.add("-Xms256m");
        command.add("-Xmx512m");
        
        // Use -jar to run the shadow JAR directly with absolute path
        command.add("-jar");
        command.add(jarPath.toAbsolutePath().toString());
        
        // Create process with inherited IO
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO(); // This is the key - inherit IO streams from parent process
        
        // Set working directory
        Path workingDir = Paths.get("run");
        if (!Files.exists(workingDir)) {
            Files.createDirectories(workingDir);
        }
        pb.directory(workingDir.toFile());
        
        // Pass through environment variables
        Map<String, String> env = pb.environment();
        env.put("REDIS_HOST", System.getenv("REDIS_HOST") != null ? System.getenv("REDIS_HOST") : "localhost");
        env.put("REDIS_PORT", System.getenv("REDIS_PORT") != null ? System.getenv("REDIS_PORT") : "6379");
        env.put("REDIS_PASSWORD", System.getenv("REDIS_PASSWORD") != null ? System.getenv("REDIS_PASSWORD") : "");
        
        // Start the process
        Process process = pb.start();
        
        // Register shutdown hook to stop the child process
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (process.isAlive()) {
                process.destroy();
                try {
                    process.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }));
        
        // Wait for process to complete
        int exitCode = process.waitFor();
        System.exit(exitCode);
    }
    
    private static Path findJarFile() {
        // When running from gradle, we're in the registry-service directory
        // When running standalone, we might be elsewhere
        
        // First check if we're already in registry-service directory
        Path currentDir = Paths.get("").toAbsolutePath();
        Path shadowJar = null;
        
        if (currentDir.endsWith("registry-service")) {
            // We're in the registry-service directory
            shadowJar = Paths.get("build/libs/registry-service-1.0.0-SNAPSHOT.jar");
        } else {
            // We might be in the project root, check registry-service subdirectory
            shadowJar = Paths.get("registry-service/build/libs/registry-service-1.0.0-SNAPSHOT.jar");
        }
        
        if (Files.exists(shadowJar)) {
            System.out.println("Found shadowJar: " + shadowJar.toAbsolutePath());
            return shadowJar;
        }
        
        // Fallback: look for the JAR in common locations
        Path[] possiblePaths = {
            Paths.get("build/libs/registry-service-1.0.0-SNAPSHOT.jar"),
            Paths.get("registry-service/build/libs/registry-service-1.0.0-SNAPSHOT.jar"),
            Paths.get("../registry-service/build/libs/registry-service-1.0.0-SNAPSHOT.jar"),
            Paths.get("build/libs/registry-service.jar"),
            Paths.get("registry-service/build/libs/registry-service.jar")
        };
        
        for (Path path : possiblePaths) {
            if (Files.exists(path)) {
                System.out.println("Found JAR at: " + path.toAbsolutePath());
                return path;
            }
        }
        
        // Last resort: search for any JAR in build/libs
        Path buildLibs = Files.exists(Paths.get("build/libs"))
            ? Paths.get("build/libs")
            : Paths.get("registry-service/build/libs");
            
        if (Files.exists(buildLibs)) {
            try {
                return Files.list(buildLibs)
                    .filter(p -> p.toString().endsWith(".jar") && !p.toString().contains("-sources") && !p.toString().contains("-javadoc"))
                    .findFirst()
                    .orElse(null);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        
        return null;
    }
}