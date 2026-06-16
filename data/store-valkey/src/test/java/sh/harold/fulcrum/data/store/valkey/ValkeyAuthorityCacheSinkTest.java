package sh.harold.fulcrum.data.store.valkey;

import io.valkey.UnifiedJedis;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.data.authority.AuthorityEmission;
import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ValkeyAuthorityCacheSinkTest {
    @Test
    void cacheWriteEmissionWritesValkeyKeyAndTtl() {
        RecordingJedis client = new RecordingJedis();

        new ValkeyAuthorityCacheSink(client, Optional.of(Duration.ofSeconds(30)))
                .publish(new AuthorityEmission(AuthorityEmissionKind.CACHE_WRITE, "cache-key", "cache-payload"));

        assertEquals("cache-key", client.key);
        assertEquals("cache-payload", client.value);
        assertEquals(30L, client.ttlSeconds);
    }

    @Test
    void nonCacheEmissionIsLeftForLogAdapter() {
        RecordingJedis client = new RecordingJedis();

        new ValkeyAuthorityCacheSink(client)
                .publish(new AuthorityEmission(AuthorityEmissionKind.EVENT, "aggregate", "event-payload"));

        assertEquals("", client.key);
        assertEquals("", client.value);
    }

    private static final class RecordingJedis extends UnifiedJedis {
        private String key = "";
        private String value = "";
        private long ttlSeconds = -1;

        @Override
        public String set(String key, String value) {
            this.key = key;
            this.value = value;
            return "OK";
        }

        @Override
        public long expire(String key, long seconds) {
            this.ttlSeconds = seconds;
            return 1L;
        }
    }
}
