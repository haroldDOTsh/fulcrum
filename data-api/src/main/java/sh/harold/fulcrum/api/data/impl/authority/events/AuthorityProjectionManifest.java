package sh.harold.fulcrum.api.data.impl.authority.events;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stable projection compatibility manifest recorded with projection checkpoint receipts.
 */
public final class AuthorityProjectionManifest {
    private static final Gson GSON = new Gson();
    private static final String DEFAULT_PROJECTION_VERSION = "unversioned";
    private static final String WILDCARD_EVENT_TYPE = "*";

    private final String projectionName;
    private final String projectionVersion;
    private final List<String> acceptedEventTypes;
    private final String manifestPayload;
    private final String manifestFingerprint;

    private AuthorityProjectionManifest(
        String projectionName,
        String projectionVersion,
        List<String> acceptedEventTypes
    ) {
        this.projectionName = normalize(projectionName, "projectionName");
        this.projectionVersion = projectionVersion == null || projectionVersion.isBlank()
            ? DEFAULT_PROJECTION_VERSION
            : projectionVersion.trim();
        this.acceptedEventTypes = List.copyOf(acceptedEventTypes);
        this.manifestPayload = GSON.toJson(payload(this.projectionName, this.projectionVersion, this.acceptedEventTypes));
        this.manifestFingerprint = AuthorityEventFingerprints.sha256(manifestPayload);
    }

    /**
     * Creates a compatibility manifest for existing projections that have not declared event types yet.
     *
     * @param projectionName projection name
     * @return wildcard compatibility manifest
     */
    public static AuthorityProjectionManifest unversioned(String projectionName) {
        return new AuthorityProjectionManifest(
            projectionName,
            DEFAULT_PROJECTION_VERSION,
            List.of(WILDCARD_EVENT_TYPE)
        );
    }

    /**
     * Creates an explicit projection manifest that accepts only declared event types.
     *
     * @param projectionName projection name
     * @param projectionVersion stable projection implementation version
     * @param acceptedEventTypes event types the projection can handle
     * @return projection manifest
     */
    public static AuthorityProjectionManifest of(
        String projectionName,
        String projectionVersion,
        Collection<String> acceptedEventTypes
    ) {
        List<String> eventTypes = normalizedEventTypes(acceptedEventTypes);
        return new AuthorityProjectionManifest(projectionName, projectionVersion, eventTypes);
    }

    /**
     * Returns whether the manifest accepts an authority event type.
     *
     * @param eventType event type from the authority event log
     * @return true when the event may be delivered to the projection
     */
    public boolean acceptsEventType(String eventType) {
        if (acceptedEventTypes.contains(WILDCARD_EVENT_TYPE)) {
            return true;
        }
        return eventType != null && acceptedEventTypes.contains(eventType.trim());
    }

    /**
     * Returns whether this is the compatibility wildcard manifest for undeclared projections.
     *
     * @return true for compatibility wildcard manifests
     */
    public boolean acceptsAllEventTypes() {
        return acceptedEventTypes.size() == 1 && WILDCARD_EVENT_TYPE.equals(acceptedEventTypes.get(0));
    }

    /**
     * Returns the projection name.
     *
     * @return projection name
     */
    public String projectionName() {
        return projectionName;
    }

    /**
     * Returns the projection implementation version.
     *
     * @return projection version
     */
    public String projectionVersion() {
        return projectionVersion;
    }

    /**
     * Returns accepted event types in deterministic order.
     *
     * @return accepted event types
     */
    public List<String> acceptedEventTypes() {
        return acceptedEventTypes;
    }

    /**
     * Returns the canonical manifest JSON payload.
     *
     * @return manifest payload JSON
     */
    public String manifestPayload() {
        return manifestPayload;
    }

    /**
     * Returns the canonical manifest fingerprint.
     *
     * @return manifest fingerprint
     */
    public String manifestFingerprint() {
        return manifestFingerprint;
    }

    private static List<String> normalizedEventTypes(Collection<String> acceptedEventTypes) {
        if (acceptedEventTypes == null || acceptedEventTypes.isEmpty()) {
            throw new IllegalArgumentException("acceptedEventTypes is required");
        }
        List<String> eventTypes = new ArrayList<>();
        for (String eventType : acceptedEventTypes) {
            eventTypes.add(normalize(eventType, "acceptedEventType"));
        }
        eventTypes.sort(String::compareTo);
        return eventTypes.stream().distinct().toList();
    }

    private static String normalize(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static Map<String, Object> payload(
        String projectionName,
        String projectionVersion,
        List<String> acceptedEventTypes
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectionName", projectionName);
        payload.put("projectionVersion", projectionVersion);
        payload.put("acceptedEventTypes", acceptedEventTypes);
        return payload;
    }
}
