package sh.harold.fulcrum.api.data.impl.authority;

/**
 * Shared naming rules for principals observed by the authority ingress path.
 */
public final class AuthorityPrincipals {
    private static final String UNKNOWN = "unknown";
    private static final String NODE_PREFIX = "node:";

    private AuthorityPrincipals() {
    }

    public static String nodePrincipal(String nodeId) {
        if (!known(nodeId)) {
            return UNKNOWN;
        }
        return NODE_PREFIX + nodeId;
    }

    public static boolean known(String principal) {
        return principal != null && !principal.isBlank() && !UNKNOWN.equalsIgnoreCase(principal);
    }

    public static boolean reservedPrincipal(String actorId) {
        return actorId != null && actorId.startsWith(NODE_PREFIX);
    }

    public static boolean canClaimActor(String verifiedPrincipal, String actorId) {
        if (!reservedPrincipal(actorId)) {
            return true;
        }
        return known(verifiedPrincipal) && actorId.equals(verifiedPrincipal);
    }
}
