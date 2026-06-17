package sh.harold.fulcrum.host.paper;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record AgonesGameServerSnapshot(
        String name,
        String namespace,
        String state,
        String rawJson) {
    public static final String SESSION_ID_ANNOTATION = "sh.harold.fulcrum/session-id";
    public static final String SLOT_ID_ANNOTATION = "sh.harold.fulcrum/slot-id";
    public static final String RESOLVED_MANIFEST_ID_ANNOTATION = "sh.harold.fulcrum/resolved-manifest-id";

    public AgonesGameServerSnapshot {
        name = PaperArtifactNames.requireNonBlank(name, "name");
        namespace = PaperArtifactNames.requireNonBlank(namespace, "namespace");
        state = PaperArtifactNames.requireNonBlank(state, "state");
        rawJson = Objects.requireNonNull(rawJson, "rawJson");
    }

    public Optional<String> annotation(String key) {
        String checkedKey = PaperArtifactNames.requireNonBlank(key, "key");
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(checkedKey) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                .matcher(rawJson);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(PaperArtifactNames.requireNonBlank(unescape(matcher.group(1)), checkedKey));
    }

    private static String unescape(String value) {
        StringBuilder unescaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '\\') {
                unescaped.append(character);
                continue;
            }
            if (index + 1 >= value.length()) {
                throw new IllegalStateException("Invalid JSON escape sequence in Agones GameServer metadata");
            }
            char escaped = value.charAt(++index);
            switch (escaped) {
                case '"', '\\', '/' -> unescaped.append(escaped);
                case 'b' -> unescaped.append('\b');
                case 'f' -> unescaped.append('\f');
                case 'n' -> unescaped.append('\n');
                case 'r' -> unescaped.append('\r');
                case 't' -> unescaped.append('\t');
                case 'u' -> {
                    if (index + 4 >= value.length()) {
                        throw new IllegalStateException("Invalid JSON unicode escape sequence in Agones GameServer metadata");
                    }
                    String hex = value.substring(index + 1, index + 5);
                    unescaped.append((char) Integer.parseInt(hex, 16));
                    index += 4;
                }
                default -> throw new IllegalStateException("Invalid JSON escape sequence in Agones GameServer metadata");
            }
        }
        return unescaped.toString();
    }
}
