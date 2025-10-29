package sh.harold.fulcrum.api.punishment;

/**
 * Status flags applied to punishment records so we never delete history.
 */
public enum PunishmentStatus {
    ACTIVE,
    EXPIRED,
    APPEALED,
    PARDONED
}
