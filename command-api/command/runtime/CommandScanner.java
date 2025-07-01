package sh.harold.fulcrum.command.runtime;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

import java.util.HashSet;
import java.util.Set;

public final class CommandScanner {
    private CommandScanner() {
    }

    /**
     * Finds all classes in the given classloader and base package that are annotated with @Command
     * and implement CommandExecutor.
     *
     * @param classLoader the classloader to scan
     * @param basePackage the base package to scan
     * @return set of valid command classes
     */
    public static Set<Class<?>> findCommandClasses(ClassLoader classLoader, String basePackage) {
        Set<Class<?>> result = new HashSet<>();
        try (ScanResult scan = new ClassGraph()
                .enableClassInfo()
                .enableAnnotationInfo()
                .acceptPackages(basePackage)
                .overrideClassLoaders(classLoader)
                .scan()) {
            Class<?> annotationClass = Class.forName("sh.harold.fulcrum.command.annotations.Command", true, classLoader);
            for (var classInfo : scan.getClassesWithAnnotation(annotationClass.getName())) {
                Class<?> clazz = classInfo.loadClass();
                try {
                    Class<?> executorClass = Class.forName("sh.harold.fulcrum.command.CommandExecutor", true, classLoader);
                    clazz.getDeclaredConstructor();
                    if (executorClass.isAssignableFrom(clazz)) {
                        result.add(clazz);
                    }
                } catch (Exception ignored) {
                    // Skip if not valid
                }
            }
        } catch (Exception e) {
            // Log or handle scanning errors if needed in the future:tm:
        }
        return result;
    }

    // Legacy method for backward compatibility (scans default package/classloader)
    public static Set<Class<?>> findCommandClasses() {
        return findCommandClasses(CommandScanner.class.getClassLoader(), "sh.harold.fulcrum");
    }
}
