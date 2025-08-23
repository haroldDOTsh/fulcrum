package sh.harold.fulcrum.api.message.scoreboard.render;

import java.util.List;
import java.util.UUID;

/**
 * Represents a fully rendered scoreboard ready for display.
 * This class contains all the processed content, title, and metadata
 * needed to display a scoreboard to a player.
 *
 * <p>RenderedScoreboard is immutable and thread-safe.
 */
public class RenderedScoreboard {

    private final UUID playerId;
    private final String scoreboardId;
    private final String title;
    private final List<String> content;
    private final long renderTime;
    private final int originalLineCount;
    private final boolean wasTruncated;

    /**
     * Creates a new RenderedScoreboard with the given parameters.
     *
     * @param playerId          the UUID of the player this scoreboard is for
     * @param scoreboardId      the ID of the scoreboard definition
     * @param title             the rendered title
     * @param content           the rendered content lines
     * @param originalLineCount the original number of lines before truncation
     * @param wasTruncated      whether the content was truncated due to line limits
     * @throws IllegalArgumentException if any required parameter is null
     */
    public RenderedScoreboard(UUID playerId, String scoreboardId, String title, List<String> content,
                              int originalLineCount, boolean wasTruncated) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (scoreboardId == null || scoreboardId.trim().isEmpty()) {
            throw new IllegalArgumentException("Scoreboard ID cannot be null or empty");
        }
        if (content == null) {
            throw new IllegalArgumentException("Content cannot be null");
        }

        this.playerId = playerId;
        this.scoreboardId = scoreboardId;
        this.title = title;
        this.content = List.copyOf(content);
        this.originalLineCount = originalLineCount;
        this.wasTruncated = wasTruncated;
        this.renderTime = System.currentTimeMillis();
    }

    /**
     * Gets the UUID of the player this scoreboard is for.
     *
     * @return the player UUID
     */
    public UUID getPlayerId() {
        return playerId;
    }

    /**
     * Gets the ID of the scoreboard definition.
     *
     * @return the scoreboard ID
     */
    public String getScoreboardId() {
        return scoreboardId;
    }

    /**
     * Gets the rendered title.
     *
     * @return the title, or null if no title is set
     */
    public String getTitle() {
        return title;
    }

    /**
     * Gets the rendered content lines.
     *
     * @return an immutable list of content lines
     */
    public List<String> getContent() {
        return content;
    }

    /**
     * Gets the time when this scoreboard was rendered.
     *
     * @return the render time in milliseconds since epoch
     */
    public long getRenderTime() {
        return renderTime;
    }

    /**
     * Gets the original number of lines before any truncation.
     *
     * @return the original line count
     */
    public int getOriginalLineCount() {
        return originalLineCount;
    }

    /**
     * Checks if the content was truncated due to line limits.
     *
     * @return true if the content was truncated, false otherwise
     */
    public boolean wasTruncated() {
        return wasTruncated;
    }

    /**
     * Gets the current number of content lines.
     *
     * @return the number of content lines
     */
    public int getLineCount() {
        return content.size();
    }

    /**
     * Checks if this scoreboard has a title.
     *
     * @return true if the scoreboard has a title, false otherwise
     */
    public boolean hasTitle() {
        return title != null && !title.trim().isEmpty();
    }

    /**
     * Checks if this scoreboard has content.
     *
     * @return true if the scoreboard has content, false otherwise
     */
    public boolean hasContent() {
        return !content.isEmpty();
    }

    /**
     * Gets the effective title for this scoreboard.
     * If no title is set, returns a default title.
     *
     * @return the effective title
     */
    public String getEffectiveTitle() {
        if (hasTitle()) {
            return title;
        }
        return "&7Scoreboard"; // Default title
    }

    /**
     * Gets the number of lines that were truncated.
     *
     * @return the number of truncated lines, or 0 if no truncation occurred
     */
    public int getTruncatedLineCount() {
        return wasTruncated ? originalLineCount - content.size() : 0;
    }

    /**
     * Checks if this scoreboard is empty (no content).
     *
     * @return true if the scoreboard is empty, false otherwise
     */
    public boolean isEmpty() {
        return content.isEmpty();
    }

    /**
     * Creates a copy of this RenderedScoreboard with a different title.
     *
     * @param newTitle the new title
     * @return a new RenderedScoreboard with the updated title
     */
    public RenderedScoreboard withTitle(String newTitle) {
        return new RenderedScoreboard(playerId, scoreboardId, newTitle, content, originalLineCount, wasTruncated);
    }

    /**
     * Creates a copy of this RenderedScoreboard with different content.
     *
     * @param newContent the new content
     * @return a new RenderedScoreboard with the updated content
     * @throws IllegalArgumentException if newContent is null
     */
    public RenderedScoreboard withContent(List<String> newContent) {
        if (newContent == null) {
            throw new IllegalArgumentException("Content cannot be null");
        }
        return new RenderedScoreboard(playerId, scoreboardId, title, newContent, originalLineCount, wasTruncated);
    }

    @Override
    public String toString() {
        return "RenderedScoreboard{" +
                "playerId=" + playerId +
                ", scoreboardId='" + scoreboardId + '\'' +
                ", hasTitle=" + hasTitle() +
                ", lineCount=" + content.size() +
                ", originalLineCount=" + originalLineCount +
                ", wasTruncated=" + wasTruncated +
                ", renderTime=" + renderTime +
                '}';
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        RenderedScoreboard that = (RenderedScoreboard) obj;
        return originalLineCount == that.originalLineCount &&
                wasTruncated == that.wasTruncated &&
                playerId.equals(that.playerId) &&
                scoreboardId.equals(that.scoreboardId) &&
                (title != null ? title.equals(that.title) : that.title == null) &&
                content.equals(that.content);
    }

    @Override
    public int hashCode() {
        int result = playerId.hashCode();
        result = 31 * result + scoreboardId.hashCode();
        result = 31 * result + (title != null ? title.hashCode() : 0);
        result = 31 * result + content.hashCode();
        result = 31 * result + originalLineCount;
        result = 31 * result + (wasTruncated ? 1 : 0);
        return result;
    }
}