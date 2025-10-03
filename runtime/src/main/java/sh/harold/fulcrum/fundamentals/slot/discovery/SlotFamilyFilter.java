package sh.harold.fulcrum.fundamentals.slot.discovery;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Host-level allow/deny lists for slot family advertisement
 * (see docs/slot-family-discovery-notes.md).
 */
public final class SlotFamilyFilter {
    private final Set<String> allow;
    private final Set<String> deny;

    private SlotFamilyFilter(Set<String> allow, Set<String> deny) {
        this.allow = allow;
        this.deny = deny;
    }

    public static SlotFamilyFilter allowAll() {
        return new SlotFamilyFilter(Collections.emptySet(), Collections.emptySet());
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isAllowed(String familyId) {
        Objects.requireNonNull(familyId, "familyId");
        if (!allow.isEmpty() && !allow.contains(familyId)) {
            return false;
        }
        return !deny.contains(familyId);
    }

    public Set<String> getAllow() {
        return allow;
    }

    public Set<String> getDeny() {
        return deny;
    }

    public static final class Builder {
        private final Set<String> allow = new HashSet<>();
        private final Set<String> deny = new HashSet<>();

        private Builder() {
        }

        public Builder allow(String familyId) {
            allow.add(familyId);
            return this;
        }

        public Builder allowAll(Iterable<String> familyIds) {
            if (familyIds != null) {
                familyIds.forEach(allow::add);
            }
            return this;
        }

        public Builder deny(String familyId) {
            deny.add(familyId);
            return this;
        }

        public Builder denyAll(Iterable<String> familyIds) {
            if (familyIds != null) {
                familyIds.forEach(deny::add);
            }
            return this;
        }

        public SlotFamilyFilter build() {
            return new SlotFamilyFilter(
                Collections.unmodifiableSet(new HashSet<>(allow)),
                Collections.unmodifiableSet(new HashSet<>(deny))
            );
        }
    }
}
