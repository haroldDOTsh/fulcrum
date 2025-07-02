package sh.harold.fulcrum.module;

import java.util.*;
import java.util.function.Function;

/**
 * Generic dependency resolver for topological sorting of modules.
 */
public final class DependencyResolver {
    private DependencyResolver() {}

    public static <T> List<T> resolve(List<T> nodes, Function<T, String> nameExtractor, Function<T, List<String>> dependencyExtractor) {
        Map<String, T> nodeMap = new HashMap<>();
        for (var node : nodes) {
            nodeMap.put(nameExtractor.apply(node), node);
        }
        List<T> sorted = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        for (var node : nodes) {
            visit(node, nameExtractor, dependencyExtractor, nodeMap, visited, visiting, sorted, new ArrayDeque<>());
        }
        return sorted;
    }

    private static <T> void visit(
            T node,
            Function<T, String> nameExtractor,
            Function<T, List<String>> dependencyExtractor,
            Map<String, T> nodeMap,
            Set<String> visited,
            Set<String> visiting,
            List<T> sorted,
            Deque<String> stack
    ) {
        String name = nameExtractor.apply(node);
        if (visited.contains(name)) return;
        if (visiting.contains(name)) {
            stack.addLast(name);
            throw new IllegalStateException("[Module] Circular dependency detected: " + String.join(" -> ", stack));
        }
        visiting.add(name);
        stack.addLast(name);
        for (String dep : dependencyExtractor.apply(node)) {
            if (!nodeMap.containsKey(dep)) {
                throw new IllegalStateException("[Module] Failed to resolve dependencies: Missing dependency '" + dep + "' required by '" + name + "'");
            }
            visit(nodeMap.get(dep), nameExtractor, dependencyExtractor, nodeMap, visited, visiting, sorted, stack);
        }
        visiting.remove(name);
        stack.removeLast();
        visited.add(name);
        sorted.add(node);
    }
}
