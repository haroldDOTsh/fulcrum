package sh.harold.fulcrum.api.data.authority;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Scale-first data contracts for Fulcrum's private authority plane.
 *
 * <p>These contracts are intentionally small: runtime plugins should read
 * snapshots or submit commands, not patch arbitrary shared documents.</p>
 */
public final class DataAuthority {
    public static final int COMMAND_SCHEMA_VERSION = 1;
    public static final long ANY_REVISION = -1L;

    private DataAuthority() {
    }

    public record Subject(UUID subjectId) {
        public static final String SCOPE_PREFIX = "subject:";

        public Subject {
            if (subjectId == null) {
                throw new IllegalArgumentException("subjectId is required");
            }
        }

        public static Subject player(UUID playerId) {
            return new Subject(playerId);
        }

        public String scope() {
            return SCOPE_PREFIX + subjectId;
        }
    }

    public enum RejectionReason {
        NONE,
        STALE_FENCING_TOKEN,
        STALE_REVISION,
        EXPIRED_DEADLINE,
        INVALID_ACTOR,
        INVALID_SCOPE,
        IDEMPOTENCY_CONFLICT,
        STORE_UNAVAILABLE,
        VALIDATION_FAILED
    }

    public enum ReadQuoteStatus {
        SATISFIED,
        NOT_FOUND,
        UNKNOWN_OR_STALE,
        EXPIRED,
        UNWATERMARKED,
        SCOPE_MISMATCH,
        VISIBILITY_TOKEN_MISMATCH,
        REVISION_MISMATCH,
        STALE_REVISION;

        public boolean retryable() {
            return switch (this) {
                case UNKNOWN_OR_STALE, EXPIRED, VISIBILITY_TOKEN_MISMATCH, STALE_REVISION -> true;
                default -> false;
            };
        }

        public String defaultMessage() {
            return switch (this) {
                case SATISFIED -> "snapshot satisfies read requirement";
                case NOT_FOUND -> "snapshot was not found";
                case UNKNOWN_OR_STALE -> "snapshot is unavailable before the required revision";
                case EXPIRED -> "snapshot watermark is older than the required maximum age";
                case UNWATERMARKED -> "snapshot is not authority-watermarked";
                case SCOPE_MISMATCH -> "snapshot watermark scope does not match the requested aggregate";
                case VISIBILITY_TOKEN_MISMATCH -> "snapshot watermark does not satisfy the requested visibility token";
                case REVISION_MISMATCH -> "snapshot revision does not match the watermark revision";
                case STALE_REVISION -> "snapshot is older than the required revision";
            };
        }
    }

    public enum ReadSourceTier {
        UNKNOWN,
        AUTHORITY,
        HOT_STATE,
        CACHE
    }

    public record AuthorityBootIdentity(
        String registryNodeId,
        String authorityNodeId,
        String startupReceiptFingerprint,
        long receiptCreatedAtEpochMillis,
        String principalSource,
        String readContractFingerprint
    ) {
        private static final String UNKNOWN = "unknown";

        public AuthorityBootIdentity {
            registryNodeId = normalize(registryNodeId);
            authorityNodeId = normalize(authorityNodeId);
            startupReceiptFingerprint = normalize(startupReceiptFingerprint);
            receiptCreatedAtEpochMillis = Math.max(0L, receiptCreatedAtEpochMillis);
            principalSource = normalize(principalSource);
            readContractFingerprint = normalize(readContractFingerprint);
        }

        public static AuthorityBootIdentity unknown() {
            return new AuthorityBootIdentity(UNKNOWN, UNKNOWN, UNKNOWN, 0L, UNKNOWN, UNKNOWN);
        }

        public static AuthorityBootIdentity fromPayload(Map<?, ?> raw, AuthorityBootIdentity fallback) {
            if (raw == null || raw.isEmpty()) {
                return fallback == null ? unknown() : fallback;
            }
            return new AuthorityBootIdentity(
                string(raw.get("registryNodeId")),
                string(raw.get("authorityNodeId")),
                string(raw.get("startupReceiptFingerprint")),
                longValue(raw.get("receiptCreatedAtEpochMillis"), 0L),
                string(raw.get("principalSource")),
                string(raw.get("readContractFingerprint"))
            );
        }

        public boolean known() {
            return known(registryNodeId)
                && known(authorityNodeId)
                && known(startupReceiptFingerprint)
                && known(readContractFingerprint);
        }

        public Map<String, Object> payload() {
            java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
            values.put("registryNodeId", registryNodeId);
            values.put("authorityNodeId", authorityNodeId);
            values.put("startupReceiptFingerprint", startupReceiptFingerprint);
            values.put("receiptCreatedAtEpochMillis", receiptCreatedAtEpochMillis);
            values.put("principalSource", principalSource);
            values.put("readContractFingerprint", readContractFingerprint);
            values.put("known", known());
            return Map.copyOf(values);
        }

        private static boolean known(String value) {
            return value != null && !value.isBlank() && !UNKNOWN.equalsIgnoreCase(value);
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? UNKNOWN : value.trim();
        }

        private static String string(Object value) {
            return value == null ? null : value.toString();
        }

        private static long longValue(Object value, long fallback) {
            if (value instanceof Number number) {
                return number.longValue();
            }
            return value == null || value.toString().isBlank() ? fallback : Long.parseLong(value.toString());
        }
    }

    public record ReadVisibilityToken(
        String aggregateScope,
        String stateTopic,
        String partitionKey,
        int sourcePartition,
        long sourceOffset,
        long sourceRevision,
        String eventChainHash
    ) {
        private static final String UNKNOWN = "unknown";

        public ReadVisibilityToken {
            aggregateScope = normalize(aggregateScope);
            stateTopic = normalize(stateTopic);
            partitionKey = normalize(partitionKey);
            sourcePartition = sourcePartition < 0 ? -1 : sourcePartition;
            sourceOffset = sourceOffset < 0L ? -1L : sourceOffset;
            sourceRevision = Math.max(0L, sourceRevision);
            eventChainHash = normalize(eventChainHash);
        }

        public static ReadVisibilityToken fromWatermark(SnapshotWatermark watermark) {
            return watermark == null ? null : watermark.visibilityToken();
        }

        public static ReadVisibilityToken fromPayload(Map<?, ?> raw, ReadVisibilityToken fallback) {
            if (raw == null || raw.isEmpty()) {
                return fallback;
            }
            return new ReadVisibilityToken(
                string(raw.get("aggregateScope")),
                string(raw.get("stateTopic")),
                string(raw.get("partitionKey")),
                intValue(raw.get("sourcePartition"), -1),
                longValue(raw.get("sourceOffset"), -1L),
                longValue(raw.get("sourceRevision"), 0L),
                string(raw.get("eventChainHash"))
            );
        }

        public boolean known() {
            return known(aggregateScope)
                && known(stateTopic)
                && known(partitionKey)
                && sourceRevision > 0L;
        }

        public boolean logPositioned() {
            return sourcePartition >= 0 && sourceOffset >= 0L;
        }

        public Map<String, Object> payload() {
            java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
            values.put("aggregateScope", aggregateScope);
            values.put("stateTopic", stateTopic);
            values.put("partitionKey", partitionKey);
            values.put("sourcePartition", sourcePartition);
            values.put("sourceOffset", sourceOffset);
            values.put("sourceRevision", sourceRevision);
            values.put("eventChainHash", eventChainHash);
            values.put("known", known());
            values.put("logPositioned", logPositioned());
            return Map.copyOf(values);
        }

        private static boolean known(String value) {
            return value != null && !value.isBlank() && !UNKNOWN.equalsIgnoreCase(value);
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? UNKNOWN : value.trim();
        }

        private static String string(Object value) {
            return value == null ? null : value.toString();
        }

        private static int intValue(Object value, int fallback) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            return value == null || value.toString().isBlank() ? fallback : Integer.parseInt(value.toString());
        }

        private static long longValue(Object value, long fallback) {
            if (value instanceof Number number) {
                return number.longValue();
            }
            return value == null || value.toString().isBlank() ? fallback : Long.parseLong(value.toString());
        }
    }

    public record ReadRequirement(
        long minimumRevision,
        long maxAgeMillis,
        ReadVisibilityToken visibilityToken
    ) {
        public ReadRequirement(long minimumRevision, long maxAgeMillis) {
            this(minimumRevision, maxAgeMillis, null);
        }

        public ReadRequirement {
            minimumRevision = Math.max(0L, minimumRevision);
            maxAgeMillis = maxAgeMillis < 0L ? -1L : maxAgeMillis;
            visibilityToken = visibilityToken == null || !visibilityToken.known() ? null : visibilityToken;
            if (visibilityToken != null) {
                minimumRevision = Math.max(minimumRevision, visibilityToken.sourceRevision());
            }
        }

        public static ReadRequirement eventual() {
            return new ReadRequirement(0L, -1L);
        }

        public static ReadRequirement atLeast(long minimumRevision) {
            return new ReadRequirement(minimumRevision, -1L);
        }

        public static ReadRequirement freshAtLeast(long minimumRevision, long maxAgeMillis) {
            return new ReadRequirement(minimumRevision, maxAgeMillis);
        }

        public static ReadRequirement after(ReadVisibilityToken visibilityToken) {
            return new ReadRequirement(0L, -1L, visibilityToken);
        }

        public static ReadRequirement freshAfter(ReadVisibilityToken visibilityToken, long maxAgeMillis) {
            return new ReadRequirement(0L, maxAgeMillis, visibilityToken);
        }

        public static ReadRequirement orEventual(ReadRequirement requirement) {
            return requirement == null ? eventual() : requirement;
        }

        public boolean requiresRevision() {
            return minimumRevision > 0L;
        }

        public boolean hasMaxAge() {
            return maxAgeMillis >= 0L;
        }
    }

    public record ReadProvenance(
        ReadSourceTier sourceTier,
        long cachedAtEpochMillis,
        long observedAtEpochMillis,
        long cacheAgeMillis,
        long maxAgeMillis,
        AuthorityBootIdentity authorityBoot
    ) {
        public ReadProvenance(
            ReadSourceTier sourceTier,
            long cachedAtEpochMillis,
            long observedAtEpochMillis,
            long cacheAgeMillis,
            long maxAgeMillis
        ) {
            this(
                sourceTier,
                cachedAtEpochMillis,
                observedAtEpochMillis,
                cacheAgeMillis,
                maxAgeMillis,
                AuthorityBootIdentity.unknown()
            );
        }

        public ReadProvenance {
            sourceTier = sourceTier == null ? ReadSourceTier.UNKNOWN : sourceTier;
            cachedAtEpochMillis = Math.max(0L, cachedAtEpochMillis);
            observedAtEpochMillis = Math.max(0L, observedAtEpochMillis);
            cacheAgeMillis = cacheAgeMillis < 0L ? -1L : cacheAgeMillis;
            maxAgeMillis = maxAgeMillis < 0L ? -1L : maxAgeMillis;
            authorityBoot = authorityBoot == null ? AuthorityBootIdentity.unknown() : authorityBoot;
        }

        public static ReadProvenance unknown() {
            return new ReadProvenance(ReadSourceTier.UNKNOWN, 0L, 0L, -1L, -1L);
        }

        public static ReadProvenance authority() {
            return authority(AuthorityBootIdentity.unknown());
        }

        public static ReadProvenance authority(AuthorityBootIdentity authorityBoot) {
            return new ReadProvenance(ReadSourceTier.AUTHORITY, 0L, 0L, -1L, -1L, authorityBoot);
        }

        public static ReadProvenance hotState() {
            return hotState(AuthorityBootIdentity.unknown());
        }

        public static ReadProvenance hotState(AuthorityBootIdentity authorityBoot) {
            return new ReadProvenance(ReadSourceTier.HOT_STATE, 0L, 0L, -1L, -1L, authorityBoot);
        }

        public static ReadProvenance cache(long cachedAtEpochMillis, long observedAtEpochMillis, long maxAgeMillis) {
            return cache(AuthorityBootIdentity.unknown(), cachedAtEpochMillis, observedAtEpochMillis, maxAgeMillis);
        }

        public static ReadProvenance cache(
            AuthorityBootIdentity authorityBoot,
            long cachedAtEpochMillis,
            long observedAtEpochMillis,
            long maxAgeMillis
        ) {
            long cacheAgeMillis = Math.max(0L, observedAtEpochMillis - cachedAtEpochMillis);
            return new ReadProvenance(
                ReadSourceTier.CACHE,
                cachedAtEpochMillis,
                observedAtEpochMillis,
                cacheAgeMillis,
                maxAgeMillis,
                authorityBoot
            );
        }

        public static ReadProvenance fromPayload(Map<?, ?> raw, ReadProvenance fallback) {
            if (raw == null || raw.isEmpty()) {
                return fallback == null ? unknown() : fallback;
            }
            ReadProvenance effectiveFallback = fallback == null ? unknown() : fallback;
            return new ReadProvenance(
                sourceTier(raw.get("sourceTier")),
                longValue(raw.get("cachedAtEpochMillis"), 0L),
                longValue(raw.get("observedAtEpochMillis"), 0L),
                longValue(raw.get("cacheAgeMillis"), -1L),
                longValue(raw.get("maxAgeMillis"), -1L),
                AuthorityBootIdentity.fromPayload(map(raw.get("authorityBoot")), effectiveFallback.authorityBoot())
            );
        }

        public static ReadProvenance cacheFrom(
            ReadProvenance upstream,
            long cachedAtEpochMillis,
            long observedAtEpochMillis,
            long maxAgeMillis
        ) {
            AuthorityBootIdentity authorityBoot = upstream == null
                ? AuthorityBootIdentity.unknown()
                : upstream.authorityBoot();
            return cache(authorityBoot, cachedAtEpochMillis, observedAtEpochMillis, maxAgeMillis);
        }

        public ReadProvenance withAuthorityBoot(AuthorityBootIdentity authorityBoot) {
            AuthorityBootIdentity effectiveIdentity = authorityBoot == null
                ? AuthorityBootIdentity.unknown()
                : authorityBoot;
            if (!effectiveIdentity.known() || effectiveIdentity.equals(this.authorityBoot)) {
                return this;
            }
            return new ReadProvenance(
                sourceTier,
                cachedAtEpochMillis,
                observedAtEpochMillis,
                cacheAgeMillis,
                maxAgeMillis,
                effectiveIdentity
            );
        }

        public boolean cached() {
            return sourceTier == ReadSourceTier.CACHE;
        }

        public boolean expired() {
            return cached() && maxAgeMillis >= 0L && cacheAgeMillis > maxAgeMillis;
        }

        public Map<String, Object> payload() {
            java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
            values.put("sourceTier", sourceTier.name());
            values.put("cachedAtEpochMillis", cachedAtEpochMillis);
            values.put("observedAtEpochMillis", observedAtEpochMillis);
            values.put("cacheAgeMillis", cacheAgeMillis);
            values.put("maxAgeMillis", maxAgeMillis);
            values.put("authorityBoot", authorityBoot.payload());
            values.put("cached", cached());
            values.put("expired", expired());
            return Map.copyOf(values);
        }

        private static ReadSourceTier sourceTier(Object value) {
            if (value == null || value.toString().isBlank()) {
                return ReadSourceTier.UNKNOWN;
            }
            try {
                return ReadSourceTier.valueOf(value.toString());
            } catch (IllegalArgumentException ignored) {
                return ReadSourceTier.UNKNOWN;
            }
        }

        private static long longValue(Object value, long fallback) {
            if (value instanceof Number number) {
                return number.longValue();
            }
            return value == null || value.toString().isBlank() ? fallback : Long.parseLong(value.toString());
        }

        private static Map<?, ?> map(Object value) {
            return value instanceof Map<?, ?> map ? map : Map.of();
        }
    }

    public record ProjectionDeliveryReceipt(
        String sourceProvider,
        String projectionName,
        String aggregateScope,
        String stateTopic,
        UUID sourceEventId,
        long deliveredRevision,
        long deliveredAtEpochMillis,
        int sourcePartition,
        long sourceOffset,
        String outputFingerprint,
        String lineageFingerprint
    ) {
        private static final String UNKNOWN = "unknown";

        public ProjectionDeliveryReceipt(
            String sourceProvider,
            String projectionName,
            String aggregateScope,
            String stateTopic,
            UUID sourceEventId,
            long deliveredRevision,
            long deliveredAtEpochMillis,
            String outputFingerprint,
            String lineageFingerprint
        ) {
            this(
                sourceProvider,
                projectionName,
                aggregateScope,
                stateTopic,
                sourceEventId,
                deliveredRevision,
                deliveredAtEpochMillis,
                -1,
                -1L,
                outputFingerprint,
                lineageFingerprint
            );
        }

        public ProjectionDeliveryReceipt {
            sourceProvider = normalize(sourceProvider);
            projectionName = normalize(projectionName);
            aggregateScope = normalize(aggregateScope);
            stateTopic = normalize(stateTopic);
            deliveredRevision = Math.max(0L, deliveredRevision);
            deliveredAtEpochMillis = Math.max(0L, deliveredAtEpochMillis);
            sourcePartition = sourcePartition < 0 ? -1 : sourcePartition;
            sourceOffset = sourceOffset < 0L ? -1L : sourceOffset;
            outputFingerprint = normalize(outputFingerprint);
            lineageFingerprint = normalize(lineageFingerprint);
        }

        public static ProjectionDeliveryReceipt fromWatermark(
            String projectionName,
            SnapshotWatermark watermark
        ) {
            if (watermark == null || !watermark.watermarked()) {
                return null;
            }
            return new ProjectionDeliveryReceipt(
                watermark.sourceProvider(),
                projectionName,
                watermark.aggregateScope(),
                watermark.stateTopic(),
                watermark.sourceEventId(),
                watermark.sourceRevision(),
                watermark.eventCreatedEpochMillis(),
                watermark.sourcePartition(),
                watermark.sourceOffset(),
                watermark.stateFingerprint(),
                watermark.eventChainHash()
            );
        }

        public static ProjectionDeliveryReceipt fromPayload(Map<?, ?> raw, ProjectionDeliveryReceipt fallback) {
            if (raw == null || raw.isEmpty()) {
                return fallback;
            }
            return new ProjectionDeliveryReceipt(
                string(raw.get("sourceProvider")),
                string(raw.get("projectionName")),
                string(raw.get("aggregateScope")),
                string(raw.get("stateTopic")),
                uuid(raw.get("sourceEventId")),
                longValue(raw.get("deliveredRevision"), 0L),
                longValue(raw.get("deliveredAtEpochMillis"), 0L),
                intValue(raw.get("sourcePartition"), -1),
                longValue(raw.get("sourceOffset"), -1L),
                string(raw.get("outputFingerprint")),
                string(raw.get("lineageFingerprint"))
            );
        }

        public boolean delivered() {
            return sourceEventId != null
                && deliveredRevision > 0L
                && known(outputFingerprint)
                && known(lineageFingerprint);
        }

        public boolean logPositioned() {
            return sourcePartition >= 0 && sourceOffset >= 0L;
        }

        public boolean satisfies(String expectedProjectionName, String expectedAggregateScope, long minimumRevision) {
            return delivered()
                && projectionName.equals(normalize(expectedProjectionName))
                && aggregateScope.equals(normalize(expectedAggregateScope))
                && deliveredRevision >= Math.max(0L, minimumRevision);
        }

        public Map<String, Object> payload() {
            java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
            values.put("sourceProvider", sourceProvider);
            values.put("projectionName", projectionName);
            values.put("aggregateScope", aggregateScope);
            values.put("stateTopic", stateTopic);
            if (sourceEventId != null) {
                values.put("sourceEventId", sourceEventId.toString());
            }
            values.put("deliveredRevision", deliveredRevision);
            values.put("deliveredAtEpochMillis", deliveredAtEpochMillis);
            values.put("sourcePartition", sourcePartition);
            values.put("sourceOffset", sourceOffset);
            values.put("outputFingerprint", outputFingerprint);
            values.put("lineageFingerprint", lineageFingerprint);
            values.put("delivered", delivered());
            values.put("logPositioned", logPositioned());
            return Map.copyOf(values);
        }

        private static boolean known(String value) {
            return value != null && !value.isBlank() && !UNKNOWN.equalsIgnoreCase(value);
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? UNKNOWN : value;
        }

        private static String string(Object value) {
            return value == null ? null : value.toString();
        }

        private static long longValue(Object value, long fallback) {
            if (value instanceof Number number) {
                return number.longValue();
            }
            return value == null || value.toString().isBlank() ? fallback : Long.parseLong(value.toString());
        }

        private static int intValue(Object value, int fallback) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            return value == null || value.toString().isBlank() ? fallback : Integer.parseInt(value.toString());
        }

        private static UUID uuid(Object value) {
            if (value instanceof UUID uuid) {
                return uuid;
            }
            if (value == null || value.toString().isBlank()) {
                return null;
            }
            return UUID.fromString(value.toString());
        }
    }

    public record ReadQuote(
        String aggregateScope,
        String projectionFamily,
        long requiredRevision,
        long observedRevision,
        ReadQuoteStatus status,
        SnapshotWatermark watermark,
        String message,
        ReadProvenance provenance,
        ProjectionDeliveryReceipt deliveryReceipt
    ) {
        public ReadQuote(
            String aggregateScope,
            String projectionFamily,
            long requiredRevision,
            long observedRevision,
            ReadQuoteStatus status,
            SnapshotWatermark watermark,
            String message
        ) {
            this(
                aggregateScope,
                projectionFamily,
                requiredRevision,
                observedRevision,
                status,
                watermark,
                message,
                ReadProvenance.unknown()
            );
        }

        public ReadQuote(
            String aggregateScope,
            String projectionFamily,
            long requiredRevision,
            long observedRevision,
            ReadQuoteStatus status,
            SnapshotWatermark watermark,
            String message,
            ReadProvenance provenance
        ) {
            this(
                aggregateScope,
                projectionFamily,
                requiredRevision,
                observedRevision,
                status,
                watermark,
                message,
                provenance,
                null
            );
        }

        public ReadQuote {
            aggregateScope = normalize(aggregateScope);
            projectionFamily = normalize(projectionFamily);
            requiredRevision = Math.max(0L, requiredRevision);
            observedRevision = Math.max(0L, observedRevision);
            status = Objects.requireNonNull(status, "status");
            message = message == null || message.isBlank() ? status.defaultMessage() : message;
            provenance = provenance == null ? ReadProvenance.unknown() : provenance;
        }

        public boolean satisfied() {
            return status == ReadQuoteStatus.SATISFIED;
        }

        public boolean retryable() {
            return status.retryable();
        }

        public Map<String, Object> payload() {
            java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
            values.put("aggregateScope", aggregateScope);
            values.put("projectionFamily", projectionFamily);
            values.put("requiredRevision", requiredRevision);
            values.put("observedRevision", observedRevision);
            values.put("status", status.name());
            if (watermark != null) {
                values.put("watermark", watermark.payload());
            }
            values.put("message", message);
            values.put("provenance", provenance.payload());
            if (deliveryReceipt != null) {
                values.put("deliveryReceipt", deliveryReceipt.payload());
            }
            values.put("satisfied", satisfied());
            values.put("retryable", retryable());
            return Map.copyOf(values);
        }

        public static ReadQuote fromPayload(Map<?, ?> raw, ReadQuote fallback) {
            if (raw == null || raw.isEmpty()) {
                return fallback;
            }
            ReadQuote effectiveFallback = fallback == null
                ? new ReadQuote(null, null, 0L, 0L, ReadQuoteStatus.UNKNOWN_OR_STALE, null, null)
                : fallback;
            return new ReadQuote(
                string(raw.get("aggregateScope"), effectiveFallback.aggregateScope()),
                string(raw.get("projectionFamily"), effectiveFallback.projectionFamily()),
                longValue(raw.get("requiredRevision"), effectiveFallback.requiredRevision()),
                longValue(raw.get("observedRevision"), effectiveFallback.observedRevision()),
                status(raw.get("status"), effectiveFallback.status()),
                SnapshotWatermark.fromPayload(map(raw.get("watermark")), effectiveFallback.watermark()),
                string(raw.get("message"), effectiveFallback.message()),
                ReadProvenance.fromPayload(map(raw.get("provenance")), effectiveFallback.provenance()),
                ProjectionDeliveryReceipt.fromPayload(
                    map(raw.get("deliveryReceipt")),
                    effectiveFallback.deliveryReceipt()
                )
            );
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? "unknown" : value;
        }

        private static Map<?, ?> map(Object value) {
            return value instanceof Map<?, ?> map ? map : Map.of();
        }

        private static String string(Object value, String fallback) {
            return value == null || value.toString().isBlank() ? fallback : value.toString();
        }

        private static long longValue(Object value, long fallback) {
            if (value instanceof Number number) {
                return number.longValue();
            }
            return value == null || value.toString().isBlank() ? fallback : Long.parseLong(value.toString());
        }

        private static ReadQuoteStatus status(Object value, ReadQuoteStatus fallback) {
            if (value == null || value.toString().isBlank()) {
                return fallback;
            }
            try {
                return ReadQuoteStatus.valueOf(value.toString());
            } catch (IllegalArgumentException ignored) {
                return fallback;
            }
        }
    }

    public record QuotedRead<T>(Optional<T> snapshot, ReadQuote quote) {
        public QuotedRead {
            snapshot = snapshot == null ? Optional.empty() : snapshot;
            quote = Objects.requireNonNull(quote, "quote");
            if (quote.satisfied() != snapshot.isPresent()) {
                throw new IllegalArgumentException("read quote status must match snapshot presence");
            }
        }

        public static <T> QuotedRead<T> satisfied(T snapshot, ReadQuote quote) {
            return new QuotedRead<>(Optional.of(Objects.requireNonNull(snapshot, "snapshot")), quote);
        }

        public static <T> QuotedRead<T> unsatisfied(ReadQuote quote) {
            return new QuotedRead<>(Optional.empty(), quote);
        }

        public boolean satisfied() {
            return quote.satisfied();
        }
    }

    public interface CommandPort {
        CompletionStage<CommandResult> submit(AuthorityCommand command);
    }

    public interface CommandSubmissionPort {
        CompletionStage<CommandSubmissionReceipt> submitDurable(AuthorityCommand command);
    }

    public interface PlayerProfileReader {
        CompletionStage<Optional<PlayerProfileSnapshot>> findProfile(UUID playerId);

        default CompletionStage<QuotedRead<PlayerProfileSnapshot>> quoteProfile(
            UUID playerId,
            ReadRequirement requirement
        ) {
            Objects.requireNonNull(playerId, "playerId");
            ReadRequirement effectiveRequirement = ReadRequirement.orEventual(requirement);
            return findProfile(playerId)
                .thenApply(snapshot -> quoteProfileSnapshot(
                    playerId,
                    effectiveRequirement,
                    snapshot,
                    0L,
                    System.currentTimeMillis()
                ));
        }

        default CompletionStage<Boolean> profileExists(UUID playerId) {
            return findProfile(playerId).thenApply(Optional::isPresent);
        }
    }

    public interface PlayerRankReader {
        CompletionStage<Optional<PlayerRankSnapshot>> findRanks(UUID playerId);

        default CompletionStage<QuotedRead<PlayerRankSnapshot>> quoteRanks(
            UUID playerId,
            ReadRequirement requirement
        ) {
            Objects.requireNonNull(playerId, "playerId");
            ReadRequirement effectiveRequirement = ReadRequirement.orEventual(requirement);
            return findRanks(playerId)
                .thenApply(snapshot -> quoteRankSnapshot(
                    playerId,
                    effectiveRequirement,
                    snapshot,
                    0L,
                    System.currentTimeMillis()
                ));
        }
    }

    public interface PresenceReader {
        CompletionStage<Optional<PlayerPresenceSnapshot>> findPresence(Subject subject);

        default CompletionStage<QuotedRead<PlayerPresenceSnapshot>> quotePresence(
            Subject subject,
            ReadRequirement requirement
        ) {
            Objects.requireNonNull(subject, "subject");
            ReadRequirement effectiveRequirement = ReadRequirement.orEventual(requirement);
            return findPresence(subject)
                .thenApply(snapshot -> quotePresenceSnapshot(
                    subject.subjectId(),
                    effectiveRequirement,
                    snapshot,
                    0L,
                    System.currentTimeMillis()
                ));
        }
    }

    private static QuotedRead<PlayerProfileSnapshot> quoteProfileSnapshot(
        UUID playerId,
        ReadRequirement requirement,
        Optional<PlayerProfileSnapshot> snapshot,
        long revisionFloor,
        long nowEpochMillis
    ) {
        String aggregateScope = "player:" + playerId;
        ReadRequirement effectiveRequirement = ReadRequirement.orEventual(requirement);
        long requiredRevision = Math.max(effectiveRequirement.minimumRevision(), Math.max(0L, revisionFloor));
        Optional<PlayerProfileSnapshot> effectiveSnapshot = snapshot == null ? Optional.empty() : snapshot;
        if (effectiveSnapshot.isEmpty()) {
            ReadQuoteStatus status = requiredRevision > 0L
                ? ReadQuoteStatus.UNKNOWN_OR_STALE
                : ReadQuoteStatus.NOT_FOUND;
            return QuotedRead.unsatisfied(new ReadQuote(
                aggregateScope,
                "player_profile",
                requiredRevision,
                0L,
                status,
                null,
                null,
                ReadProvenance.authority()
            ));
        }

        PlayerProfileSnapshot profileSnapshot = effectiveSnapshot.get();
        SnapshotWatermark snapshotWatermark = profileSnapshot.watermark();
        long snapshotRevision = Math.max(0L, profileSnapshot.revision());
        long observedRevision = snapshotWatermark == null
            ? snapshotRevision
            : Math.max(snapshotRevision, snapshotWatermark.sourceRevision());
        ReadQuoteStatus status;
        if (snapshotWatermark == null || !snapshotWatermark.watermarked()) {
            status = ReadQuoteStatus.UNWATERMARKED;
        } else if (!aggregateScope.equals(snapshotWatermark.aggregateScope())) {
            status = ReadQuoteStatus.SCOPE_MISMATCH;
        } else if (effectiveRequirement.visibilityToken() != null
            && !snapshotWatermark.satisfies(effectiveRequirement.visibilityToken())) {
            status = ReadQuoteStatus.VISIBILITY_TOKEN_MISMATCH;
        } else if (snapshotWatermark.sourceRevision() != snapshotRevision) {
            status = ReadQuoteStatus.REVISION_MISMATCH;
        } else if (snapshotRevision < requiredRevision || snapshotWatermark.sourceRevision() < requiredRevision) {
            status = ReadQuoteStatus.STALE_REVISION;
        } else if (effectiveRequirement.hasMaxAge()
            && snapshotWatermark.staleAt(nowEpochMillis, effectiveRequirement.maxAgeMillis())) {
            status = ReadQuoteStatus.EXPIRED;
        } else {
            status = ReadQuoteStatus.SATISFIED;
        }

        ReadQuote quote = new ReadQuote(
            aggregateScope,
            "player_profile",
            requiredRevision,
            observedRevision,
            status,
            snapshotWatermark,
            null,
            ReadProvenance.authority(),
            ProjectionDeliveryReceipt.fromWatermark("player_profile", snapshotWatermark)
        );
        return status == ReadQuoteStatus.SATISFIED
            ? QuotedRead.satisfied(profileSnapshot, quote)
            : QuotedRead.unsatisfied(quote);
    }

    private static QuotedRead<PlayerRankSnapshot> quoteRankSnapshot(
        UUID playerId,
        ReadRequirement requirement,
        Optional<PlayerRankSnapshot> snapshot,
        long revisionFloor,
        long nowEpochMillis
    ) {
        String aggregateScope = "rank:player:" + playerId;
        ReadRequirement effectiveRequirement = ReadRequirement.orEventual(requirement);
        long requiredRevision = Math.max(effectiveRequirement.minimumRevision(), Math.max(0L, revisionFloor));
        Optional<PlayerRankSnapshot> effectiveSnapshot = snapshot == null ? Optional.empty() : snapshot;
        if (effectiveSnapshot.isEmpty()) {
            ReadQuoteStatus status = requiredRevision > 0L
                ? ReadQuoteStatus.UNKNOWN_OR_STALE
                : ReadQuoteStatus.NOT_FOUND;
            return QuotedRead.unsatisfied(new ReadQuote(
                aggregateScope,
                "player_rank",
                requiredRevision,
                0L,
                status,
                null,
                null,
                ReadProvenance.authority()
            ));
        }

        PlayerRankSnapshot rankSnapshot = effectiveSnapshot.get();
        SnapshotWatermark snapshotWatermark = rankSnapshot.watermark();
        long snapshotRevision = Math.max(0L, rankSnapshot.revision());
        long observedRevision = snapshotWatermark == null
            ? snapshotRevision
            : Math.max(snapshotRevision, snapshotWatermark.sourceRevision());
        ReadQuoteStatus status;
        if (snapshotWatermark == null || !snapshotWatermark.watermarked()) {
            status = ReadQuoteStatus.UNWATERMARKED;
        } else if (!aggregateScope.equals(snapshotWatermark.aggregateScope())) {
            status = ReadQuoteStatus.SCOPE_MISMATCH;
        } else if (effectiveRequirement.visibilityToken() != null
            && !snapshotWatermark.satisfies(effectiveRequirement.visibilityToken())) {
            status = ReadQuoteStatus.VISIBILITY_TOKEN_MISMATCH;
        } else if (snapshotWatermark.sourceRevision() != snapshotRevision) {
            status = ReadQuoteStatus.REVISION_MISMATCH;
        } else if (snapshotRevision < requiredRevision || snapshotWatermark.sourceRevision() < requiredRevision) {
            status = ReadQuoteStatus.STALE_REVISION;
        } else if (effectiveRequirement.hasMaxAge()
            && snapshotWatermark.staleAt(nowEpochMillis, effectiveRequirement.maxAgeMillis())) {
            status = ReadQuoteStatus.EXPIRED;
        } else {
            status = ReadQuoteStatus.SATISFIED;
        }

        ReadQuote quote = new ReadQuote(
            aggregateScope,
            "player_rank",
            requiredRevision,
            observedRevision,
            status,
            snapshotWatermark,
            null,
            ReadProvenance.authority(),
            ProjectionDeliveryReceipt.fromWatermark("player_rank", snapshotWatermark)
        );
        return status == ReadQuoteStatus.SATISFIED
            ? QuotedRead.satisfied(rankSnapshot, quote)
            : QuotedRead.unsatisfied(quote);
    }

    private static QuotedRead<PlayerPresenceSnapshot> quotePresenceSnapshot(
        UUID subjectId,
        ReadRequirement requirement,
        Optional<PlayerPresenceSnapshot> snapshot,
        long revisionFloor,
        long nowEpochMillis
    ) {
        String aggregateScope = Subject.SCOPE_PREFIX + subjectId;
        ReadRequirement effectiveRequirement = ReadRequirement.orEventual(requirement);
        long requiredRevision = Math.max(effectiveRequirement.minimumRevision(), Math.max(0L, revisionFloor));
        Optional<PlayerPresenceSnapshot> effectiveSnapshot = snapshot == null ? Optional.empty() : snapshot;
        if (effectiveSnapshot.isEmpty()) {
            ReadQuoteStatus status = requiredRevision > 0L
                ? ReadQuoteStatus.UNKNOWN_OR_STALE
                : ReadQuoteStatus.NOT_FOUND;
            return QuotedRead.unsatisfied(new ReadQuote(
                aggregateScope,
                "presence",
                requiredRevision,
                0L,
                status,
                null,
                null,
                ReadProvenance.authority()
            ));
        }

        PlayerPresenceSnapshot presenceSnapshot = effectiveSnapshot.get();
        SnapshotWatermark snapshotWatermark = presenceSnapshot.watermark();
        long snapshotRevision = Math.max(0L, presenceSnapshot.revision());
        long observedRevision = snapshotWatermark == null
            ? snapshotRevision
            : Math.max(snapshotRevision, snapshotWatermark.sourceRevision());
        ReadQuoteStatus status;
        if (snapshotWatermark == null || !snapshotWatermark.watermarked()) {
            status = ReadQuoteStatus.UNWATERMARKED;
        } else if (!aggregateScope.equals(snapshotWatermark.aggregateScope())) {
            status = ReadQuoteStatus.SCOPE_MISMATCH;
        } else if (effectiveRequirement.visibilityToken() != null
            && !snapshotWatermark.satisfies(effectiveRequirement.visibilityToken())) {
            status = ReadQuoteStatus.VISIBILITY_TOKEN_MISMATCH;
        } else if (snapshotWatermark.sourceRevision() != snapshotRevision) {
            status = ReadQuoteStatus.REVISION_MISMATCH;
        } else if (snapshotRevision < requiredRevision || snapshotWatermark.sourceRevision() < requiredRevision) {
            status = ReadQuoteStatus.STALE_REVISION;
        } else if (effectiveRequirement.hasMaxAge()
            && snapshotWatermark.staleAt(nowEpochMillis, effectiveRequirement.maxAgeMillis())) {
            status = ReadQuoteStatus.EXPIRED;
        } else {
            status = ReadQuoteStatus.SATISFIED;
        }

        ReadQuote quote = new ReadQuote(
            aggregateScope,
            "presence",
            requiredRevision,
            observedRevision,
            status,
            snapshotWatermark,
            null,
            ReadProvenance.authority(),
            ProjectionDeliveryReceipt.fromWatermark("presence", snapshotWatermark)
        );
        return status == ReadQuoteStatus.SATISFIED
            ? QuotedRead.satisfied(presenceSnapshot, quote)
            : QuotedRead.unsatisfied(quote);
    }

    public interface AuthorityCommand {
        CommandManifest manifest();

        default UUID commandId() {
            return manifest().commandId();
        }

        default String declarationId() {
            return manifest().declarationId();
        }

        default String actorId() {
            return manifest().actorId();
        }

        default String scope() {
            return manifest().scope();
        }

        default String idempotencyKey() {
            return manifest().idempotencyKey();
        }

        default long deadlineEpochMillis() {
            return manifest().deadlineEpochMillis();
        }

        default String fencingToken() {
            return manifest().fencingToken();
        }

        default long expectedRevision() {
            return manifest().expectedRevision();
        }

        default CommandProvenance provenance() {
            return manifest().provenance();
        }
    }

    public record CommandProvenance(
        String originNode,
        String authorityRoute,
        String providerKind,
        int contractVersion,
        String verifiedPrincipal
    ) {
        private static final String UNKNOWN = "unknown";

        public CommandProvenance {
            originNode = normalize(originNode);
            authorityRoute = normalize(authorityRoute);
            providerKind = normalize(providerKind);
            contractVersion = contractVersion <= 0 ? COMMAND_SCHEMA_VERSION : contractVersion;
            verifiedPrincipal = normalize(verifiedPrincipal);
        }

        public CommandProvenance(
            String originNode,
            String authorityRoute,
            String providerKind,
            int contractVersion
        ) {
            this(originNode, authorityRoute, providerKind, contractVersion, UNKNOWN);
        }

        public static CommandProvenance unknown() {
            return new CommandProvenance(UNKNOWN, UNKNOWN, UNKNOWN, COMMAND_SCHEMA_VERSION, UNKNOWN);
        }

        public Map<String, Object> payload() {
            java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
            values.put("originNode", originNode);
            values.put("authorityRoute", authorityRoute);
            values.put("providerKind", providerKind);
            values.put("contractVersion", contractVersion);
            values.put("verifiedPrincipal", verifiedPrincipal);
            return Map.copyOf(values);
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? UNKNOWN : value;
        }
    }

    public record CommandManifest(
        UUID commandId,
        String declarationId,
        String actorId,
        String scope,
        String idempotencyKey,
        long deadlineEpochMillis,
        String fencingToken,
        long expectedRevision,
        int schemaVersion,
        CommandProvenance provenance
    ) {
        public CommandManifest {
            if (commandId == null) {
                throw new IllegalArgumentException("commandId is required");
            }
            if (declarationId == null || declarationId.isBlank()) {
                throw new IllegalArgumentException("declarationId is required");
            }
            if (actorId == null || actorId.isBlank()) {
                throw new IllegalArgumentException("actorId is required");
            }
            if (scope == null || scope.isBlank()) {
                throw new IllegalArgumentException("scope is required");
            }
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new IllegalArgumentException("idempotencyKey is required");
            }
            fencingToken = fencingToken == null ? "" : fencingToken;
            if (expectedRevision < ANY_REVISION) {
                throw new IllegalArgumentException("expectedRevision is invalid");
            }
            if (schemaVersion <= 0) {
                throw new IllegalArgumentException("schemaVersion is required");
            }
            provenance = provenance == null ? CommandProvenance.unknown() : provenance;
        }

        public CommandManifest(
            UUID commandId,
            String declarationId,
            String actorId,
            String scope,
            String idempotencyKey,
            long deadlineEpochMillis,
            String fencingToken,
            long expectedRevision,
            int schemaVersion
        ) {
            this(
                commandId,
                declarationId,
                actorId,
                scope,
                idempotencyKey,
                deadlineEpochMillis,
                fencingToken,
                expectedRevision,
                schemaVersion,
                CommandProvenance.unknown()
            );
        }

        public static CommandManifest create(
            UUID commandId,
            String declarationId,
            String actorId,
            String scope,
            String idempotencyKey,
            long deadlineEpochMillis,
            String fencingToken,
            long expectedRevision
        ) {
            return new CommandManifest(
                commandId,
                declarationId,
                actorId,
                scope,
                idempotencyKey,
                deadlineEpochMillis,
                fencingToken,
                expectedRevision,
                COMMAND_SCHEMA_VERSION
            );
        }

        public static CommandManifest create(
            UUID commandId,
            String declarationId,
            String actorId,
            String scope,
            String idempotencyKey,
            long deadlineEpochMillis,
            String fencingToken,
            long expectedRevision,
            CommandProvenance provenance
        ) {
            return new CommandManifest(
                commandId,
                declarationId,
                actorId,
                scope,
                idempotencyKey,
                deadlineEpochMillis,
                fencingToken,
                expectedRevision,
                COMMAND_SCHEMA_VERSION,
                provenance
            );
        }
    }

    public record PlayerProfileCommand(
        CommandManifest manifest,
        UUID playerId,
        String username,
        long timestampEpochMillis,
        String lastIp,
        String lastWorld,
        String lastLocation,
        String gameMode,
        Integer level,
        Float exp,
        Double health,
        Integer foodLevel,
        String playtimeStartField
    ) implements AuthorityCommand {
        public PlayerProfileCommand {
            requireManifest(manifest);
            if (playerId == null) {
                throw new IllegalArgumentException("playerId is required");
            }
            username = username == null ? "unknown" : username;
        }

        public Subject subject() {
            return Subject.player(playerId);
        }
    }

    public record PlayerSessionCommand(
        CommandManifest manifest,
        UUID playerId,
        String username,
        UUID sessionId,
        long timestampEpochMillis,
        String currentServer,
        String currentProxy,
        String lastIp,
        Integer protocolVersion,
        String disconnectReason
    ) implements AuthorityCommand {
        public PlayerSessionCommand {
            requireManifest(manifest);
            if (playerId == null) {
                throw new IllegalArgumentException("playerId is required");
            }
            username = username == null ? "unknown" : username;
        }

        public Subject subject() {
            return Subject.player(playerId);
        }
    }

    public record PlayerRankCommand(
        CommandManifest manifest,
        UUID playerId,
        String primaryRank,
        List<String> ranks
    ) implements AuthorityCommand {
        public PlayerRankCommand {
            requireManifest(manifest);
            if (playerId == null) {
                throw new IllegalArgumentException("playerId is required");
            }
            primaryRank = primaryRank == null || primaryRank.isBlank() ? "DEFAULT" : primaryRank;
            ranks = ranks == null || ranks.isEmpty() ? List.of(primaryRank) : List.copyOf(ranks);
        }
    }

    public record MatchParticipant(
        UUID playerId,
        String teamId,
        Integer placement,
        String state,
        Map<String, Object> stats
    ) {
        public MatchParticipant {
            if (playerId == null) {
                throw new IllegalArgumentException("playerId is required");
            }
            stats = stats == null ? Map.of() : Map.copyOf(stats);
        }

    }

    public record MatchCommand(
        CommandManifest manifest,
        UUID matchId,
        String familyId,
        String mapId,
        String serverId,
        String slotId,
        String state,
        Long startedAtEpochMillis,
        Long endedAtEpochMillis,
        Map<String, Object> slotMetadata,
        List<MatchParticipant> participants
    ) implements AuthorityCommand {
        public MatchCommand {
            requireManifest(manifest);
            if (matchId == null) {
                throw new IllegalArgumentException("matchId is required");
            }
            familyId = familyId == null || familyId.isBlank() ? "unknown" : familyId;
            state = state == null || state.isBlank()
                ? ("RECORD_MATCH_START".equals(manifest.declarationId()) ? "STARTED" : "ENDED")
                : state;
            slotMetadata = slotMetadata == null ? Map.of() : Map.copyOf(slotMetadata);
            participants = participants == null ? List.of() : List.copyOf(participants);
        }
    }

    private static void requireManifest(CommandManifest manifest) {
        if (manifest == null) {
            throw new IllegalArgumentException("manifest is required");
        }
    }

    public record CommandSubmissionReceipt(
        UUID commandId,
        String declarationId,
        String aggregateScope,
        String commandDomain,
        String commandTopic,
        String responseTopic,
        String eventTopic,
        String stateTopic,
        String partitionKey,
        int partition,
        long offset,
        long appendedAtEpochMillis,
        CommandProvenance provenance
    ) {
        public CommandSubmissionReceipt {
            if (commandId == null) {
                throw new IllegalArgumentException("commandId is required");
            }
            declarationId = requireText(declarationId, "declarationId");
            aggregateScope = requireText(aggregateScope, "aggregateScope");
            commandDomain = requireText(commandDomain, "commandDomain");
            commandTopic = requireText(commandTopic, "commandTopic");
            responseTopic = requireText(responseTopic, "responseTopic");
            eventTopic = requireText(eventTopic, "eventTopic");
            stateTopic = requireText(stateTopic, "stateTopic");
            partitionKey = requireText(partitionKey, "partitionKey");
            if (partition < 0) {
                throw new IllegalArgumentException("partition must be non-negative");
            }
            if (offset < 0L) {
                throw new IllegalArgumentException("offset must be non-negative");
            }
            appendedAtEpochMillis = Math.max(0L, appendedAtEpochMillis);
            provenance = provenance == null ? CommandProvenance.unknown() : provenance;
        }

        public Map<String, Object> payload() {
            java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
            values.put("commandId", commandId.toString());
            values.put("declarationId", declarationId);
            values.put("aggregateScope", aggregateScope);
            values.put("commandDomain", commandDomain);
            values.put("commandTopic", commandTopic);
            values.put("responseTopic", responseTopic);
            values.put("eventTopic", eventTopic);
            values.put("stateTopic", stateTopic);
            values.put("partitionKey", partitionKey);
            values.put("partition", partition);
            values.put("offset", offset);
            values.put("appendedAtEpochMillis", appendedAtEpochMillis);
            values.put("provenance", provenance.payload());
            return Map.copyOf(values);
        }

        private static String requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return value;
        }
    }

    public record CommandResult(
        UUID commandId,
        boolean accepted,
        long revision,
        RejectionReason rejectionReason,
        String message,
        CommandSettlement settlement,
        CommandRefusalReceipt refusalReceipt
    ) {
        public CommandResult(
            UUID commandId,
            boolean accepted,
            long revision,
            RejectionReason rejectionReason,
            String message
        ) {
            this(commandId, accepted, revision, rejectionReason, message, null, null);
        }

        public CommandResult(
            UUID commandId,
            boolean accepted,
            long revision,
            RejectionReason rejectionReason,
            String message,
            CommandSettlement settlement
        ) {
            this(commandId, accepted, revision, rejectionReason, message, settlement, null);
        }

        public CommandResult {
            if (commandId == null) {
                throw new IllegalArgumentException("commandId is required");
            }
            rejectionReason = rejectionReason == null ? RejectionReason.NONE : rejectionReason;
            message = message == null ? "" : message;
            settlement = settlement == null ? CommandSettlement.unsettled(revision) : settlement;
            refusalReceipt = accepted ? null : refusalReceipt;
        }

        public CommandResult withSettlement(CommandSettlement settlement) {
            return new CommandResult(
                commandId,
                accepted,
                revision,
                rejectionReason,
                message,
                settlement,
                refusalReceipt
            );
        }

        public CommandResult withRefusalReceipt(CommandRefusalReceipt refusalReceipt) {
            return new CommandResult(
                commandId,
                accepted,
                revision,
                rejectionReason,
                message,
                settlement,
                refusalReceipt
            );
        }
    }

    public record CommandRefusalReceipt(
        String sourceProvider,
        UUID commandId,
        String declarationId,
        String aggregateScope,
        String originNode,
        String targetNode,
        String authorityRoute,
        RejectionReason rejectionReason,
        long resultRevision,
        String contractFingerprint,
        String routeManifestFingerprint,
        String payloadHash,
        long refusedAtEpochMillis,
        String receiptFingerprint
    ) {
        private static final String UNKNOWN = "unknown";

        public CommandRefusalReceipt {
            sourceProvider = normalize(sourceProvider);
            if (commandId == null) {
                throw new IllegalArgumentException("commandId is required");
            }
            declarationId = normalize(declarationId);
            aggregateScope = normalize(aggregateScope);
            originNode = normalize(originNode);
            targetNode = normalize(targetNode);
            authorityRoute = normalize(authorityRoute);
            rejectionReason = rejectionReason == null ? RejectionReason.VALIDATION_FAILED : rejectionReason;
            if (rejectionReason == RejectionReason.NONE) {
                throw new IllegalArgumentException("refusal receipt rejectionReason must not be NONE");
            }
            resultRevision = Math.max(ANY_REVISION, resultRevision);
            contractFingerprint = normalize(contractFingerprint);
            routeManifestFingerprint = normalize(routeManifestFingerprint);
            payloadHash = normalize(payloadHash);
            refusedAtEpochMillis = Math.max(0L, refusedAtEpochMillis);

            String expectedFingerprint = fingerprint(
                sourceProvider,
                commandId,
                declarationId,
                aggregateScope,
                originNode,
                targetNode,
                authorityRoute,
                rejectionReason,
                resultRevision,
                contractFingerprint,
                routeManifestFingerprint,
                payloadHash,
                refusedAtEpochMillis
            );
            receiptFingerprint = receiptFingerprint == null || receiptFingerprint.isBlank()
                ? expectedFingerprint
                : receiptFingerprint.trim().toLowerCase(Locale.ROOT);
            if (!expectedFingerprint.equals(receiptFingerprint)) {
                throw new IllegalArgumentException("refusal receipt fingerprint does not match payload");
            }
        }

        public static CommandRefusalReceipt create(
            String sourceProvider,
            UUID commandId,
            String declarationId,
            String aggregateScope,
            String originNode,
            String targetNode,
            String authorityRoute,
            RejectionReason rejectionReason,
            long resultRevision,
            String contractFingerprint,
            String routeManifestFingerprint,
            String payloadHash,
            long refusedAtEpochMillis
        ) {
            return new CommandRefusalReceipt(
                sourceProvider,
                commandId,
                declarationId,
                aggregateScope,
                originNode,
                targetNode,
                authorityRoute,
                rejectionReason,
                resultRevision,
                contractFingerprint,
                routeManifestFingerprint,
                payloadHash,
                refusedAtEpochMillis,
                null
            );
        }

        public static CommandRefusalReceipt fromPayload(Map<?, ?> raw, CommandRefusalReceipt fallback) {
            if (raw == null || raw.isEmpty()) {
                return fallback;
            }
            return new CommandRefusalReceipt(
                string(raw.get("sourceProvider")),
                uuid(raw.get("commandId")),
                string(raw.get("declarationId")),
                string(raw.get("aggregateScope")),
                string(raw.get("originNode")),
                string(raw.get("targetNode")),
                string(raw.get("authorityRoute")),
                rejectionReason(raw.get("rejectionReason")),
                longValue(raw.get("resultRevision"), ANY_REVISION),
                string(raw.get("contractFingerprint")),
                string(raw.get("routeManifestFingerprint")),
                string(raw.get("payloadHash")),
                longValue(raw.get("refusedAtEpochMillis"), 0L),
                string(raw.get("receiptFingerprint"))
            );
        }

        public static String payloadHash(Map<?, ?> payload) {
            return sha256(canonicalJson(payload == null ? Map.of() : payload));
        }

        public boolean refused() {
            return rejectionReason != RejectionReason.NONE
                && known(declarationId)
                && known(aggregateScope)
                && known(originNode)
                && known(targetNode)
                && known(authorityRoute)
                && known(contractFingerprint)
                && known(routeManifestFingerprint)
                && known(payloadHash)
                && receiptFingerprint.matches("[0-9a-f]{64}");
        }

        public Map<String, Object> payload() {
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            values.put("sourceProvider", sourceProvider);
            values.put("commandId", commandId.toString());
            values.put("declarationId", declarationId);
            values.put("aggregateScope", aggregateScope);
            values.put("originNode", originNode);
            values.put("targetNode", targetNode);
            values.put("authorityRoute", authorityRoute);
            values.put("rejectionReason", rejectionReason.name());
            values.put("resultRevision", resultRevision);
            values.put("contractFingerprint", contractFingerprint);
            values.put("routeManifestFingerprint", routeManifestFingerprint);
            values.put("payloadHash", payloadHash);
            values.put("refusedAtEpochMillis", refusedAtEpochMillis);
            values.put("receiptFingerprint", receiptFingerprint);
            values.put("refused", refused());
            return Map.copyOf(values);
        }

        private static String fingerprint(
            String sourceProvider,
            UUID commandId,
            String declarationId,
            String aggregateScope,
            String originNode,
            String targetNode,
            String authorityRoute,
            RejectionReason rejectionReason,
            long resultRevision,
            String contractFingerprint,
            String routeManifestFingerprint,
            String payloadHash,
            long refusedAtEpochMillis
        ) {
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            values.put("sourceProvider", sourceProvider);
            values.put("commandId", commandId.toString());
            values.put("declarationId", declarationId);
            values.put("aggregateScope", aggregateScope);
            values.put("originNode", originNode);
            values.put("targetNode", targetNode);
            values.put("authorityRoute", authorityRoute);
            values.put("rejectionReason", rejectionReason.name());
            values.put("resultRevision", resultRevision);
            values.put("contractFingerprint", contractFingerprint);
            values.put("routeManifestFingerprint", routeManifestFingerprint);
            values.put("payloadHash", payloadHash);
            values.put("refusedAtEpochMillis", refusedAtEpochMillis);
            return sha256(canonicalJson(values));
        }

        private static String canonicalJson(Object value) {
            Object canonical = canonicalValue(value);
            if (canonical instanceof Map<?, ?> map) {
                StringBuilder builder = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        builder.append(',');
                    }
                    first = false;
                    builder.append(quote(entry.getKey().toString()))
                        .append(':')
                        .append(canonicalJson(entry.getValue()));
                }
                return builder.append('}').toString();
            }
            if (canonical instanceof Iterable<?> values) {
                StringBuilder builder = new StringBuilder("[");
                boolean first = true;
                for (Object item : values) {
                    if (!first) {
                        builder.append(',');
                    }
                    first = false;
                    builder.append(canonicalJson(item));
                }
                return builder.append(']').toString();
            }
            if (canonical == null) {
                return "null";
            }
            if (canonical instanceof Boolean) {
                return canonical.toString();
            }
            return quote(canonical.toString());
        }

        private static Object canonicalValue(Object value) {
            if (value instanceof Map<?, ?> map) {
                TreeMap<String, Object> sorted = new TreeMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() != null) {
                        sorted.put(entry.getKey().toString(), canonicalValue(entry.getValue()));
                    }
                }
                return sorted;
            }
            if (value instanceof Iterable<?> values) {
                ArrayList<Object> normalized = new ArrayList<>();
                for (Object item : values) {
                    normalized.add(canonicalValue(item));
                }
                return List.copyOf(normalized);
            }
            if (value instanceof Number number) {
                return canonicalNumber(number);
            }
            if (value instanceof UUID uuid) {
                return uuid.toString();
            }
            return value;
        }

        private static String canonicalNumber(Number number) {
            if (number instanceof Byte || number instanceof Short
                || number instanceof Integer || number instanceof Long) {
                return BigDecimal.valueOf(number.longValue()).toPlainString();
            }
            return BigDecimal.valueOf(number.doubleValue()).stripTrailingZeros().toPlainString();
        }

        private static String quote(String value) {
            StringBuilder builder = new StringBuilder("\"");
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                switch (character) {
                    case '"' -> builder.append("\\\"");
                    case '\\' -> builder.append("\\\\");
                    case '\b' -> builder.append("\\b");
                    case '\f' -> builder.append("\\f");
                    case '\n' -> builder.append("\\n");
                    case '\r' -> builder.append("\\r");
                    case '\t' -> builder.append("\\t");
                    default -> builder.append(character);
                }
            }
            return builder.append('"').toString();
        }

        private static String sha256(String material) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }

        private static boolean known(String value) {
            return value != null && !value.isBlank() && !UNKNOWN.equalsIgnoreCase(value);
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? UNKNOWN : value.trim();
        }

        private static String string(Object value) {
            return value == null ? null : value.toString();
        }

        private static long longValue(Object value, long fallback) {
            if (value instanceof Number number) {
                return number.longValue();
            }
            return value == null || value.toString().isBlank() ? fallback : Long.parseLong(value.toString());
        }

        private static UUID uuid(Object value) {
            if (value instanceof UUID uuid) {
                return uuid;
            }
            if (value == null || value.toString().isBlank()) {
                return null;
            }
            return UUID.fromString(value.toString());
        }

        private static RejectionReason rejectionReason(Object value) {
            if (value == null || value.toString().isBlank()) {
                return RejectionReason.VALIDATION_FAILED;
            }
            return RejectionReason.valueOf(value.toString());
        }
    }

    public record CommandSettlement(
        String sourceProvider,
        String commandDomain,
        String commandTopic,
        String responseTopic,
        String eventTopic,
        String stateTopic,
        String partitionKey,
        String fencingToken,
        String idempotencyKey,
        long expectedRevision,
        SnapshotWatermark watermark,
        Map<String, Object> statePayload
    ) {
        private static final String UNKNOWN = "unknown";

        public CommandSettlement(
            String sourceProvider,
            String commandDomain,
            String commandTopic,
            String responseTopic,
            String eventTopic,
            String stateTopic,
            String partitionKey,
            String fencingToken,
            String idempotencyKey,
            long expectedRevision,
            SnapshotWatermark watermark
        ) {
            this(
                sourceProvider,
                commandDomain,
                commandTopic,
                responseTopic,
                eventTopic,
                stateTopic,
                partitionKey,
                fencingToken,
                idempotencyKey,
                expectedRevision,
                watermark,
                Map.of()
            );
        }

        public CommandSettlement(
            String sourceProvider,
            String commandDomain,
            String commandTopic,
            String eventTopic,
            String stateTopic,
            String partitionKey,
            String fencingToken,
            String idempotencyKey,
            long expectedRevision,
            SnapshotWatermark watermark
        ) {
            this(
                sourceProvider,
                commandDomain,
                commandTopic,
                responseTopicFor(commandDomain),
                eventTopic,
                stateTopic,
                partitionKey,
                fencingToken,
                idempotencyKey,
                expectedRevision,
                watermark,
                Map.of()
            );
        }

        public CommandSettlement {
            sourceProvider = normalize(sourceProvider);
            commandDomain = normalize(commandDomain);
            commandTopic = normalize(commandTopic);
            responseTopic = normalize(responseTopic);
            eventTopic = normalize(eventTopic);
            stateTopic = normalize(stateTopic);
            partitionKey = normalize(partitionKey);
            fencingToken = normalize(fencingToken);
            idempotencyKey = normalize(idempotencyKey);
            expectedRevision = Math.max(ANY_REVISION, expectedRevision);
            watermark = watermark == null ? SnapshotWatermark.unwatermarked(
                UNKNOWN,
                UNKNOWN,
                UNKNOWN,
                0L
            ) : watermark;
            statePayload = immutablePayloadCopy(statePayload);
        }

        public static CommandSettlement unsettled(long revision) {
            return new CommandSettlement(
                UNKNOWN,
                UNKNOWN,
                UNKNOWN,
                UNKNOWN,
                UNKNOWN,
                UNKNOWN,
                UNKNOWN,
                UNKNOWN,
                UNKNOWN,
                ANY_REVISION,
                SnapshotWatermark.unwatermarked(UNKNOWN, UNKNOWN, UNKNOWN, revision),
                Map.of()
            );
        }

        public static CommandSettlement fromPayload(Map<?, ?> raw, CommandSettlement fallback) {
            if (raw == null || raw.isEmpty()) {
                return fallback;
            }
            CommandSettlement effectiveFallback = fallback == null ? unsettled(0L) : fallback;
            SnapshotWatermark watermark = SnapshotWatermark.fromPayload(
                map(raw.get("watermark")),
                effectiveFallback.watermark()
            );
            String commandDomain = string(raw.get("commandDomain"));
            return new CommandSettlement(
                string(raw.get("sourceProvider")),
                commandDomain,
                string(raw.get("commandTopic")),
                string(raw.get("responseTopic"), responseTopicFor(commandDomain)),
                string(raw.get("eventTopic")),
                string(raw.get("stateTopic")),
                string(raw.get("partitionKey")),
                string(raw.get("fencingToken")),
                string(raw.get("idempotencyKey")),
                longValue(raw.get("expectedRevision"), ANY_REVISION),
                watermark,
                payloadMap(raw.get("statePayload"))
            );
        }

        public boolean settled() {
            return known(sourceProvider)
                && known(commandDomain)
                && known(commandTopic)
                && known(responseTopic)
                && known(eventTopic)
                && known(stateTopic)
                && known(partitionKey)
                && watermark.watermarked();
        }

        public Map<String, Object> payload() {
            java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
            values.put("sourceProvider", sourceProvider);
            values.put("commandDomain", commandDomain);
            values.put("commandTopic", commandTopic);
            values.put("responseTopic", responseTopic);
            values.put("eventTopic", eventTopic);
            values.put("stateTopic", stateTopic);
            values.put("partitionKey", partitionKey);
            values.put("fencingToken", fencingToken);
            values.put("idempotencyKey", idempotencyKey);
            values.put("expectedRevision", expectedRevision);
            values.put("watermark", watermark.payload());
            values.put("statePayload", statePayload);
            values.put("settled", settled());
            return Map.copyOf(values);
        }

        private static Map<?, ?> map(Object value) {
            return value instanceof Map<?, ?> map ? map : Map.of();
        }

        private static boolean known(String value) {
            return value != null && !value.isBlank() && !UNKNOWN.equalsIgnoreCase(value);
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? UNKNOWN : value;
        }

        private static String string(Object value) {
            return value == null ? null : value.toString();
        }

        private static String string(Object value, String fallback) {
            return value == null || value.toString().isBlank() ? fallback : value.toString();
        }

        private static String responseTopicFor(String commandDomain) {
            String normalized = normalize(commandDomain);
            return known(normalized) ? "rsp." + normalized : UNKNOWN;
        }

        private static Map<String, Object> immutablePayloadCopy(Map<String, Object> values) {
            if (values == null || values.isEmpty()) {
                return Map.of();
            }
            return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values));
        }

        private static Map<String, Object> payloadMap(Object value) {
            if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
                return Map.of();
            }
            java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
            map.forEach((key, child) -> {
                if (key != null) {
                    result.put(key.toString(), normalizePayloadValue(child));
                }
            });
            return java.util.Collections.unmodifiableMap(result);
        }

        private static Object normalizePayloadValue(Object value) {
            if (value instanceof Map<?, ?> map) {
                java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
                map.forEach((key, child) -> {
                    if (key != null) {
                        result.put(key.toString(), normalizePayloadValue(child));
                    }
                });
                return java.util.Collections.unmodifiableMap(result);
            }
            if (value instanceof Iterable<?> iterable) {
                java.util.ArrayList<Object> result = new java.util.ArrayList<>();
                for (Object child : iterable) {
                    result.add(normalizePayloadValue(child));
                }
                return List.copyOf(result);
            }
            if (value instanceof Double number && Math.rint(number) == number) {
                return number.longValue();
            }
            if (value instanceof Float number && Math.rint(number) == number) {
                return number.longValue();
            }
            return value;
        }

        private static long longValue(Object value, long fallback) {
            if (value instanceof Number number) {
                return number.longValue();
            }
            return value == null || value.toString().isBlank() ? fallback : Long.parseLong(value.toString());
        }
    }

    public record SnapshotWatermark(
        String sourceProvider,
        String aggregateScope,
        String aggregateType,
        String aggregateId,
        String commandDomain,
        String stateTopic,
        String partitionKey,
        UUID sourceCommandId,
        UUID sourceEventId,
        long sourceRevision,
        long eventCreatedEpochMillis,
        int sourcePartition,
        long sourceOffset,
        String stateFingerprint,
        String eventChainHash
    ) {
        private static final String UNKNOWN = "unknown";

        public SnapshotWatermark(
            String sourceProvider,
            String aggregateScope,
            String aggregateType,
            String aggregateId,
            String commandDomain,
            String stateTopic,
            String partitionKey,
            UUID sourceCommandId,
            UUID sourceEventId,
            long sourceRevision,
            long eventCreatedEpochMillis,
            String stateFingerprint,
            String eventChainHash
        ) {
            this(
                sourceProvider,
                aggregateScope,
                aggregateType,
                aggregateId,
                commandDomain,
                stateTopic,
                partitionKey,
                sourceCommandId,
                sourceEventId,
                sourceRevision,
                eventCreatedEpochMillis,
                -1,
                -1L,
                stateFingerprint,
                eventChainHash
            );
        }

        public SnapshotWatermark {
            sourceProvider = normalize(sourceProvider);
            aggregateScope = normalize(aggregateScope);
            aggregateType = normalize(aggregateType);
            aggregateId = normalize(aggregateId);
            commandDomain = normalize(commandDomain);
            stateTopic = normalize(stateTopic);
            partitionKey = normalize(partitionKey);
            sourceRevision = Math.max(0L, sourceRevision);
            eventCreatedEpochMillis = Math.max(0L, eventCreatedEpochMillis);
            sourcePartition = sourcePartition < 0 ? -1 : sourcePartition;
            sourceOffset = sourceOffset < 0L ? -1L : sourceOffset;
            stateFingerprint = normalize(stateFingerprint);
            eventChainHash = normalize(eventChainHash);
        }

        public static SnapshotWatermark unwatermarked(
            String aggregateScope,
            String aggregateType,
            String aggregateId,
            long sourceRevision
        ) {
            return new SnapshotWatermark(
                UNKNOWN,
                aggregateScope,
                aggregateType,
                aggregateId,
                UNKNOWN,
                UNKNOWN,
                aggregateScope,
                null,
                null,
                sourceRevision,
                0L,
                -1,
                -1L,
                UNKNOWN,
                UNKNOWN
            );
        }

        public static SnapshotWatermark fromPayload(Map<?, ?> raw, SnapshotWatermark fallback) {
            if (raw == null || raw.isEmpty()) {
                return fallback;
            }
            return new SnapshotWatermark(
                string(raw.get("sourceProvider")),
                string(raw.get("aggregateScope")),
                string(raw.get("aggregateType")),
                string(raw.get("aggregateId")),
                string(raw.get("commandDomain")),
                string(raw.get("stateTopic")),
                string(raw.get("partitionKey")),
                uuid(raw.get("sourceCommandId")),
                uuid(raw.get("sourceEventId")),
                longValue(raw.get("sourceRevision"), 0L),
                longValue(raw.get("eventCreatedEpochMillis"), 0L),
                intValue(raw.get("sourcePartition"), fallback == null ? -1 : fallback.sourcePartition()),
                longValue(raw.get("sourceOffset"), fallback == null ? -1L : fallback.sourceOffset()),
                string(raw.get("stateFingerprint")),
                string(raw.get("eventChainHash"))
            );
        }

        public boolean watermarked() {
            return sourceEventId != null
                && sourceCommandId != null
                && sourceRevision > 0L
                && known(stateFingerprint)
                && known(eventChainHash)
                && known(stateTopic)
                && known(partitionKey);
        }

        public boolean logPositioned() {
            return sourcePartition >= 0 && sourceOffset >= 0L;
        }

        public ReadVisibilityToken visibilityToken() {
            return new ReadVisibilityToken(
                aggregateScope,
                stateTopic,
                partitionKey,
                sourcePartition,
                sourceOffset,
                sourceRevision,
                eventChainHash
            );
        }

        public boolean satisfies(ReadVisibilityToken token) {
            if (token == null || !token.known() || !watermarked()) {
                return token == null;
            }
            if (!aggregateScope.equals(token.aggregateScope())
                || !stateTopic.equals(token.stateTopic())
                || !partitionKey.equals(token.partitionKey())) {
                return false;
            }
            if (sourceRevision < token.sourceRevision()) {
                return false;
            }
            if (token.logPositioned()
                && (sourcePartition != token.sourcePartition() || sourceOffset < token.sourceOffset())) {
                return false;
            }
            return !known(token.eventChainHash()) || eventChainHash.equals(token.eventChainHash());
        }

        public boolean staleAt(long nowEpochMillis, long maxAgeMillis) {
            return !watermarked()
                || maxAgeMillis >= 0L
                && eventCreatedEpochMillis > 0L
                && eventCreatedEpochMillis + maxAgeMillis < nowEpochMillis;
        }

        public Map<String, Object> payload() {
            java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
            values.put("sourceProvider", sourceProvider);
            values.put("aggregateScope", aggregateScope);
            values.put("aggregateType", aggregateType);
            values.put("aggregateId", aggregateId);
            values.put("commandDomain", commandDomain);
            values.put("stateTopic", stateTopic);
            values.put("partitionKey", partitionKey);
            if (sourceCommandId != null) {
                values.put("sourceCommandId", sourceCommandId.toString());
            }
            if (sourceEventId != null) {
                values.put("sourceEventId", sourceEventId.toString());
            }
            values.put("sourceRevision", sourceRevision);
            values.put("eventCreatedEpochMillis", eventCreatedEpochMillis);
            values.put("sourcePartition", sourcePartition);
            values.put("sourceOffset", sourceOffset);
            values.put("stateFingerprint", stateFingerprint);
            values.put("eventChainHash", eventChainHash);
            values.put("watermarked", watermarked());
            values.put("logPositioned", logPositioned());
            values.put("visibilityToken", visibilityToken().payload());
            return Map.copyOf(values);
        }

        private static boolean known(String value) {
            return value != null && !value.isBlank() && !UNKNOWN.equalsIgnoreCase(value);
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? UNKNOWN : value;
        }

        private static String string(Object value) {
            return value == null ? null : value.toString();
        }

        private static long longValue(Object value, long fallback) {
            if (value instanceof Number number) {
                return number.longValue();
            }
            return value == null || value.toString().isBlank() ? fallback : Long.parseLong(value.toString());
        }

        private static int intValue(Object value, int fallback) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            return value == null || value.toString().isBlank() ? fallback : Integer.parseInt(value.toString());
        }

        private static UUID uuid(Object value) {
            if (value instanceof UUID uuid) {
                return uuid;
            }
            if (value == null || value.toString().isBlank()) {
                return null;
            }
            return UUID.fromString(value.toString());
        }
    }

    public record PlayerProfileSnapshot(
        UUID playerId,
        String username,
        String normalizedUsername,
        boolean online,
        String currentServer,
        String currentProxy,
        long totalPlaytimeMs,
        Map<String, Object> profileData,
        long revision,
        SnapshotWatermark watermark
    ) {
        public PlayerProfileSnapshot(
            UUID playerId,
            String username,
            String normalizedUsername,
            boolean online,
            String currentServer,
            String currentProxy,
            long totalPlaytimeMs,
            Map<String, Object> profileData,
            long revision
        ) {
            this(
                playerId,
                username,
                normalizedUsername,
                online,
                currentServer,
                currentProxy,
                totalPlaytimeMs,
                profileData,
                revision,
                SnapshotWatermark.unwatermarked(
                    "player:" + playerId,
                    "player_profile",
                    playerId == null ? null : playerId.toString(),
                    revision
                )
            );
        }

        public PlayerProfileSnapshot {
            if (playerId == null) {
                throw new IllegalArgumentException("playerId is required");
            }
            username = username == null ? "unknown" : username;
            normalizedUsername = normalizedUsername == null ? username.toLowerCase(Locale.ROOT) : normalizedUsername;
            profileData = profileData == null ? Map.of() : Map.copyOf(profileData);
            watermark = watermark == null
                ? SnapshotWatermark.unwatermarked("player:" + playerId, "player_profile", playerId.toString(), revision)
                : watermark;
        }
    }

    public record PlayerRankSnapshot(
        UUID playerId,
        String primaryRank,
        List<String> ranks,
        long revision,
        SnapshotWatermark watermark
    ) {
        public PlayerRankSnapshot(
            UUID playerId,
            String primaryRank,
            List<String> ranks,
            long revision
        ) {
            this(
                playerId,
                primaryRank,
                ranks,
                revision,
                SnapshotWatermark.unwatermarked(
                    "rank:player:" + playerId,
                    "player_rank",
                    playerId == null ? null : playerId.toString(),
                    revision
                )
            );
        }

        public PlayerRankSnapshot {
            if (playerId == null) {
                throw new IllegalArgumentException("playerId is required");
            }
            primaryRank = primaryRank == null || primaryRank.isBlank() ? "DEFAULT" : primaryRank;
            ranks = ranks == null || ranks.isEmpty() ? List.of(primaryRank) : List.copyOf(ranks);
            watermark = watermark == null
                ? SnapshotWatermark.unwatermarked("rank:player:" + playerId, "player_rank", playerId.toString(), revision)
                : watermark;
        }
    }

    public record PlayerPresenceSnapshot(
        UUID subjectId,
        UUID playerId,
        String username,
        boolean online,
        String currentServer,
        String currentProxy,
        UUID sessionId,
        long observedAtEpochMillis,
        long revision,
        SnapshotWatermark watermark
    ) {
        public PlayerPresenceSnapshot(
            UUID subjectId,
            UUID playerId,
            String username,
            boolean online,
            String currentServer,
            String currentProxy,
            UUID sessionId,
            long observedAtEpochMillis,
            long revision
        ) {
            this(
                subjectId,
                playerId,
                username,
                online,
                currentServer,
                currentProxy,
                sessionId,
                observedAtEpochMillis,
                revision,
                SnapshotWatermark.unwatermarked(
                    Subject.SCOPE_PREFIX + subjectId,
                    "presence",
                    subjectId == null ? null : subjectId.toString(),
                    revision
                )
            );
        }

        public PlayerPresenceSnapshot {
            if (subjectId == null) {
                throw new IllegalArgumentException("subjectId is required");
            }
            username = username == null ? "unknown" : username;
            observedAtEpochMillis = Math.max(0L, observedAtEpochMillis);
            watermark = watermark == null
                ? SnapshotWatermark.unwatermarked(
                    Subject.SCOPE_PREFIX + subjectId,
                    "presence",
                    subjectId.toString(),
                    revision
                )
                : watermark;
        }
    }
}
