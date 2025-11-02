package sh.harold.fulcrum.fundamentals.playerdata.cache;

import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.common.cache.SessionBackedPlayerCache;
import sh.harold.fulcrum.fundamentals.session.PlayerSessionService;
import sh.harold.fulcrum.session.PlayerSessionRecord;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public final class RuntimePlayerCache extends SessionBackedPlayerCache {

    private final PlayerSessionService sessionService;

    public RuntimePlayerCache(DataAPI dataAPI, PlayerSessionService sessionService) {
        super(dataAPI, new ServiceAccess(sessionService));
        this.sessionService = sessionService;
    }

    public Optional<PlayerSessionRecord> reload(UUID playerId) {
        return sessionService.reload(playerId);
    }

    private record ServiceAccess(PlayerSessionService delegate) implements SessionAccess {

        @Override
            public Optional<PlayerSessionRecord> getSession(UUID playerId) {
                return delegate.getActiveSession(playerId);
            }

            @Override
            public boolean withSession(UUID playerId, Consumer<PlayerSessionRecord> consumer) {
                return delegate.withActiveSession(playerId, consumer);
            }
        }
}
