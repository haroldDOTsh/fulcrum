package sh.harold.fulcrum.api.data.impl.authority;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Database-minted ownership receipt for an authority partition fencing epoch.
 */
public record AuthorityWriterClaim(
    UUID claimId,
    String commandDomain,
    String commandTopic,
    String partitionKey,
    String ownerNode,
    long epoch,
    String previousOwnerNode,
    long previousEpoch,
    Instant claimedAt,
    String claimFingerprint
) {
    static final String TOKEN_PREFIX = "claim:v1:";

    public AuthorityWriterClaim {
        claimId = Objects.requireNonNull(claimId, "claimId");
        commandDomain = requireText(commandDomain, "commandDomain");
        commandTopic = requireText(commandTopic, "commandTopic");
        partitionKey = requireText(partitionKey, "partitionKey");
        ownerNode = requireText(ownerNode, "ownerNode");
        if (epoch <= 0L) {
            throw new IllegalArgumentException("epoch must be positive");
        }
        previousOwnerNode = previousOwnerNode == null || previousOwnerNode.isBlank() ? null : previousOwnerNode;
        if (previousEpoch < 0L) {
            throw new IllegalArgumentException("previousEpoch must not be negative");
        }
        claimedAt = Objects.requireNonNull(claimedAt, "claimedAt");
        if (claimFingerprint == null || !claimFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("claimFingerprint must be a SHA-256 hash");
        }
    }

    public static AuthorityWriterClaim mint(
        String commandDomain,
        String commandTopic,
        String partitionKey,
        String ownerNode,
        long epoch,
        String previousOwnerNode,
        long previousEpoch,
        Instant claimedAt
    ) {
        UUID claimId = UUID.randomUUID();
        Instant effectiveClaimedAt = claimedAt == null ? Instant.now() : claimedAt;
        String fingerprint = fingerprint(
            claimId,
            commandDomain,
            commandTopic,
            partitionKey,
            ownerNode,
            epoch,
            previousOwnerNode,
            previousEpoch,
            effectiveClaimedAt
        );
        return new AuthorityWriterClaim(
            claimId,
            commandDomain,
            commandTopic,
            partitionKey,
            ownerNode,
            epoch,
            previousOwnerNode,
            previousEpoch,
            effectiveClaimedAt,
            fingerprint
        );
    }

    public String fencingToken() {
        return TOKEN_PREFIX + epoch + ":" + claimId + ":" + claimFingerprint;
    }

    public static String fingerprint(
        UUID claimId,
        String commandDomain,
        String commandTopic,
        String partitionKey,
        String ownerNode,
        long epoch,
        String previousOwnerNode,
        long previousEpoch,
        Instant claimedAt
    ) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("claimId", Objects.requireNonNull(claimId, "claimId").toString());
        material.put("commandDomain", requireText(commandDomain, "commandDomain"));
        material.put("commandTopic", requireText(commandTopic, "commandTopic"));
        material.put("partitionKey", requireText(partitionKey, "partitionKey"));
        material.put("ownerNode", requireText(ownerNode, "ownerNode"));
        material.put("epoch", epoch);
        material.put("previousOwnerNode", previousOwnerNode == null || previousOwnerNode.isBlank()
            ? "none"
            : previousOwnerNode);
        material.put("previousEpoch", previousEpoch);
        material.put("claimedAt", Objects.requireNonNull(claimedAt, "claimedAt").toString());
        return AuthorityCommandFingerprints.hash(AuthorityCommandFingerprints.canonicalJson(material));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}

record AuthorityWriterClaimToken(long epoch, UUID claimId, String claimFingerprint) {
    AuthorityWriterClaimToken {
        if (epoch <= 0L) {
            throw new IllegalArgumentException("writer claim epoch must be positive");
        }
        claimId = Objects.requireNonNull(claimId, "claimId");
        if (claimFingerprint == null || !claimFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("writer claim fingerprint must be a SHA-256 hash");
        }
    }

    static AuthorityWriterClaimToken parse(String token) {
        if (token == null || token.isBlank() || !token.startsWith(AuthorityWriterClaim.TOKEN_PREFIX)) {
            return null;
        }
        String[] parts = token.split(":", 5);
        if (parts.length != 5 || !"claim".equals(parts[0]) || !"v1".equals(parts[1])) {
            throw new IllegalArgumentException("fencingToken writer claim receipt is malformed");
        }
        try {
            return new AuthorityWriterClaimToken(
                Long.parseLong(parts[2]),
                UUID.fromString(parts[3]),
                parts[4]
            );
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("fencingToken writer claim receipt is malformed", exception);
        }
    }
}
