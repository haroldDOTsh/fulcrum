package sh.harold.fulcrum.minigame.team;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a single team inside a minigame match.
 */
public final class MatchTeam {

    private final String id;
    private final Component displayName;
    private final NamedTextColor color;
    private final int maxMembers;
    private final Set<UUID> members = ConcurrentHashMap.newKeySet();
    private final TeamData data = new TeamData();

    public MatchTeam(String id,
                     Component displayName,
                     NamedTextColor color,
                     int maxMembers) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.color = color != null ? color : NamedTextColor.WHITE;
        this.maxMembers = Math.max(1, maxMembers);
    }

    public String getId() {
        return id;
    }

    public Component getDisplayName() {
        return displayName;
    }

    public NamedTextColor getColor() {
        return color;
    }

    public int getMaxMembers() {
        return maxMembers;
    }

    public int size() {
        return members.size();
    }

    public boolean hasRoom() {
        return size() < maxMembers;
    }

    public boolean addMember(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        if (!hasRoom() && !members.contains(playerId)) {
            return false;
        }
        return members.add(playerId);
    }

    public boolean removeMember(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        return members.remove(playerId);
    }

    public Set<UUID> members() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(members));
    }

    public TeamData data() {
        return data;
    }
}
