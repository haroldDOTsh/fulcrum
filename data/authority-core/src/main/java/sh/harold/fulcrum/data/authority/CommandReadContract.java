package sh.harold.fulcrum.data.authority;

import java.util.Objects;
import java.util.Optional;

public record CommandReadContract<T>(
        CommandReadConsistency consistency,
        Optional<ProjectionSnapshot<T>> postWriteProjection) {
    public CommandReadContract {
        consistency = Objects.requireNonNull(consistency, "consistency");
        postWriteProjection = postWriteProjection == null ? Optional.empty() : postWriteProjection;
        if (consistency == CommandReadConsistency.SYNC_READ_YOUR_WRITES && postWriteProjection.isEmpty()) {
            throw new IllegalArgumentException("sync read-your-writes contracts require a post-write projection");
        }
        if (consistency == CommandReadConsistency.ASYNC_EVENTUAL && postWriteProjection.isPresent()) {
            throw new IllegalArgumentException("async eventual contracts must not carry a post-write projection");
        }
    }

    public static <T> CommandReadContract<T> syncReadYourWrites(ProjectionSnapshot<T> postWriteProjection) {
        return new CommandReadContract<>(CommandReadConsistency.SYNC_READ_YOUR_WRITES, Optional.of(postWriteProjection));
    }

    public static <T> CommandReadContract<T> asyncEventual() {
        return new CommandReadContract<>(CommandReadConsistency.ASYNC_EVENTUAL, Optional.empty());
    }

    public boolean promisesImmediateProjectionVisibility() {
        return consistency == CommandReadConsistency.SYNC_READ_YOUR_WRITES;
    }
}
