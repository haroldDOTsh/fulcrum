package sh.harold.fulcrum.minigame.team;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.*;

public final class TeamPlanner {

    private static final NamedTextColor[] TEAM_COLORS = new NamedTextColor[]{
            NamedTextColor.RED,
            NamedTextColor.BLUE,
            NamedTextColor.GREEN,
            NamedTextColor.YELLOW,
            NamedTextColor.AQUA,
            NamedTextColor.WHITE,
            NamedTextColor.LIGHT_PURPLE,
            NamedTextColor.GRAY
    };
    private static final String[] TEAM_COLOR_NAMES = new String[]{
            "Red",
            "Blue",
            "Green",
            "Yellow",
            "Aqua",
            "White",
            "Pink",
            "Gray"
    };

    private TeamPlanner() {
    }

    public static TeamPlan plan(UUID matchId,
                                Collection<Player> players,
                                Map<String, String> slotMetadata,
                                Map<UUID, Map<String, String>> playerMetadata) {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(players, "players");

        int teamSize = parsePositiveInt(slotMetadata, "team.max");
        if (teamSize <= 0) {
            teamSize = Math.max(1, players.size());
        }

        int teamCount = parsePositiveInt(slotMetadata, "team.count");
        if (teamCount <= 0) {
            int maxPlayers = parsePositiveInt(slotMetadata, "familyMaxPlayers");
            if (maxPlayers <= 0) {
                maxPlayers = parsePositiveInt(slotMetadata, "maxPlayers");
            }
            if (maxPlayers > 0) {
                teamCount = Math.max(1, (int) Math.ceil(maxPlayers / (double) teamSize));
            }
        }
        if (teamCount <= 0) {
            teamCount = Math.max(1, (int) Math.ceil(players.size() / (double) teamSize));
        }

        List<TeamSlot> slots = createSlots(matchId, teamCount, teamSize);
        Map<Integer, TeamSlot> slotsByIndex = new HashMap<>();
        for (int i = 0; i < slots.size(); i++) {
            slotsByIndex.put(i, slots.get(i));
        }

        Map<UUID, Map<String, String>> metadataByPlayer = new HashMap<>(playerMetadata);

        // Step 1: honour explicit team indices if present
        for (Player player : players) {
            Map<String, String> metadata = metadataByPlayer.getOrDefault(player.getUniqueId(), Map.of());
            Integer explicitIndex = parseInteger(metadata.get("team.index"));
            if (explicitIndex != null && slotsByIndex.containsKey(explicitIndex)) {
                slotsByIndex.get(explicitIndex).getMembersMutable().add(player.getUniqueId());
                metadataByPlayer.remove(player.getUniqueId());
            }
        }

        // Step 2: group remaining players by party id (or solo)
        Map<String, List<UUID>> groups = new LinkedHashMap<>();
        for (Player player : players) {
            if (!metadataByPlayer.containsKey(player.getUniqueId())) {
                continue;
            }
            Map<String, String> metadata = metadataByPlayer.get(player.getUniqueId());
            String partyId = metadata != null ? metadata.get("partyId") : null;
            if (partyId == null || partyId.isBlank()) {
                partyId = "solo:" + player.getUniqueId();
            }
            groups.computeIfAbsent(partyId, key -> new ArrayList<>()).add(player.getUniqueId());
        }

        List<List<UUID>> orderedGroups = new ArrayList<>(groups.values());
        orderedGroups.sort(Comparator.comparingInt((List<UUID> group) -> group.size()).reversed());

        for (List<UUID> group : orderedGroups) {
            TeamSlot slot = selectSlotForGroup(slots, group.size());
            slot.getMembersMutable().addAll(group);
        }

        return new TeamPlan(slots);
    }

    private static List<TeamSlot> createSlots(UUID matchId, int teamCount, int teamSize) {
        List<TeamSlot> slots = new ArrayList<>(teamCount);
        for (int index = 0; index < teamCount; index++) {
            NamedTextColor color = index < TEAM_COLORS.length ? TEAM_COLORS[index] : NamedTextColor.WHITE;
            Component name;
            if (index < TEAM_COLOR_NAMES.length) {
                name = Component.text(TEAM_COLOR_NAMES[index], color);
            } else {
                char letter = (char) ('A' + Math.min(index, 25));
                name = Component.text("Team " + letter, color);
            }
            String id = "team-" + index;
            slots.add(new TeamSlot(id, name, color, teamSize));
        }
        return slots;
    }

    private static TeamSlot selectSlotForGroup(List<TeamSlot> slots, int groupSize) {
        return slots.stream()
                .filter(slot -> slot.remaining() >= groupSize)
                .min(Comparator.comparingInt(TeamSlot::remaining))
                .orElseThrow(() -> new IllegalStateException("Unable to allocate group of size " + groupSize));
    }

    private static int parsePositiveInt(Map<String, String> metadata, String key) {
        if (metadata == null || key == null) {
            return -1;
        }
        String raw = metadata.get(key);
        Integer parsed = parseInteger(raw);
        return parsed != null && parsed > 0 ? parsed : -1;
    }

    private static Integer parseInteger(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static final class TeamPlan {
        private final List<TeamSlot> slots;

        private TeamPlan(List<TeamSlot> slots) {
            this.slots = List.copyOf(slots);
        }

        public List<TeamSlot> slots() {
            return slots;
        }
    }

    public static final class TeamSlot {
        private final String id;
        private final Component displayName;
        private final NamedTextColor color;
        private final int maxMembers;
        private final List<UUID> members = new ArrayList<>();

        private TeamSlot(String id,
                         Component displayName,
                         NamedTextColor color,
                         int maxMembers) {
            this.id = id;
            this.displayName = displayName;
            this.color = color;
            this.maxMembers = maxMembers;
        }

        public String id() {
            return id;
        }

        public Component displayName() {
            return displayName;
        }

        public NamedTextColor color() {
            return color;
        }

        public int maxMembers() {
            return maxMembers;
        }

        public List<UUID> members() {
            return List.copyOf(members);
        }

        private List<UUID> getMembersMutable() {
            return members;
        }

        private int remaining() {
            return Math.max(0, maxMembers - members.size());
        }
    }
}
