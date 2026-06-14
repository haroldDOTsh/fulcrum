package sh.harold.fulcrum.api.data.impl.messagebus;

public final class MessageBusAuthorityChannels {
    public static final String COMMAND = "fulcrum.authority.command";
    public static final String PROFILE_READ = "fulcrum.authority.profile.read";
    public static final String RANK_READ = "fulcrum.authority.rank.read";
    public static final String SNAPSHOT_INVALIDATION = "fulcrum.authority.snapshot.invalidate";

    private MessageBusAuthorityChannels() {
    }
}
