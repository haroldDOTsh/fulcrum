package sh.harold.fulcrum.distribution.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

record BundleDesiredState(String schema, List<DeclaredBundle> bundles) {
    static final String SCHEMA = "fulcrum.bundle-desired-state/v1";

    BundleDesiredState {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported bundle desired-state schema: " + schema);
        }
        bundles = List.copyOf(bundles);
    }

    static BundleDesiredState empty() {
        return new BundleDesiredState(SCHEMA, List.of());
    }

    BundleDesiredState addOrReplace(DeclaredBundle bundle) {
        List<DeclaredBundle> next = new ArrayList<>();
        boolean replaced = false;
        for (DeclaredBundle existing : bundles) {
            if (existing.id().equals(bundle.id())) {
                next.add(bundle);
                replaced = true;
            } else {
                next.add(existing);
            }
        }
        if (!replaced) {
            next.add(bundle);
        }
        return new BundleDesiredState(schema, next);
    }

    BundleDesiredState remove(String id) {
        return new BundleDesiredState(
                schema,
                bundles.stream()
                        .filter(bundle -> !bundle.id().equals(id))
                        .toList());
    }

    Optional<DeclaredBundle> find(String id) {
        return bundles.stream().filter(bundle -> bundle.id().equals(id)).findFirst();
    }

    String toJson() {
        String line = System.lineSeparator();
        StringBuilder json = new StringBuilder();
        json.append("{").append(line);
        json.append("  \"schema\": \"").append(escape(schema)).append("\",").append(line);
        json.append("  \"bundles\": [").append(line);
        for (int index = 0; index < bundles.size(); index++) {
            json.append(bundleJson(bundles.get(index)));
            if (index + 1 < bundles.size()) {
                json.append(",");
            }
            json.append(line);
        }
        json.append("  ]").append(line);
        json.append("}").append(line);
        return json.toString();
    }

    static BundleDesiredState fromJson(String json) {
        String schema = field(json, "schema");
        List<DeclaredBundle> bundles = new ArrayList<>();
        int arrayStart = json.indexOf("\"bundles\": [");
        if (arrayStart < 0) {
            throw new IllegalArgumentException("desired state missing bundles");
        }
        int cursor = json.indexOf('{', arrayStart);
        while (cursor >= 0) {
            int end = json.indexOf('}', cursor);
            if (end < 0) {
                throw new IllegalArgumentException("unterminated bundle declaration");
            }
            String object = json.substring(cursor, end + 1);
            bundles.add(bundleFromJson(object));
            cursor = json.indexOf('{', end + 1);
        }
        return new BundleDesiredState(schema, bundles);
    }

    private static String bundleJson(DeclaredBundle bundle) {
        return "    {"
                + "\"id\":\"" + escape(bundle.id()) + "\","
                + "\"artifactRef\":\"" + escape(bundle.artifactRef()) + "\","
                + "\"digest\":\"" + escape(bundle.digest()) + "\","
                + "\"kind\":\"" + escape(bundle.kind()) + "\","
                + "\"scope\":\"" + escape(bundle.scope()) + "\","
                + "\"placementProfile\":\"" + escape(bundle.placementProfile()) + "\","
                + "\"placementTier\":\"" + escape(bundle.placementTier().orElse("none")) + "\","
                + "\"authorityDomains\":" + arrayJson(bundle.authorityDomains()) + ","
                + "\"resourceClasses\":" + arrayJson(bundle.resourceClasses()) + ","
                + "\"enabled\":" + bundle.enabled()
                + "}";
    }

    private static DeclaredBundle bundleFromJson(String object) {
        String placementTier = field(object, "placementTier");
        return new DeclaredBundle(
                field(object, "id"),
                field(object, "artifactRef"),
                field(object, "digest"),
                field(object, "kind"),
                field(object, "scope"),
                field(object, "placementProfile"),
                placementTier.equals("none") ? Optional.empty() : Optional.of(placementTier),
                arrayField(object, "authorityDomains"),
                arrayField(object, "resourceClasses"),
                booleanField(object, "enabled"));
    }

    private static String arrayJson(List<String> values) {
        return values.stream()
                .map(value -> "\"" + escape(value) + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static List<String> arrayField(String json, String name) {
        String marker = "\"" + name + "\":[";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalArgumentException("missing array field: " + name);
        }
        int valueStart = start + marker.length();
        int end = json.indexOf(']', valueStart);
        if (end < 0) {
            throw new IllegalArgumentException("unterminated array field: " + name);
        }
        String raw = json.substring(valueStart, end).trim();
        if (raw.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.length() < 2 || trimmed.charAt(0) != '"' || trimmed.charAt(trimmed.length() - 1) != '"') {
                throw new IllegalArgumentException("invalid array value for: " + name);
            }
            values.add(unescape(trimmed.substring(1, trimmed.length() - 1)));
        }
        return values;
    }

    private static String field(String json, String name) {
        String marker = "\"" + name + "\": \"";
        int start = json.indexOf(marker);
        if (start < 0) {
            marker = "\"" + name + "\":\"";
            start = json.indexOf(marker);
        }
        if (start < 0) {
            throw new IllegalArgumentException("missing field: " + name);
        }
        int valueStart = start + marker.length();
        int end = json.indexOf('"', valueStart);
        if (end < 0) {
            throw new IllegalArgumentException("unterminated field: " + name);
        }
        return unescape(json.substring(valueStart, end));
    }

    private static boolean booleanField(String json, String name) {
        String marker = "\"" + name + "\":";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalArgumentException("missing boolean field: " + name);
        }
        int valueStart = start + marker.length();
        if (json.startsWith("true", valueStart)) {
            return true;
        }
        if (json.startsWith("false", valueStart)) {
            return false;
        }
        throw new IllegalArgumentException("invalid boolean field: " + name);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
