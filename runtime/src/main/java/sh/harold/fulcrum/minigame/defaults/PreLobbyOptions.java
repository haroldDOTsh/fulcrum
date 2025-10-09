package sh.harold.fulcrum.minigame.defaults;

import java.time.Duration;

/**
 * Configuration for the default pre-lobby state.
 */
public final class PreLobbyOptions {
    private Duration countdown = Duration.ofSeconds(60);
    private boolean mapVoteEnabled;
    private int minimumPlayers = 2;

    public Duration getCountdown() {
        return countdown;
    }

    public PreLobbyOptions countdownSeconds(int seconds) {
        this.countdown = Duration.ofSeconds(Math.max(0, seconds));
        return this;
    }

    public boolean isMapVoteEnabled() {
        return mapVoteEnabled;
    }

    public PreLobbyOptions mapVoteEnabled(boolean mapVoteEnabled) {
        this.mapVoteEnabled = mapVoteEnabled;
        return this;
    }

    public int getMinimumPlayers() {
        return minimumPlayers;
    }

    public PreLobbyOptions minimumPlayers(int minimumPlayers) {
        this.minimumPlayers = Math.max(1, minimumPlayers);
        return this;
    }
}
