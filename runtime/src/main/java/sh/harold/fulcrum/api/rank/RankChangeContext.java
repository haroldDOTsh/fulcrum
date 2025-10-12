package sh.harold.fulcrum.api.rank;

import java.util.Objects;
import java.util.UUID;

/**
 * Context information describing who initiated a rank change.
 * Used for populating audit metadata alongside the rank mutation.
 */
public record RankChangeContext(
        Executor executorType,
        String executorName,
        UUID executorUuid
) {

    public RankChangeContext {
        Objects.requireNonNull(executorType, "executorType");
        executorName = executorName != null ? executorName : executorType.name();
    }

    public static RankChangeContext ofPlayer(String executorName, UUID executorUuid) {
        return new RankChangeContext(Executor.PLAYER, executorName, executorUuid);
    }

    public static RankChangeContext ofConsole(String executorName) {
        return new RankChangeContext(Executor.CONSOLE, executorName, null);
    }

    public static RankChangeContext system() {
        return new RankChangeContext(Executor.SYSTEM, "System", null);
    }

    public enum Executor {
        PLAYER,
        CONSOLE,
        SYSTEM
    }
}
