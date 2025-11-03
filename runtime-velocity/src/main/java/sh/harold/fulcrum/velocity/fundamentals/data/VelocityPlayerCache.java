package sh.harold.fulcrum.velocity.fundamentals.data;

import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.common.cache.SessionBackedPlayerCache;
import sh.harold.fulcrum.session.PlayerSessionRecord;
import sh.harold.fulcrum.velocity.session.VelocityPlayerSessionService;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;

final class VelocityPlayerCache extends SessionBackedPlayerCache {

    VelocityPlayerCache(DataAPI dataAPI, VelocityPlayerSessionService sessionService) {
        this(dataAPI, sessionService, ForkJoinPool.commonPool());
    }

    VelocityPlayerCache(DataAPI dataAPI, VelocityPlayerSessionService sessionService, Executor asyncExecutor) {
        super(dataAPI, new Access(sessionService), asyncExecutor);
    }

    private record Access(VelocityPlayerSessionService delegate) implements SessionAccess {

        @Override
        public Optional<PlayerSessionRecord> getSession(UUID playerId) {
            return delegate.getSession(playerId);
        }

        @Override
        public boolean withSession(UUID playerId, Consumer<PlayerSessionRecord> consumer) {
            return delegate.withActiveSession(playerId, consumer);
        }
    }
}
