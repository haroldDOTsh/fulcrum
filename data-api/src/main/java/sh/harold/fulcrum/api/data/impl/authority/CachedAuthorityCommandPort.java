package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Command-port decorator that serves repeated idempotency keys from a fast result cache.
 */
public final class CachedAuthorityCommandPort implements DataAuthority.CommandPort {
    private final DataAuthority.CommandPort delegate;
    private final CommandResultCache cache;
    private final ConcurrentMap<String, InFlightCommand> inFlight = new ConcurrentHashMap<>();

    public CachedAuthorityCommandPort(DataAuthority.CommandPort delegate, CommandResultCache cache) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override
    public CompletionStage<DataAuthority.CommandResult> submit(DataAuthority.AuthorityCommand command) {
        Objects.requireNonNull(command, "command");
        String idempotencyKey = command.idempotencyKey();
        AuthorityCommandFingerprints.Fingerprint fingerprint = AuthorityCommandFingerprints.fingerprint(command);
        String contractFingerprint = DataAuthorityCommandContracts.fingerprint();
        Optional<CachedCommandResult> cached = read(idempotencyKey);
        if (cached.isPresent()
            && cached.get().commandFingerprint().equals(fingerprint.commandFingerprint())
            && cached.get().contractFingerprint().equals(contractFingerprint)) {
            return CompletableFuture.completedFuture(cached.get().result());
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return delegate.submit(command);
        }

        InFlightCommand pending = new InFlightCommand(
            fingerprint.commandFingerprint(),
            contractFingerprint,
            new CompletableFuture<>()
        );
        InFlightCommand existing = inFlight.putIfAbsent(idempotencyKey, pending);
        if (existing != null) {
            if (existing.commandFingerprint().equals(fingerprint.commandFingerprint())
                && existing.contractFingerprint().equals(contractFingerprint)) {
                return existing.result();
            }
            return existing.result().handle((ignored, throwable) -> null)
                .thenCompose(ignored -> delegate.submit(command));
        }

        boolean cacheWriteAllowed = cached.isEmpty()
            || cached.get().commandFingerprint().equals(fingerprint.commandFingerprint());
        CompletableFuture<DataAuthority.CommandResult> resultFuture;
        try {
            resultFuture = delegate.submit(command)
                .thenApply(result -> {
                    if (cacheWriteAllowed && cacheable(result)) {
                        write(new CachedCommandResult(
                            idempotencyKey,
                            fingerprint.commandFingerprint(),
                            contractFingerprint,
                            result
                        ));
                    }
                    return result;
                })
                .toCompletableFuture();
        } catch (RuntimeException exception) {
            inFlight.remove(idempotencyKey, pending);
            pending.result().completeExceptionally(exception);
            throw exception;
        }
        resultFuture.whenComplete((result, throwable) -> {
            if (throwable == null) {
                pending.result().complete(result);
            } else {
                pending.result().completeExceptionally(throwable);
            }
            inFlight.remove(idempotencyKey, pending);
        });
        return resultFuture;
    }

    private Optional<CachedCommandResult> read(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        try {
            return cache.read(idempotencyKey);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private void write(CachedCommandResult result) {
        try {
            cache.write(result);
        } catch (RuntimeException ignored) {
            // The cache is an accelerator; the durable authority result has already been recorded.
        }
    }

    private static boolean cacheable(DataAuthority.CommandResult result) {
        return result != null && result.rejectionReason() != DataAuthority.RejectionReason.STORE_UNAVAILABLE;
    }

    public interface CommandResultCache extends AutoCloseable {
        Optional<CachedCommandResult> read(String idempotencyKey);

        /**
         * Stores a result only when the key is absent or already carries the same command fingerprint.
         */
        void write(CachedCommandResult result);

        @Override
        default void close() {
        }
    }

    public record CachedCommandResult(
        String idempotencyKey,
        String commandFingerprint,
        String contractFingerprint,
        DataAuthority.CommandResult result
    ) {
        public CachedCommandResult {
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new IllegalArgumentException("idempotencyKey is required");
            }
            if (commandFingerprint == null || commandFingerprint.isBlank()) {
                throw new IllegalArgumentException("commandFingerprint is required");
            }
            if (contractFingerprint == null || contractFingerprint.isBlank()) {
                throw new IllegalArgumentException("contractFingerprint is required");
            }
            result = Objects.requireNonNull(result, "result");
        }
    }

    private record InFlightCommand(
        String commandFingerprint,
        String contractFingerprint,
        CompletableFuture<DataAuthority.CommandResult> result
    ) {
    }
}
