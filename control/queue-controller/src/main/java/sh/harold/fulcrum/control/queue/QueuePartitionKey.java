package sh.harold.fulcrum.control.queue;

import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.PoolId;

import java.util.Objects;
import java.util.Optional;

public record QueuePartitionKey(
        ExperienceId experienceId,
        Optional<String> modeId,
        PoolId poolId) {
    public QueuePartitionKey {
        experienceId = Objects.requireNonNull(experienceId, "experienceId");
        modeId = modeId == null
                ? Optional.empty()
                : modeId.map(value -> ControlQueueStrings.requireNonBlank(value, "modeId"));
        poolId = Objects.requireNonNull(poolId, "poolId");
    }

    public String canonicalValue() {
        return "experience=" + experienceId.value()
                + "|mode=" + modeId.orElse("none")
                + "|pool=" + poolId.value();
    }
}
