package sh.harold.fulcrum.core.artifact;

import java.util.Locale;
import java.util.regex.Pattern;

public record ArtifactDigestReference(String algorithm, String value) {
    private static final String DEFAULT_ALGORITHM = "sha-256";
    private static final Pattern ALGORITHM = Pattern.compile("[a-z0-9][a-z0-9-]{1,31}");
    private static final Pattern HEX_VALUE = Pattern.compile("[a-f0-9]{32,128}");

    public ArtifactDigestReference {
        algorithm = normalizeAlgorithm(ArtifactLayoutNames.requireNonBlank(algorithm, "algorithm"));
        value = ArtifactLayoutNames.requireNonBlank(value, "value").toLowerCase(Locale.ROOT);
        if (!ALGORITHM.matcher(algorithm).matches()) {
            throw new IllegalArgumentException("algorithm must be a stable digest token");
        }
        if (!HEX_VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException("value must be lowercase hexadecimal");
        }
    }

    public static ArtifactDigestReference parse(String digest) {
        String checked = ArtifactLayoutNames.requireNonBlank(digest, "digest").toLowerCase(Locale.ROOT);
        int delimiter = checked.indexOf(':');
        if (delimiter < 0) {
            return new ArtifactDigestReference(DEFAULT_ALGORITHM, checked);
        }
        if (delimiter == 0 || delimiter == checked.length() - 1 || checked.indexOf(':', delimiter + 1) >= 0) {
            throw new IllegalArgumentException("digest must be algorithm:value or a raw SHA-256 value");
        }
        return new ArtifactDigestReference(checked.substring(0, delimiter), checked.substring(delimiter + 1));
    }

    public String wireValue() {
        return algorithm + ":" + value;
    }

    private static String normalizeAlgorithm(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.equals("sha256") ? DEFAULT_ALGORITHM : normalized;
    }
}
