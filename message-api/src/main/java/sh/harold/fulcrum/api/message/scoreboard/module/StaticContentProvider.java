package sh.harold.fulcrum.api.message.scoreboard.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Implementation of ContentProvider for static content.
 * This provider returns fixed content that does not change over time.
 *
 * <p>Static content providers are ideal for:
 * <ul>
 *   <li>Fixed information like server names or IP addresses</li>
 *   <li>Constant labels and headers</li>
 *   <li>Separator lines and formatting</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * ContentProvider provider = new StaticContentProvider(Arrays.asList(
 *     "&6&lServer Information",
 *     "&7IP: &aplay.example.com",
 *     "&7Version: &a1.20.1"
 * ));
 * }</pre>
 */
public class StaticContentProvider implements ContentProvider {

    private final List<String> content;
    private final int maxLines;

    /**
     * Creates a new StaticContentProvider with the given content.
     *
     * @param content the list of content lines
     * @throws IllegalArgumentException if content is null
     */
    public StaticContentProvider(List<String> content) {
        this(content, -1);
    }

    /**
     * Creates a new StaticContentProvider with the given content and line limit.
     *
     * @param content  the list of content lines
     * @param maxLines the maximum number of lines to return, or -1 for no limit
     * @throws IllegalArgumentException if content is null or maxLines is less than -1
     */
    public StaticContentProvider(List<String> content, int maxLines) {
        if (content == null) {
            throw new IllegalArgumentException("Content cannot be null");
        }
        if (maxLines < -1) {
            throw new IllegalArgumentException("Max lines cannot be less than -1");
        }
        this.content = new ArrayList<>(content);
        this.maxLines = maxLines;
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
        if (maxLines == -1 || content.size() <= maxLines) {
            return new ArrayList<>(content);
        }
        return new ArrayList<>(content.subList(0, maxLines));
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
    public int getMaxLines() {
        return maxLines;
    }

    @Override
    public boolean isContentAvailable(UUID playerId) {
        return !content.isEmpty();
    }

    @Override
    public String getCacheKey(UUID playerId) {
        // Static content can be cached indefinitely with a simple key
        return "static_" + content.hashCode();
    }

    @Override
    public long getCacheDuration() {
        // Static content can be cached indefinitely
        return Long.MAX_VALUE;
    }

    /**
     * Gets the original content list.
     *
     * @return a copy of the original content list
     */
    public List<String> getOriginalContent() {
        return new ArrayList<>(content);
    }

    /**
     * Gets the number of content lines.
     *
     * @return the number of content lines
     */
    public int getContentSize() {
        return content.size();
    }

    /**
     * Checks if the content is empty.
     *
     * @return true if the content is empty, false otherwise
     */
    public boolean isEmpty() {
        return content.isEmpty();
    }

    @Override
    public String toString() {
        return "StaticContentProvider{" +
                "contentSize=" + content.size() +
                ", maxLines=" + maxLines +
                '}';
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        StaticContentProvider that = (StaticContentProvider) obj;
        return maxLines == that.maxLines && content.equals(that.content);
    }

    @Override
    public int hashCode() {
        return content.hashCode() * 31 + maxLines;
    }
}