package sh.harold.fulcrum.api.message.scoreboard.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Implementation of ContentProvider for static content.
 * This provider returns fixed content that does not change over time.
 */
public class StaticContentProvider implements ContentProvider {

    private final List<String> content;

    /**
     * Creates a new StaticContentProvider with the given content.
     *
     * @param content the list of content lines
     * @throws IllegalArgumentException if content is null
     */
    public StaticContentProvider(List<String> content) {
        if (content == null) {
            throw new IllegalArgumentException("Content cannot be null");
        }
        this.content = new ArrayList<>(content);
    }

    /**
     * Creates a new StaticContentProvider with a single line of content.
     *
     * @param line the content line
     * @throws IllegalArgumentException if line is null
     */
    public StaticContentProvider(String line) {
        this(Collections.singletonList(line));
    }

    @Override
    public List<String> getContent(UUID playerId) {
        return new ArrayList<>(content);
    }

    @Override
    public boolean isPlayerSpecific() {
        return false;
    }

    @Override
    public boolean isDynamic() {
        return false;
    }

    @Override
    public boolean isContentAvailable(UUID playerId) {
        return !content.isEmpty();
    }

    @Override
    public String getCacheKey(UUID playerId) {
        return "static";
    }

    @Override
    public long getCacheDuration() {
        return Long.MAX_VALUE;
    }

    @Override
    public int getMaxLines() {
        return content.size();
    }

    @Override
    public String toString() {
        return "StaticContentProvider{" +
                "contentSize=" + content.size() +
                '}';
    }
}