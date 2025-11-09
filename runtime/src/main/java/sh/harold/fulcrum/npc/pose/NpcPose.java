package sh.harold.fulcrum.npc.pose;

/**
 * Describes the static pose applied to a spawned NPC.
 */
public final class NpcPose {

    private final boolean sitting;
    private final boolean sneaking;
    private final boolean lookAtPlayers;
    private final boolean lockYaw;
    private final float yawOffset;
    private final float pitchOffset;

    private NpcPose(Builder builder) {
        this.sitting = builder.sitting;
        this.sneaking = builder.sneaking;
        this.lookAtPlayers = builder.lookAtPlayers;
        this.lockYaw = builder.lockYaw;
        this.yawOffset = builder.yawOffset;
        this.pitchOffset = builder.pitchOffset;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static NpcPose standing() {
        return builder().build();
    }

    public static NpcPose sitting() {
        return builder().sitting(true).build();
    }

    public boolean isSitting() {
        return sitting;
    }

    public boolean isSneaking() {
        return sneaking;
    }

    public boolean lookAtPlayers() {
        return lookAtPlayers;
    }

    public boolean lockYaw() {
        return lockYaw;
    }

    public float yawOffset() {
        return yawOffset;
    }

    public float pitchOffset() {
        return pitchOffset;
    }

    public Builder toBuilder() {
        return builder()
                .sitting(sitting)
                .sneaking(sneaking)
                .lookAtPlayers(lookAtPlayers)
                .lockYaw(lockYaw)
                .yawOffset(yawOffset)
                .pitchOffset(pitchOffset);
    }

    @Override
    public String toString() {
        return "NpcPose{" +
                "sitting=" + sitting +
                ", sneaking=" + sneaking +
                ", lookAtPlayers=" + lookAtPlayers +
                ", lockYaw=" + lockYaw +
                ", yawOffset=" + yawOffset +
                ", pitchOffset=" + pitchOffset +
                '}';
    }

    public static final class Builder {
        private boolean sitting;
        private boolean sneaking;
        private boolean lookAtPlayers = true;
        private boolean lockYaw;
        private float yawOffset;
        private float pitchOffset;

        private Builder() {
        }

        private static float normalizeDegrees(float degrees) {
            float normalized = degrees % 360.0F;
            if (normalized < 0) {
                normalized += 360.0F;
            }
            return normalized;
        }

        public Builder sitting(boolean sitting) {
            this.sitting = sitting;
            if (sitting) {
                this.sneaking = false;
            }
            return this;
        }

        public Builder sneaking(boolean sneaking) {
            this.sneaking = sneaking;
            if (sneaking) {
                this.sitting = false;
            }
            return this;
        }

        public Builder lookAtPlayers(boolean lookAtPlayers) {
            this.lookAtPlayers = lookAtPlayers;
            return this;
        }

        public Builder lockYaw(boolean lockYaw) {
            this.lockYaw = lockYaw;
            return this;
        }

        public Builder yawOffset(float yawOffset) {
            this.yawOffset = normalizeDegrees(yawOffset);
            return this;
        }

        public Builder pitchOffset(float pitchOffset) {
            if (pitchOffset < -90.0F || pitchOffset > 90.0F) {
                throw new IllegalArgumentException("Pitch offset must be between -90 and 90 degrees");
            }
            this.pitchOffset = pitchOffset;
            return this;
        }

        public NpcPose build() {
            return new NpcPose(this);
        }
    }
}
