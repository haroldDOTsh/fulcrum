package sh.harold.fulcrum.api.data.impl.authority;

import com.google.gson.Gson;
import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

final class AuthorityCommandFingerprints {
    private static final Gson GSON = new Gson();

    private AuthorityCommandFingerprints() {
    }

    static Fingerprint fingerprint(DataAuthority.AuthorityCommand command) {
        DataAuthorityCommandContracts.CommandContract contract = DataAuthorityCommandContracts.contract(command.type());
        String payloadHash = hash(canonicalJson(AuthorityCommandPayloads.payload(command)));
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("commandId", command.commandId().toString());
        material.put("declarationId", contract.declarationId());
        material.put("schemaVersion", command.manifest().schemaVersion());
        material.put("actorId", command.actorId());
        material.put("verifiedPrincipal", command.provenance().verifiedPrincipal());
        material.put("route", AuthorityCommandRoute.fromDeclarationId(contract.declarationId(), command.scope())
            .payload());
        material.put("scope", command.scope());
        material.put("expectedRevision", command.expectedRevision());
        material.put("payloadHash", payloadHash);
        return new Fingerprint(payloadHash, hash(canonicalJson(material)));
    }

    static String canonicalJson(Object value) {
        return GSON.toJson(canonicalValue(value));
    }

    static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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
            List<Object> normalized = new ArrayList<>();
            for (Object item : values) {
                normalized.add(canonicalValue(item));
            }
            return normalized;
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

    record Fingerprint(String payloadHash, String commandFingerprint) {
    }
}
