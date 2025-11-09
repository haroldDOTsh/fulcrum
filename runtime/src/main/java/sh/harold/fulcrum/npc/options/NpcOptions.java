package sh.harold.fulcrum.npc.options;

import org.bukkit.entity.EntityType;

/**
 * Per-NPC toggles covering entity type and runtime behaviour.
 */
public final class NpcOptions {
    private final EntityType entityType;
    private final boolean silent;
    private final boolean collidable;
    private final boolean gravity;
    private final boolean lookClose;
    private final boolean glowing;
    private final boolean aiEnabled;

    private NpcOptions(Builder builder) {
        this.entityType = builder.entityType;
        this.silent = builder.silent;
        this.collidable = builder.collidable;
        this.gravity = builder.gravity;
        this.lookClose = builder.lookClose;
        this.glowing = builder.glowing;
        this.aiEnabled = builder.aiEnabled;
    }

    public static Builder builder() {
        return new Builder();
    }

    public EntityType entityType() {
        return entityType;
    }

    public boolean silent() {
        return silent;
    }

    public boolean collidable() {
        return collidable;
    }

    public boolean gravity() {
        return gravity;
    }

    public boolean lookClose() {
        return lookClose;
    }

    public boolean glowing() {
        return glowing;
    }

    public boolean aiEnabled() {
        return aiEnabled;
    }

    public static final class Builder {
        private EntityType entityType = EntityType.PLAYER;
        private boolean silent;
        private boolean collidable;
        private boolean gravity = true;
        private boolean lookClose = true;
        private boolean glowing;
        private boolean aiEnabled = true;

        private Builder() {
        }

        public Builder entityType(EntityType entityType) {
            if (entityType != null) {
                this.entityType = entityType;
            }
            return this;
        }

        public Builder silent(boolean silent) {
            this.silent = silent;
            return this;
        }

        public Builder collidable(boolean collidable) {
            this.collidable = collidable;
            return this;
        }

        public Builder gravity(boolean gravity) {
            this.gravity = gravity;
            return this;
        }

        public Builder lookClose(boolean lookClose) {
            this.lookClose = lookClose;
            return this;
        }

        public Builder glowing(boolean glowing) {
            this.glowing = glowing;
            return this;
        }

        public Builder aiEnabled(boolean aiEnabled) {
            this.aiEnabled = aiEnabled;
            return this;
        }

        public NpcOptions build() {
            return new NpcOptions(this);
        }
    }
}
