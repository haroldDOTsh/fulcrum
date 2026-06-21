package sh.harold.fulcrum.data.store.memory;

import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.runtime.AuthorityCommandDelivery;
import sh.harold.fulcrum.data.authority.runtime.AuthorityCommandSource;
import sh.harold.fulcrum.data.authority.runtime.AuthorityOffset;
import sh.harold.fulcrum.data.authority.runtime.AuthorityOffsetCommitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class InMemoryAuthorityCommandLog<C extends CommandPayload> {
    private final String source;
    private final List<AuthorityCommand<C>> commands = new ArrayList<>();
    private long committedPosition;

    public InMemoryAuthorityCommandLog(String source) {
        this.source = requireNonBlank(source, "source");
    }

    public synchronized AuthorityOffset append(AuthorityCommand<C> command) {
        Objects.requireNonNull(command, "command");
        long position = commands.size();
        commands.add(command);
        return new AuthorityOffset(source, 0, position);
    }

    public synchronized AuthorityCommandSource<C> openSource() {
        return new Source(committedPosition);
    }

    public AuthorityOffsetCommitter committer() {
        return this::commit;
    }

    public synchronized long committedPosition() {
        return committedPosition;
    }

    private synchronized Optional<AuthorityCommandDelivery<C>> poll(long position) {
        if (position >= commands.size()) {
            return Optional.empty();
        }
        AuthorityOffset offset = new AuthorityOffset(source, 0, position);
        return Optional.of(new AuthorityCommandDelivery<>(commands.get(Math.toIntExact(position)), offset));
    }

    private synchronized void commit(AuthorityOffset offset) {
        Objects.requireNonNull(offset, "offset");
        if (!source.equals(offset.source())) {
            throw new IllegalArgumentException("offset source " + offset.source() + " does not match " + source);
        }
        if (offset.partition() != 0) {
            throw new IllegalArgumentException("in-memory authority log only supports partition 0");
        }
        committedPosition = Math.max(committedPosition, offset.position() + 1);
    }

    private final class Source implements AuthorityCommandSource<C> {
        private long nextPosition;

        private Source(long nextPosition) {
            this.nextPosition = nextPosition;
        }

        @Override
        public Optional<AuthorityCommandDelivery<C>> poll() {
            Optional<AuthorityCommandDelivery<C>> delivery = InMemoryAuthorityCommandLog.this.poll(nextPosition);
            if (delivery.isPresent()) {
                nextPosition++;
            }
            return delivery;
        }
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
