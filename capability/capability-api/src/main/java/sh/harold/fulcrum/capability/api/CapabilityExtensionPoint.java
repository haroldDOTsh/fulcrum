package sh.harold.fulcrum.capability.api;

public enum CapabilityExtensionPoint {
    PROXY_LOGIN_GATE("Proxy.LoginGate"),
    PROXY_COMMANDS("Proxy.Commands"),
    PROXY_ROUTE_POLICY_HOOKS("Proxy.RoutePolicyHooks"),
    PROXY_PLAYER_FANOUT("Proxy.PlayerFanout"),
    PAPER_CHAT_PIPELINE("Paper.ChatPipeline"),
    PAPER_COMMANDS("Paper.Commands"),
    PAPER_TAB_LIST("Paper.TabList"),
    PAPER_SCOREBOARD("Paper.Scoreboard"),
    PAPER_EVENTS("Paper.Events"),
    PAPER_MENUS("Paper.Menus"),
    PAPER_SOUND("Paper.Sound"),
    EXPERIENCE_LIFECYCLE("Experience.Lifecycle"),
    EXPERIENCE_UI_SURFACE("Experience.UiSurface"),
    EXPERIENCE_QUEUE_POLICY("Experience.QueuePolicy"),
    EXPERIENCE_ROSTER_POLICY("Experience.RosterPolicy");

    private final String wireName;

    CapabilityExtensionPoint(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
