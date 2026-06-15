package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Executable read contract manifest for authority snapshot request frames.
 */
public final class DataAuthorityReadContracts {
    public static final long DEFAULT_CACHE_MAX_AGE_MILLIS = 1_000L;

    private static final int READ_SCHEMA_VERSION = DataAuthority.COMMAND_SCHEMA_VERSION;
    private static final Map<ReadType, ReadContract> CONTRACTS = contracts();
    private static final String FINGERPRINT = fingerprint(CONTRACTS);

    private DataAuthorityReadContracts() {
    }

    public static int schemaVersion() {
        return READ_SCHEMA_VERSION;
    }

    public static String fingerprint() {
        return FINGERPRINT;
    }

    public static Map<ReadType, ReadContract> all() {
        return CONTRACTS;
    }

    public static long defaultCacheMaxAgeMillis() {
        return CONTRACTS.values().stream()
            .mapToLong(ReadContract::defaultCacheMaxAgeMillis)
            .max()
            .orElse(DEFAULT_CACHE_MAX_AGE_MILLIS);
    }

    public static ReadContract contract(ReadType type) {
        ReadContract contract = CONTRACTS.get(type);
        if (contract == null) {
            throw new IllegalArgumentException("No authority read contract for " + type);
        }
        return contract;
    }

    public static List<String> expectedStateTopics(ReadType type) {
        return expectedStateTopics(contract(type).projectionFamily());
    }

    public static boolean stateTopicMatches(String projectionName, String stateTopic) {
        return expectedStateTopics(projectionName).contains(stateTopic);
    }

    public static List<String> expectedStateTopics(String projectionName) {
        if ("player_profile".equals(projectionName)) {
            return List.of("state.player_profile", "state.player");
        }
        if ("player_rank".equals(projectionName)) {
            return List.of("state.player_rank", "state.rank");
        }
        return List.of("state." + projectionName);
    }

    public static DataAuthority.ReadRequirement effectiveRequirement(
        ReadType type,
        DataAuthority.ReadRequirement requirement
    ) {
        ReadContract contract = contract(type);
        DataAuthority.ReadRequirement requested = DataAuthority.ReadRequirement.orEventual(requirement);
        return new DataAuthority.ReadRequirement(
            Math.max(requested.minimumRevision(), contract.minimumRevisionFloor()),
            requested.maxAgeMillis(),
            requested.visibilityToken()
        );
    }

    public static Map<String, Object> payload(ReadType type, Map<String, Object> values) {
        Objects.requireNonNull(type, "type");
        LinkedHashMap<String, Object> stamped = new LinkedHashMap<>();
        if (values != null) {
            stamped.putAll(values);
        }
        stamped.put("readType", type.name());
        stamped.put("schemaVersion", READ_SCHEMA_VERSION);
        stamped.put("contractFingerprint", FINGERPRINT);
        return Map.copyOf(stamped);
    }

    public static void validateQuote(
        DataAuthority.ReadQuote quote,
        String expectedAggregateScope,
        String expectedProjectionFamily,
        DataAuthority.ReadRequirement requirement
    ) {
        Objects.requireNonNull(quote, "quote");
        requireQuoteField("aggregateScope", expectedAggregateScope, quote.aggregateScope());
        requireQuoteField("projectionFamily", expectedProjectionFamily, quote.projectionFamily());

        long minimumRevision = DataAuthority.ReadRequirement.orEventual(requirement).minimumRevision();
        if (quote.requiredRevision() < minimumRevision) {
            throw new IllegalStateException(
                "Authority read quote requiredRevision is below request: expected at least "
                    + minimumRevision + " but received " + quote.requiredRevision()
            );
        }
        DataAuthority.ReadVisibilityToken visibilityToken =
            DataAuthority.ReadRequirement.orEventual(requirement).visibilityToken();
        if (visibilityToken != null
            && quote.satisfied()
            && (quote.watermark() == null || !quote.watermark().satisfies(visibilityToken))) {
            throw new IllegalStateException("Authority read quote watermark does not satisfy visibility token");
        }

        long receiptMinimumRevision = Math.max(minimumRevision, quote.requiredRevision());
        DataAuthority.ProjectionDeliveryReceipt receipt = quote.deliveryReceipt();
        if (receipt != null) {
            validateDeliveryReceipt(receipt, expectedProjectionFamily, expectedAggregateScope, receiptMinimumRevision);
        } else if (quote.satisfied()) {
            throw new IllegalStateException("Authority read quote deliveryReceipt is required for satisfied reads");
        }
    }

    public static String rejection(ReadType type, Map<String, Object> payload) {
        Objects.requireNonNull(type, "type");
        ReadContract contract = contract(type);
        Map<String, Object> safePayload = payload == null ? Map.of() : payload;

        String actualFingerprint = string(safePayload.get("contractFingerprint"));
        if (!FINGERPRINT.equals(actualFingerprint)) {
            return "Authority read contract fingerprint mismatch: expected " + shortFingerprint(FINGERPRINT)
                + " but received " + shortFingerprint(actualFingerprint);
        }

        int schemaVersion;
        try {
            schemaVersion = intValue(safePayload.get("schemaVersion"), -1);
        } catch (RuntimeException exception) {
            return "Authority read contract schema version is invalid";
        }
        if (schemaVersion != READ_SCHEMA_VERSION) {
            return "Authority read contract schema version " + schemaVersion
                + " is not supported by authority read contract version " + READ_SCHEMA_VERSION;
        }

        String actualType = string(safePayload.get("readType"));
        if (!type.name().equals(actualType)) {
            return "Authority read contract type mismatch: expected " + type.name()
                + " but received " + (actualType == null ? "<missing>" : actualType);
        }

        for (String requiredField : contract.requiredFields()) {
            Object value = safePayload.get(requiredField);
            if (value == null || value.toString().isBlank()) {
                return "Authority read contract " + type + " is missing required field " + requiredField;
            }
        }

        for (String field : safePayload.keySet()) {
            if (!contract.allowedFields().contains(field)) {
                return "Authority read contract " + type + " field " + field + " is not in the read contract";
            }
        }
        return null;
    }

    private static void validateDeliveryReceipt(
        DataAuthority.ProjectionDeliveryReceipt receipt,
        String expectedProjectionName,
        String expectedAggregateScope,
        long minimumRevision
    ) {
        requireQuoteField("deliveryReceipt.projectionName", expectedProjectionName, receipt.projectionName());
        requireQuoteField("deliveryReceipt.aggregateScope", expectedAggregateScope, receipt.aggregateScope());
        if (!stateTopicMatches(expectedProjectionName, receipt.stateTopic())) {
            throw new IllegalStateException(
                "Authority read quote deliveryReceipt.stateTopic mismatch: expected one of "
                    + expectedStateTopics(expectedProjectionName) + " but received " + receipt.stateTopic()
            );
        }
        if (!receipt.delivered()) {
            throw new IllegalStateException("Authority read deliveryReceipt is not delivered");
        }
        if (receipt.deliveredRevision() < minimumRevision) {
            throw new IllegalStateException(
                "Authority read deliveryReceipt deliveredRevision is below request: expected at least "
                    + minimumRevision + " but received " + receipt.deliveredRevision()
            );
        }
    }

    private static void requireQuoteField(String field, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalStateException(
                "Authority read quote " + field + " mismatch: expected "
                    + expected + " but received " + actual
            );
        }
    }

    private static Map<ReadType, ReadContract> contracts() {
        EnumMap<ReadType, ReadContract> values = new EnumMap<>(ReadType.class);
        values.put(ReadType.PLAYER_PROFILE, new ReadContract(
            ReadType.PLAYER_PROFILE,
            "player_profile",
            "postgresql-read-replica",
            "valkey",
            0L,
            DEFAULT_CACHE_MAX_AGE_MILLIS,
            Set.of("readType", "schemaVersion", "contractFingerprint", "playerId", "minimumRevision", "maxAgeMillis"),
            Set.of(
                "readType",
                "schemaVersion",
                "contractFingerprint",
                "playerId",
                "minimumRevision",
                "maxAgeMillis",
                "visibilityToken"
            )
        ));
        values.put(ReadType.PLAYER_RANK, new ReadContract(
            ReadType.PLAYER_RANK,
            "player_rank",
            "cassandra",
            "valkey",
            0L,
            DEFAULT_CACHE_MAX_AGE_MILLIS,
            Set.of("readType", "schemaVersion", "contractFingerprint", "playerId", "minimumRevision", "maxAgeMillis"),
            Set.of(
                "readType",
                "schemaVersion",
                "contractFingerprint",
                "playerId",
                "minimumRevision",
                "maxAgeMillis",
                "visibilityToken"
            )
        ));
        return Map.copyOf(values);
    }

    private static String fingerprint(Map<ReadType, ReadContract> contracts) {
        StringBuilder material = new StringBuilder()
            .append("readSchemaVersion=").append(READ_SCHEMA_VERSION).append('\n')
            .append("satisfiedRequiresDeliveryReceipt=true").append('\n')
            .append("deliveryReceiptStateTopicBound=true").append('\n');
        contracts.values().stream()
            .sorted((left, right) -> left.type().name().compareTo(right.type().name()))
            .forEach(contract -> material
                .append(contract.type().name()).append('|')
                .append(contract.projectionFamily()).append('|')
                .append("servingStore=").append(contract.servingStore()).append('|')
                .append("cacheStore=").append(contract.cacheStore()).append('|')
                .append("minimumRevisionFloor=").append(contract.minimumRevisionFloor()).append('|')
                .append("defaultCacheMaxAgeMillis=").append(contract.defaultCacheMaxAgeMillis()).append('|')
                .append(String.join(",", contract.requiredFields().stream().sorted().toList())).append('|')
                .append(String.join(",", contract.allowedFields().stream().sorted().toList()))
                .append('\n'));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to fingerprint authority read contracts", exception);
        }
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null || value.toString().isBlank() ? fallback : Integer.parseInt(value.toString());
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static String shortFingerprint(String value) {
        if (value == null || value.isBlank()) {
            return "<missing>";
        }
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    public enum ReadType {
        PLAYER_PROFILE,
        PLAYER_RANK
    }

    public record ReadContract(
        ReadType type,
        String projectionFamily,
        String servingStore,
        String cacheStore,
        long minimumRevisionFloor,
        long defaultCacheMaxAgeMillis,
        Set<String> requiredFields,
        Set<String> allowedFields
    ) {
        public ReadContract {
            type = Objects.requireNonNull(type, "type");
            if (projectionFamily == null || projectionFamily.isBlank()) {
                throw new IllegalArgumentException("projectionFamily is required");
            }
            if (servingStore == null || servingStore.isBlank()) {
                throw new IllegalArgumentException("servingStore is required");
            }
            if (cacheStore == null || cacheStore.isBlank()) {
                throw new IllegalArgumentException("cacheStore is required");
            }
            minimumRevisionFloor = Math.max(0L, minimumRevisionFloor);
            defaultCacheMaxAgeMillis = defaultCacheMaxAgeMillis < 0L ? -1L : defaultCacheMaxAgeMillis;
            requiredFields = requiredFields == null ? Set.of() : Set.copyOf(requiredFields);
            allowedFields = allowedFields == null ? Set.of() : Set.copyOf(allowedFields);
            if (!allowedFields.containsAll(requiredFields)) {
                throw new IllegalArgumentException(type + " required fields must be allowed fields");
            }
        }
    }
}
