package sh.harold.fulcrum.minigame.defaults;

import java.util.Objects;

/**
 * Factory helpers for commonly used state implementations.
 */
public final class DefaultStates {
    private DefaultStates() {
    }

    public static PreLobbyBuilder preLobby() {
        return new PreLobbyBuilder();
    }

    public static InGameBuilder inGame() {
        return new InGameBuilder();
    }

    public static DefaultEndGameState endGame() {
        return new DefaultEndGameState();
    }

    public static final class PreLobbyBuilder {
        private final PreLobbyOptions options = new PreLobbyOptions();

        public PreLobbyBuilder countdownSeconds(int seconds) {
            options.countdownSeconds(seconds);
            return this;
        }

        public PreLobbyBuilder minimumPlayers(int players) {
            options.minimumPlayers(players);
            return this;
        }

        public PreLobbyOptions build() {
            return options;
        }
    }

    public static final class InGameBuilder {
        private InGameHandler handler = new BaseInGameHandler();

        public InGameBuilder withHandler(InGameHandler handler) {
            this.handler = Objects.requireNonNull(handler, "handler");
            return this;
        }

        public InGameHandler build() {
            return handler;
        }
    }
}
