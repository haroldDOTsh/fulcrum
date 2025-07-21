package sh.harold.fulcrum;

import java.util.List;

/**
 * Represents a runtime profile for a given environment role.
 *
 * @param map     The map or world file for this role.
 * @param modules The list of module names to load for this role.
 */
public record RuntimeProfile(String map, List<String> modules) {
}
