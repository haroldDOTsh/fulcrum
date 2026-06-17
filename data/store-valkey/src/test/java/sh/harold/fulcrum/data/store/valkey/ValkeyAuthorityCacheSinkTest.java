package sh.harold.fulcrum.data.store.valkey;

import io.valkey.UnifiedJedis;
import io.valkey.params.SetParams;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityEmission;
import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;
import sh.harold.fulcrum.data.authority.StoredAuthorityDecision;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void idempotencyLedgerStoresFirstDecisionAndDoesNotOverwrite() {
        RecordingJedis client = new RecordingJedis();
        ValkeyIdempotencyLedger<String, String> ledger = new ValkeyIdempotencyLedger<>(
                client,
                "authority:idempotency",
                codec(),
                Optional.of(Duration.ofSeconds(30)));
        IdempotencyKey key = new IdempotencyKey("idem-valkey");

        ledger.store(key, "fingerprint-1", accepted("state-1", "response-1", 1));
        ledger.store(key, "fingerprint-2", accepted("state-2", "response-2", 2));

        StoredAuthorityDecision<String, String> stored = ledger.find(key).orElseThrow();
        assertEquals("fingerprint-1", stored.payloadFingerprint());
        assertEquals(new Revision(1), stored.decision().revision());
        assertEquals("state-1", stored.decision().state());
        assertTrue(ledger.find(new IdempotencyKey("missing")).isEmpty());
    }

    private static final class RecordingJedis extends UnifiedJedis {
        private String key = "";
        private String value = "";
        private long ttlSeconds = -1;
        private final Map<String, String> values = new LinkedHashMap<>();

        @Override
        public String set(String key, String value) {
            this.key = key;
            this.value = value;
            values.put(key, value);
            return "OK";
        }

        @Override
        public String set(String key, String value, SetParams params) {
            this.key = key;
            this.value = value;
            values.putIfAbsent(key, value);
            return "OK";
        }

        @Override
        public String get(String key) {
            return values.get(key);
        }

        @Override
        public long expire(String key, long seconds) {
            this.ttlSeconds = seconds;
            return 1L;
        }

    }

    private static ValkeyStoredAuthorityDecisionCodec<String, String> codec() {
        return new ValkeyStoredAuthorityDecisionCodec<>() {
            @Override
            public String encode(StoredAuthorityDecision<String, String> decision) {
                return String.join("|",
                        decision.payloadFingerprint(),
                        Long.toString(decision.decision().revision().value()),
                        decision.decision().state(),
                        decision.decision().response());
            }

            @Override
            public StoredAuthorityDecision<String, String> decode(String payload) {
                String[] fields = payload.split("\\|", -1);
                return new StoredAuthorityDecision<>(
                        fields[0],
                        accepted(fields[2], fields[3], Long.parseLong(fields[1])));
            }
        };
    }

    private static AuthorityDecision<String, String> accepted(String state, String response, long revision) {
        return AuthorityDecision.accepted(
                new Revision(revision),
                state,
                response,
                List.of(),
                new TraceEnvelope(
                        "trace-valkey",
                        "span-valkey",
                        Optional.empty(),
                        Instant.parse("2026-06-16T00:00:00Z"),
                        "store-valkey-test",
                        new InstanceId("instance-store-valkey-test")));
    }
}
