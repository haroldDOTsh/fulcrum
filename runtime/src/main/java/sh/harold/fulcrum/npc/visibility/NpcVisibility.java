package sh.harold.fulcrum.npc.visibility;

import sh.harold.fulcrum.api.rank.Rank;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Predicate wrapper controlling when an NPC is visible to a viewer.
 */
public final class NpcVisibility {
    private final Predicate<NpcVisibilityContext> predicate;

    private NpcVisibility(Predicate<NpcVisibilityContext> predicate) {
        this.predicate = predicate;
    }

    public static NpcVisibility everyone() {
        return new NpcVisibility(ctx -> true);
    }

    public static NpcVisibility when(Predicate<NpcVisibilityContext> predicate) {
        return new NpcVisibility(Objects.requireNonNull(predicate, "predicate"));
    }

    public static NpcVisibility onlyRanksAtLeast(Rank minimum) {
        Objects.requireNonNull(minimum, "minimum");
        return new NpcVisibility(ctx -> {
            Rank primary = ctx.primaryRank();
            return primary != null && primary.getPriority() >= minimum.getPriority();
        });
    }

    public static NpcVisibility anyOf(NpcVisibility... visibilities) {
        if (visibilities == null || visibilities.length == 0) {
            return everyone();
        }
        return new NpcVisibility(ctx ->
                Arrays.stream(visibilities).anyMatch(vis -> vis.test(ctx)));
    }

    public static NpcVisibility allOf(NpcVisibility... visibilities) {
        if (visibilities == null || visibilities.length == 0) {
            return everyone();
        }
        return new NpcVisibility(ctx ->
                Arrays.stream(visibilities).allMatch(vis -> vis.test(ctx)));
    }

    public static NpcVisibility whenFlagAbsent(String flagKey) {
        Objects.requireNonNull(flagKey, "flagKey");
        return when(ctx -> ctx.playerState() == null
                || !hasFlag(ctx.playerState(), flagKey));
    }

    public static NpcVisibility whenFlagPresent(String flagKey) {
        Objects.requireNonNull(flagKey, "flagKey");
        return when(ctx -> ctx.playerState() != null
                && hasFlag(ctx.playerState(), flagKey));
    }

    public static NpcVisibility ranksAnyOf(Set<Rank> allowed) {
        Objects.requireNonNull(allowed, "allowed");
        return new NpcVisibility(ctx -> {
            if (ctx.ranks() == null || ctx.ranks().isEmpty()) {
                return false;
            }
            for (Rank rank : ctx.ranks()) {
                if (allowed.contains(rank)) {
                    return true;
                }
            }
            return false;
        });
    }

    private static boolean hasFlag(sh.harold.fulcrum.session.PlayerSessionRecord record, String key) {
        Object flags = record.getExtras().get("flags");
        if (flags instanceof java.util.Map<?, ?> map) {
            Object value = map.get(key);
            if (value instanceof Boolean bool) {
                return bool;
            }
            return value != null;
        }
        return false;
    }

    public NpcVisibility and(NpcVisibility other) {
        Objects.requireNonNull(other, "other");
        return new NpcVisibility(ctx -> this.test(ctx) && other.test(ctx));
    }

    public NpcVisibility or(NpcVisibility other) {
        Objects.requireNonNull(other, "other");
        return new NpcVisibility(ctx -> this.test(ctx) || other.test(ctx));
    }

    public boolean test(NpcVisibilityContext context) {
        return predicate.test(context);
    }

    public Predicate<NpcVisibilityContext> asPredicate() {
        return predicate;
    }
}
