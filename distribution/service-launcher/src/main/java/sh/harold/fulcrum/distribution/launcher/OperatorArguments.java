package sh.harold.fulcrum.distribution.launcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class OperatorArguments {
    private final List<String> positionals;
    private final Map<String, List<String>> values;
    private final Set<String> flags;

    private OperatorArguments(
            List<String> positionals,
            Map<String, List<String>> values,
            Set<String> flags) {
        this.positionals = List.copyOf(positionals);
        this.values = Map.copyOf(values);
        this.flags = Set.copyOf(flags);
    }

    static OperatorArguments parse(String[] args, Set<String> booleanOptions) {
        List<String> positionals = new ArrayList<>();
        Map<String, List<String>> values = new HashMap<>();
        Set<String> flags = new HashSet<>();
        Set<String> booleans = new HashSet<>(booleanOptions);
        booleans.add("help");

        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if (!arg.startsWith("--")) {
                positionals.add(arg);
                continue;
            }

            String option = arg.substring(2);
            int delimiter = option.indexOf('=');
            if (delimiter >= 0) {
                String name = nonBlank(option.substring(0, delimiter), "option name");
                String value = nonBlank(option.substring(delimiter + 1), "--" + name);
                values.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
                continue;
            }

            String name = nonBlank(option, "option name");
            if (booleans.contains(name)) {
                flags.add(name);
                continue;
            }
            if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for --" + name);
            }
            values.computeIfAbsent(name, ignored -> new ArrayList<>()).add(nonBlank(args[++index], "--" + name));
        }
        return new OperatorArguments(positionals, values, flags);
    }

    List<String> positionals() {
        return positionals;
    }

    boolean flag(String name) {
        return flags.contains(name);
    }

    Optional<String> value(String name) {
        List<String> found = values.get(name);
        if (found == null || found.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(found.getLast());
    }

    String requiredValue(String name) {
        return value(name).orElseThrow(() -> new IllegalArgumentException("Missing required option --" + name));
    }

    List<String> values(String name) {
        return values.getOrDefault(name, List.of());
    }

    boolean booleanValue(String name, boolean defaultValue) {
        if (flag(name)) {
            return true;
        }
        return value(name).map(Boolean::parseBoolean).orElse(defaultValue);
    }

    void rejectUnknown(Set<String> allowed) {
        List<String> unknown = new ArrayList<>();
        flags.stream()
                .filter(option -> !allowed.contains(option))
                .sorted()
                .forEach(unknown::add);
        values.keySet().stream()
                .filter(option -> !allowed.contains(option))
                .sorted()
                .forEach(unknown::add);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown fulcrum option: --" + unknown.getFirst());
        }
    }

    private static String nonBlank(String value, String label) {
        String checked = value.trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
