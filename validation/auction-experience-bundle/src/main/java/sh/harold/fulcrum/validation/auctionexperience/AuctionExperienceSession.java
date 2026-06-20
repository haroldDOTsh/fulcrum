package sh.harold.fulcrum.validation.auctionexperience;

public record AuctionExperienceSession(String sessionId) {
    public AuctionExperienceSession {
        sessionId = Names.requireNonBlank(sessionId, "sessionId");
    }
}
