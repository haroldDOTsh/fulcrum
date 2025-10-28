package sh.harold.fulcrum.api.punishment;

import java.time.Duration;
import java.util.*;

/**
 * Enumerates the supported punishment reasons and the ladder actions associated with each rung.
 */
public enum PunishmentReason {
    SOCIAL_MEDIA_ADVERTISING(
            "social_media_advertising",
            "Social Media Advertising",
            PunishmentLadder.CHAT_MINOR,
            buildChatMinorMarketingTiers()
    ),
    TROLLING(
            "trolling",
            "Trolling",
            PunishmentLadder.CHAT_MINOR,
            buildChatMinorMarketingTiers()
    ),
    SPAMMING(
            "spamming",
            "Spamming",
            PunishmentLadder.CHAT_MINOR,
            List.of(
                    tier(1, PunishmentEffect.mute(Duration.ofHours(1))),
                    tier(2, PunishmentEffect.mute(Duration.ofHours(12))),
                    tier(3, PunishmentEffect.mute(Duration.ofHours(24))),
                    tier(4, PunishmentEffect.mute(Duration.ofDays(7))),
                    tier(5, PunishmentEffect.mute(Duration.ofDays(14)), PunishmentEffect.ban(Duration.ofDays(7))),
                    tier(6, PunishmentEffect.mute(Duration.ofDays(30)), PunishmentEffect.ban(Duration.ofDays(7))),
                    tier(7, PunishmentEffect.mute(Duration.ofDays(90)), PunishmentEffect.ban(Duration.ofDays(7))),
                    tier(8, PunishmentEffect.mute(Duration.ofDays(180)), PunishmentEffect.ban(Duration.ofDays(7))),
                    tier(9, PunishmentEffect.mutePermanent(), PunishmentEffect.ban(Duration.ofDays(7)))
            )
    ),
    DEATH_THREATS(
            "death_threats",
            "Death Threats / Verbal Abuse",
            PunishmentLadder.CHAT_MAJOR,
            List.of(
                    tier(1, PunishmentEffect.mute(Duration.ofDays(7))),
                    tier(2, PunishmentEffect.mute(Duration.ofDays(14))),
                    tier(3, PunishmentEffect.mute(Duration.ofDays(30))),
                    tier(4, PunishmentEffect.mute(Duration.ofDays(90)), PunishmentEffect.ban(Duration.ofDays(7))),
                    tier(5, PunishmentEffect.mute(Duration.ofDays(180)), PunishmentEffect.ban(Duration.ofDays(14))),
                    tier(6, PunishmentEffect.manualReview("Escalate to #brig for permanent action"))
            )
    ),
    DDOS_THREATS(
            "ddos_threats",
            "DDoS Threats",
            PunishmentLadder.CHAT_MAJOR,
            List.of(
                    tier(1, PunishmentEffect.blacklist("Immediate blacklist for DDoS threat"))
            )
    ),
    DISCRIMINATION(
            "discrimination",
            "Discrimination",
            PunishmentLadder.CHAT_MAJOR,
            List.of(
                    tier(1, PunishmentEffect.mute(Duration.ofDays(3))),
                    tier(2, PunishmentEffect.mute(Duration.ofDays(14))),
                    tier(3, PunishmentEffect.mute(Duration.ofDays(90)), PunishmentEffect.ban(Duration.ofDays(14))),
                    tier(4, PunishmentEffect.ban(Duration.ofDays(90))),
                    tier(5, PunishmentEffect.manualReview("Escalate to #brig for network-wide action"))
            )
    ),
    NON_AFFILIATED_ADVERTISEMENT(
            "non_affiliated_advertisement",
            "Non-Affiliated Advertisement",
            PunishmentLadder.CHAT_MAJOR,
            List.of(
                    tier(1, PunishmentEffect.mute(Duration.ofDays(60))),
                    tier(2, PunishmentEffect.mute(Duration.ofDays(180)), PunishmentEffect.ban(Duration.ofDays(7))),
                    tier(3, PunishmentEffect.mutePermanent(), PunishmentEffect.ban(Duration.ofDays(90))),
                    tier(4, PunishmentEffect.manualReview("Escalate to #brig for permanent decision")),
                    tier(5, PunishmentEffect.banPermanent("Permanent ban for repeated non-affiliated advertising"))
            )
    ),
    SCAMMING(
            "scamming",
            "Scamming",
            PunishmentLadder.GAMEPLAY,
            List.of(
                    tier(1, PunishmentEffect.ban(Duration.ofDays(14)), PunishmentEffect.manualReview("Wipe player balances/items gained")),
                    tier(2, PunishmentEffect.manualReview("Escalate to #brig for long-term action")),
                    tier(3, PunishmentEffect.manualReview("Escalate to #brig for permanent action"))
            )
    ),
    PHISHING_RATTING(
            "phishing_ratting",
            "Phishing / Ratting",
            PunishmentLadder.MISC,
            List.of(
                    tier(1, PunishmentEffect.blacklist("Blacklist for phishing / ratting attempt"))
            )
    ),
    TEAM_GRIEFING(
            "team_griefing",
            "Team Griefing / Insiding",
            PunishmentLadder.GAMEPLAY,
            List.of(
                    tier(1, PunishmentEffect.ban(Duration.ofHours(24))),
                    tier(2, PunishmentEffect.ban(Duration.ofDays(7))),
                    tier(3, PunishmentEffect.ban(Duration.ofDays(14))),
                    tier(4, PunishmentEffect.ban(Duration.ofDays(45))),
                    tier(5, PunishmentEffect.ban(Duration.ofDays(90))),
                    tier(6, PunishmentEffect.manualReview("Escalate to #brig for extended suspension")),
                    tier(7, PunishmentEffect.manualReview("Escalate to #brig for extended suspension")),
                    tier(8, PunishmentEffect.manualReview("Escalate to #brig for extended suspension")),
                    tier(9, PunishmentEffect.manualReview("Escalate to #brig for extended suspension"))
            )
    ),
    ILLEGITIMATE_GAMEPLAY(
            "illegitimate_gameplay",
            "Illegitimate Gameplay / Hacks",
            PunishmentLadder.GAMEPLAY,
            List.of(
                    tier(1, PunishmentEffect.ban(Duration.ofDays(1))),
                    tier(2, PunishmentEffect.ban(Duration.ofDays(7))),
                    tier(3, PunishmentEffect.ban(Duration.ofDays(14))),
                    tier(4, PunishmentEffect.ban(Duration.ofDays(30))),
                    tier(5, PunishmentEffect.ban(Duration.ofDays(90))),
                    tier(6, PunishmentEffect.ban(Duration.ofDays(180))),
                    tier(7, PunishmentEffect.ban(Duration.ofDays(365))),
                    tier(8, PunishmentEffect.manualReview("Escalate to #brig for permanent action")),
                    tier(9, PunishmentEffect.manualReview("Escalate to #brig for permanent action"))
            )
    ),
    BUG_EXPLOITING(
            "bug_exploiting",
            "Bug Exploiting",
            PunishmentLadder.GAMEPLAY,
            buildRepeatedManualReview("Escalate to #brig and #bugs for coordinated response")
    ),
    INAPPROPRIATE_NAME(
            "inappropriate_name",
            "Inappropriate Name / Skin / Cape",
            PunishmentLadder.MISC,
            List.of(
                    tier(1, PunishmentEffect.appealRequired("Appeal through ticket after resolving the issue")),
                    tier(2, PunishmentEffect.appealRequired("Appeal through ticket after resolving the issue")),
                    tier(3, PunishmentEffect.appealRequired("Appeal through ticket after resolving the issue")),
                    tier(4, PunishmentEffect.appealRequired("Appeal through ticket after resolving the issue")),
                    tier(5, PunishmentEffect.appealRequired("Appeal through ticket after resolving the issue")),
                    tier(6, PunishmentEffect.manualReview("Escalate to #brig + ticket for review")),
                    tier(7, PunishmentEffect.manualReview("Escalate to #brig + ticket for review")),
                    tier(8, PunishmentEffect.manualReview("Escalate to #brig for network-wide decision")),
                    tier(9, PunishmentEffect.manualReview("Escalate to #brig for network-wide decision"))
            )
    ),
    INAPPROPRIATE_BUILD(
            "inappropriate_build",
            "Inappropriate Build",
            PunishmentLadder.MISC,
            List.of(
                    tier(1, PunishmentEffect.ban(Duration.ofHours(24))),
                    tier(2, PunishmentEffect.ban(Duration.ofDays(7))),
                    tier(3, PunishmentEffect.ban(Duration.ofDays(30))),
                    tier(4, PunishmentEffect.ban(Duration.ofDays(90))),
                    tier(5, PunishmentEffect.manualReview("Escalate to #brig for extended action")),
                    tier(6, PunishmentEffect.manualReview("Escalate to #brig for extended action")),
                    tier(7, PunishmentEffect.manualReview("Escalate to #brig for extended action")),
                    tier(8, PunishmentEffect.manualReview("Escalate to #brig for extended action")),
                    tier(9, PunishmentEffect.manualReview("Escalate to #brig for extended action"))
            )
    ),
    SECURITY_BAN(
            "security_ban",
            "Security Ban",
            PunishmentLadder.MISC,
            List.of(
                    tier(1, PunishmentEffect.banPermanent("Indefinite ban until ticket appeal is resolved"), PunishmentEffect.appealRequired("Appeal through ticket to restore access"))
            )
    );

    private final String id;
    private final String displayName;
    private final PunishmentLadder ladder;
    private final List<PunishmentTier> tiers;
    private final int rungDelta;

    PunishmentReason(String id,
                     String displayName,
                     PunishmentLadder ladder,
                     List<PunishmentTier> tiers) {
        this(id, displayName, ladder, tiers, 1);
    }

    PunishmentReason(String id,
                     String displayName,
                     PunishmentLadder ladder,
                     List<PunishmentTier> tiers,
                     int rungDelta) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.ladder = Objects.requireNonNull(ladder, "ladder");
        this.tiers = Collections.unmodifiableList(new ArrayList<>(tiers));
        this.rungDelta = rungDelta;
    }

    public static PunishmentReason fromId(String id) {
        if (id == null) {
            return null;
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        for (PunishmentReason reason : values()) {
            if (reason.id.equalsIgnoreCase(normalized)) {
                return reason;
            }
        }
        return null;
    }

    private static List<PunishmentTier> buildChatMinorMarketingTiers() {
        return List.of(
                tier(1, PunishmentEffect.mute(Duration.ofHours(1))),
                tier(2, PunishmentEffect.mute(Duration.ofDays(1))),
                tier(3, PunishmentEffect.mute(Duration.ofDays(7))),
                tier(4, PunishmentEffect.mute(Duration.ofDays(14)), PunishmentEffect.ban(Duration.ofDays(1))),
                tier(5, PunishmentEffect.mute(Duration.ofDays(90)), PunishmentEffect.ban(Duration.ofDays(7))),
                tier(6, PunishmentEffect.manualReview("Escalate to #brig for permanent decision"))
        );
    }

    private static List<PunishmentTier> buildRepeatedManualReview(String message) {
        List<PunishmentTier> tiers = new ArrayList<>();
        for (int rung = 1; rung <= 9; rung++) {
            tiers.add(tier(rung, PunishmentEffect.manualReview(message)));
        }
        return tiers;
    }

    private static PunishmentTier tier(int rung, PunishmentEffect... effects) {
        return PunishmentTier.of(rung, effects);
    }

    public static Map<String, PunishmentReason> idMap() {
        return Map.ofEntries(
                Map.entry(SOCIAL_MEDIA_ADVERTISING.id, SOCIAL_MEDIA_ADVERTISING),
                Map.entry(TROLLING.id, TROLLING),
                Map.entry(SPAMMING.id, SPAMMING),
                Map.entry(DEATH_THREATS.id, DEATH_THREATS),
                Map.entry(DDOS_THREATS.id, DDOS_THREATS),
                Map.entry(DISCRIMINATION.id, DISCRIMINATION),
                Map.entry(NON_AFFILIATED_ADVERTISEMENT.id, NON_AFFILIATED_ADVERTISEMENT),
                Map.entry(SCAMMING.id, SCAMMING),
                Map.entry(PHISHING_RATTING.id, PHISHING_RATTING),
                Map.entry(TEAM_GRIEFING.id, TEAM_GRIEFING),
                Map.entry(ILLEGITIMATE_GAMEPLAY.id, ILLEGITIMATE_GAMEPLAY),
                Map.entry(BUG_EXPLOITING.id, BUG_EXPLOITING),
                Map.entry(INAPPROPRIATE_NAME.id, INAPPROPRIATE_NAME),
                Map.entry(INAPPROPRIATE_BUILD.id, INAPPROPRIATE_BUILD),
                Map.entry(SECURITY_BAN.id, SECURITY_BAN)
        );
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public PunishmentLadder getLadder() {
        return ladder;
    }

    public int getRungDelta() {
        return rungDelta;
    }

    /**
     * Computes the outcome that should be applied when this reason is used.
     */
    public PunishmentOutcome evaluate(int currentRung) {
        int nextRung = Math.max(1, currentRung + rungDelta);
        PunishmentTier tier = resolveTier(nextRung);
        return new PunishmentOutcome(this, currentRung, nextRung, tier.getEffects());
    }

    private PunishmentTier resolveTier(int rung) {
        PunishmentTier best = tiers.get(tiers.size() - 1);
        for (PunishmentTier tier : tiers) {
            if (tier.getRung() == rung) {
                return tier;
            }
            if (tier.getRung() < rung) {
                best = tier;
            }
        }
        return best;
    }
}
