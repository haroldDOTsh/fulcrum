package sh.harold.fulcrum.api.lifecycle.registration;

import sh.harold.fulcrum.api.lifecycle.ServerType;

/**
 * Request to register a server with the lifecycle system.
 */
public record ServerRegistration(
    String family,
    ServerType type,
    String address,
    int port,
    int softCap,
    int hardCap
) {
    /**
     * Creates a registration for a game server.
     */
    public static ServerRegistration game(
        String family,
        String address,
        int port,
        int softCap,
        int hardCap
    ) {
        return new ServerRegistration(
            family, ServerType.GAME, address, port, softCap, hardCap
        );
    }

    /**
     * Creates a registration for a proxy server.
     */
    public static ServerRegistration proxy(
        String family,
        String address,
        int port
    ) {
        // Proxies don't have player caps in the same way
        return new ServerRegistration(
            family, ServerType.PROXY, address, port, 
            Integer.MAX_VALUE, Integer.MAX_VALUE
        );
    }
}