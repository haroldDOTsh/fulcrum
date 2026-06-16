package sh.harold.fulcrum.standard.stats;

import sh.harold.fulcrum.api.kernel.ExperienceId;

import java.util.Objects;

public record StatsExperienceCounterId(StatsCounterId counterId, ExperienceId experienceId) {
    public StatsExperienceCounterId {
        counterId = Objects.requireNonNull(counterId, "counterId");
        experienceId = Objects.requireNonNull(experienceId, "experienceId");
    }

    public String value() {
        return counterId.value() + ":" + experienceId.value();
    }
}
