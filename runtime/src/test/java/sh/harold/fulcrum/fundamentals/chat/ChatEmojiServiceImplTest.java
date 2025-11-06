package sh.harold.fulcrum.fundamentals.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.chat.ChatEmojiPack;
import sh.harold.fulcrum.api.chat.ChatEmojiPackService;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankChangeContext;
import sh.harold.fulcrum.api.rank.RankService;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ChatEmojiServiceImplTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    void defaultPlayersCanUseCorePack() {
        UUID playerId = UUID.randomUUID();
        StubRankService rankService = new StubRankService(Rank.DEFAULT);
        ChatEmojiPackService packService = new StubChatEmojiPackService();
        ChatEmojiServiceImpl service = new ChatEmojiServiceImpl(rankService, packService);

        Component result = service.apply(mockPlayer(playerId), Component.text("I :heart: this"));
        assertEquals("I ❤️ this", PLAIN.serialize(result));
    }

    @Test
    void lockedPackEmojisRequireUnlock() {
        UUID playerId = UUID.randomUUID();
        StubRankService rankService = new StubRankService(Rank.DEFAULT);
        ChatEmojiPackService packService = new StubChatEmojiPackService();
        ChatEmojiServiceImpl service = new ChatEmojiServiceImpl(rankService, packService);

        Component result = service.apply(mockPlayer(playerId), Component.text(":party:"));
        assertEquals(":party:", PLAIN.serialize(result));
    }

    @Test
    void grantingPackEnablesEmojiReplacement() {
        UUID playerId = UUID.randomUUID();
        StubRankService rankService = new StubRankService(Rank.DEFAULT);
        ChatEmojiPackService packService = new StubChatEmojiPackService();
        ChatEmojiServiceImpl service = new ChatEmojiServiceImpl(rankService, packService);

        packService.grantPack(playerId, ChatEmojiPack.CELEBRATION).join();

        Component result = service.apply(mockPlayer(playerId), Component.text(":party: time"));
        assertEquals("🎉 time", PLAIN.serialize(result));
    }

    @Test
    void staffRankAutomaticallyGetsStaffPack() {
        UUID playerId = UUID.randomUUID();
        StubRankService rankService = new StubRankService(Rank.HELPER);
        ChatEmojiPackService packService = new StubChatEmojiPackService();
        ChatEmojiServiceImpl service = new ChatEmojiServiceImpl(rankService, packService);

        Component result = service.apply(mockPlayer(playerId), Component.text(":staff: on duty"));
        assertEquals("🛡️ on duty", PLAIN.serialize(result));
    }

    private Player mockPlayer(UUID playerId) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("getUniqueId".equals(name)) {
                        return playerId;
                    }
                    if ("getName".equals(name)) {
                        return "TestPlayer";
                    }
                    if ("equals".equals(name)) {
                        Object other = args != null && args.length > 0 ? args[0] : null;
                        return proxy == other;
                    }
                    if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    }
                    if ("toString".equals(name)) {
                        return "PlayerProxy{" + playerId + "}";
                    }

                    Class<?> returnType = method.getReturnType();
                    if (returnType.equals(void.class)) {
                        return null;
                    }
                    if (returnType.equals(boolean.class)) {
                        return false;
                    }
                    if (returnType.equals(byte.class)) {
                        return (byte) 0;
                    }
                    if (returnType.equals(short.class)) {
                        return (short) 0;
                    }
                    if (returnType.equals(int.class)) {
                        return 0;
                    }
                    if (returnType.equals(long.class)) {
                        return 0L;
                    }
                    if (returnType.equals(float.class)) {
                        return 0F;
                    }
                    if (returnType.equals(double.class)) {
                        return 0D;
                    }
                    if (returnType.equals(char.class)) {
                        return '\0';
                    }
                    return null;
                }
        );
    }

    private static final class StubRankService implements RankService {
        private Rank rank;

        private StubRankService(Rank rank) {
            this.rank = rank;
        }

        private Rank effective() {
            return rank != null ? rank : Rank.DEFAULT;
        }

        @Override
        public CompletableFuture<Rank> getPrimaryRank(UUID playerId) {
            return CompletableFuture.completedFuture(effective());
        }

        @Override
        public Rank getPrimaryRankSync(UUID playerId) {
            return effective();
        }

        @Override
        public CompletableFuture<Set<Rank>> getAllRanks(UUID playerId) {
            return CompletableFuture.completedFuture(Set.of(effective()));
        }

        @Override
        public CompletableFuture<Void> setPrimaryRank(UUID playerId, Rank rank, RankChangeContext context) {
            this.rank = rank;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> addRank(UUID playerId, Rank rank, RankChangeContext context) {
            this.rank = rank;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> removeRank(UUID playerId, Rank rank, RankChangeContext context) {
            this.rank = Rank.DEFAULT;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Rank> getEffectiveRank(UUID playerId) {
            return CompletableFuture.completedFuture(effective());
        }

        @Override
        public Rank getEffectiveRankSync(UUID playerId) {
            return effective();
        }

        @Override
        public CompletableFuture<Boolean> hasRank(UUID playerId, Rank rank) {
            return CompletableFuture.completedFuture(effective() == rank);
        }

        @Override
        public CompletableFuture<Boolean> isStaff(UUID playerId) {
            return CompletableFuture.completedFuture(effective().isStaff());
        }

        @Override
        public CompletableFuture<Void> resetRanks(UUID playerId, RankChangeContext context) {
            this.rank = Rank.DEFAULT;
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class StubChatEmojiPackService implements ChatEmojiPackService {
        private final Map<UUID, EnumSet<ChatEmojiPack>> unlocked = new HashMap<>();

        @Override
        public Set<ChatEmojiPack> getUnlockedPacks(UUID playerId) {
            EnumSet<ChatEmojiPack> packs = ChatEmojiPack.createDefaultUnlocked();
            EnumSet<ChatEmojiPack> extras = unlocked.get(playerId);
            if (extras != null) {
                packs.addAll(extras);
            }
            return packs;
        }

        @Override
        public CompletableFuture<Void> grantPack(UUID playerId, ChatEmojiPack pack) {
            if (playerId == null || pack == null) {
                return CompletableFuture.completedFuture(null);
            }
            unlocked.computeIfAbsent(playerId, ignored -> EnumSet.noneOf(ChatEmojiPack.class)).add(pack);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> revokePack(UUID playerId, ChatEmojiPack pack) {
            if (playerId == null || pack == null) {
                return CompletableFuture.completedFuture(null);
            }
            EnumSet<ChatEmojiPack> packs = unlocked.get(playerId);
            if (packs != null) {
                packs.remove(pack);
                if (packs.isEmpty()) {
                    unlocked.remove(playerId);
                }
            }
            return CompletableFuture.completedFuture(null);
        }
    }
}
