package sh.harold.fulcrum.command.runtime;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import java.util.HashSet;
import java.util.Set;

public final class CommandScanner {
    private CommandScanner() {}

    public static Set<Class<?>> findCommandClasses() {
        Set<Class<?>> result = new HashSet<>();
        try (ScanResult scan = new ClassGraph()
                .enableClassInfo()
                .enableAnnotationInfo()
                .acceptPackages("sh.harold.fulcrum")
                .scan()) {
            Class<?> annotationClass = Class.forName("sh.harold.fulcrum.command.annotations.Command");
            for (var classInfo : scan.getClassesWithAnnotation(annotationClass.getName())) {
                Class<?> clazz = classInfo.loadClass();
                try {
                    Class<?> executorClass = Class.forName("sh.harold.fulcrum.command.CommandExecutor");
                    clazz.getDeclaredConstructor();
                    if (executorClass.isAssignableFrom(clazz)) {
                        result.add(clazz);
                    }
                } catch (Exception ignored) {
                    // Skip if not valid
                }
            }
        } catch (Exception e) {
            // Log or handle scanning errors if needed
        }
        return result;
    }
}
