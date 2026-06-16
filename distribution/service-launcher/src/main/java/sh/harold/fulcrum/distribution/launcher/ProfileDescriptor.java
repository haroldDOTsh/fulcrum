package sh.harold.fulcrum.distribution.launcher;

import java.util.regex.Pattern;

record ProfileDescriptor(
        String resourcePath,
        String profileId,
        String semanticModel,
        String contractSet,
        String servicePlacement,
        String storageShape,
        String agonesMode,
        String objectStorage
) {
    private static final Pattern STRING_FIELD = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"");

    static ProfileDescriptor parse(String resourcePath, String json) {
        return new ProfileDescriptor(
                resourcePath,
                field(resourcePath, json, "profileId"),
                field(resourcePath, json, "semanticModel"),
                field(resourcePath, json, "contractSet"),
                field(resourcePath, json, "servicePlacement"),
                field(resourcePath, json, "storageShape"),
                field(resourcePath, json, "agonesMode"),
                field(resourcePath, json, "objectStorage")
        );
    }

    private static String field(String resourcePath, String json, String name) {
        var matcher = STRING_FIELD.matcher(json);
        while (matcher.find()) {
            if (matcher.group(1).equals(name)) {
                return matcher.group(2);
            }
        }
        throw new IllegalStateException("Deployment profile descriptor " + resourcePath
                + " is missing required field " + name);
    }
}
