package sh.harold.fulcrum.velocity.privacy;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.friends.FriendService;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.common.privacy.*;
import sh.harold.fulcrum.common.settings.PlayerSettingsService;
import sh.harold.fulcrum.common.settings.SettingLevel;
import sh.harold.fulcrum.message.Message;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.friends.VelocityFriendService;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

public final class VelocityPrivacyFeature implements VelocityFeature {
    private PrivacyApi privacyApi;
    private ServiceLocator serviceLocator;
    private ProxyServer proxy;
    private FulcrumVelocityPlugin plugin;
    private RankService rankService;
    private PlayerSettingsService playerSettingsService;

    private static PrivacyDomain resolveDomain(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim();
        for (PrivacyDomain domain : PrivacyDomain.values()) {
            if (domain.name().equalsIgnoreCase(normalized) || domain.settingKey().equalsIgnoreCase(normalized)) {
                return domain;
            }
        }
        return null;
    }

    @Override
    public String getName() {
        return "VelocityPrivacy";
    }

    @Override
    public int getPriority() {
        return 80;
    }

    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) {
        this.serviceLocator = serviceLocator;
        this.proxy = serviceLocator.getRequiredService(ProxyServer.class);
        this.plugin = serviceLocator.getRequiredService(FulcrumVelocityPlugin.class);
        this.playerSettingsService = serviceLocator.getRequiredService(PlayerSettingsService.class);
        this.rankService = serviceLocator.getRequiredService(RankService.class);
        FriendService friendService = serviceLocator.getRequiredService(FriendService.class);

        PrivacyDomainRegistry registry = new PrivacyDomainRegistry();
        registry.register(PrivacyDomain.PARTY_INVITES, PrivacyDomainPresets.partyInvites());
        registry.register(PrivacyDomain.FRIEND_INVITES, PrivacyDomainPresets.friendInvites());
        registry.register(PrivacyDomain.DIRECT_MESSAGES,
                PrivacyDomainPresets.directMessages(EnumSet.of(SettingLevel.NONE, SettingLevel.MEDIUM, SettingLevel.HIGH, SettingLevel.MAX)));

        PrivacySignals signals = new VelocityPrivacySignals(proxy, serviceLocator);
        PrivacyGate gate = new DefaultPrivacyGate(playerSettingsService, friendService, rankService, signals, registry);
        this.privacyApi = new DefaultPrivacyApi(gate, registry);

        serviceLocator.register(PrivacyApi.class, privacyApi);
        serviceLocator.getService(FriendService.class).ifPresent(service -> {
            if (service instanceof VelocityFriendService velocityFriendService) {
                velocityFriendService.setPrivacyGate(gate);
            }
        });

        registerPrivacyDebugCommand();

        logger.info("VelocityPrivacyFeature initialised");
    }

    @Override
    public void shutdown() {
        if (proxy != null) {
            proxy.getCommandManager().unregister("privacydebug");
        }
        if (serviceLocator != null) {
            serviceLocator.unregister(PrivacyApi.class);
        }
        privacyApi = null;
    }

    private void registerPrivacyDebugCommand() {
        SimpleCommand command = new PrivacyDebugCommand();
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("privacydebug").plugin(plugin).build(),
                command);
    }

    private final class PrivacyDebugCommand implements SimpleCommand {

        @Override
        public List<String> suggest(Invocation invocation) {
            if (!(invocation.source() instanceof Player player) || !isStaff(player.getUniqueId())) {
                return List.of();
            }
            String[] args = invocation.arguments();
            if (args.length == 0) {
                return Stream.of(PrivacyDomain.values())
                        .filter(domain -> privacyApi.domains().get(domain).isPresent())
                        .map(PrivacyDomain::name)
                        .sorted()
                        .toList();
            }
            if (args.length == 1) {
                String prefix = args[0] == null ? "" : args[0].toUpperCase(Locale.ROOT);
                return Stream.of(PrivacyDomain.values())
                        .filter(domain -> privacyApi.domains().get(domain).isPresent())
                        .map(PrivacyDomain::name)
                        .filter(id -> id.startsWith(prefix))
                        .sorted()
                        .toList();
            }
            if (args.length == 2) {
                PrivacyDomain domain = resolveDomain(args[0]);
                if (domain == null) {
                    return List.of();
                }
                String prefix = args[1] == null ? "" : args[1].toUpperCase(Locale.ROOT);
                return privacyApi.domains().get(domain)
                        .map(config -> config.supportedLevels().stream()
                                .map(SettingLevel::name)
                                .filter(level -> level.startsWith(prefix))
                                .sorted()
                                .toList())
                        .orElse(List.of());
            }
            return List.of();
        }

        @Override
        public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player player)) {
                Message.error("Only players can use this command.")
                        .tag("debug")
                        .skipTranslation()
                        .send(invocation.source());
                return;
            }
            UUID playerId = player.getUniqueId();
            if (!isStaff(playerId)) {
                Message.error("You must be staff to run this command.")
                        .tag("debug")
                        .skipTranslation()
                        .send(player);
                return;
            }
            String[] args = invocation.arguments();
            if (args.length < 2) {
                Message.error("Usage: /privacydebug <featureId> <level>")
                        .tag("debug")
                        .skipTranslation()
                        .send(player);
                return;
            }
            PrivacyDomain domain = resolveDomain(args[0]);
            if (domain == null) {
                String valid = Stream.of(PrivacyDomain.values())
                        .filter(candidate -> privacyApi.domains().get(candidate).isPresent())
                        .map(PrivacyDomain::name)
                        .sorted()
                        .reduce((left, right) -> left + ", " + right)
                        .orElse("NONE");
                Message.error("Unknown privacy feature '" + args[0] + "'. Use: " + valid + ".")
                        .tag("debug")
                        .skipTranslation()
                        .send(player);
                return;
            }
            SettingLevel level;
            try {
                level = SettingLevel.valueOf(args[1].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                Message.error("Unknown level '" + args[1] + "'.")
                        .tag("debug")
                        .skipTranslation()
                        .send(player);
                return;
            }
            PrivacyDomainConfig config = privacyApi.domains().get(domain).orElse(null);
            if (config == null || !config.supportedLevels().contains(level)) {
                Message.error("That level is not available for " + domain.name() + ".")
                        .tag("debug")
                        .skipTranslation()
                        .send(player);
                return;
            }

            playerSettingsService.setLevel(playerId, domain.settingKey(), level)
                    .whenComplete((ignored, throwable) -> {
                        if (throwable != null) {
                            Message.error("Unable to update privacy right now.")
                                    .tag("debug")
                                    .skipTranslation()
                                    .send(player);
                            return;
                        }
                        Message.success("Set " + domain.name() + " to " + level.name())
                                .tag("debug")
                                .skipTranslation()
                                .send(player);
                    });
        }

        private boolean isStaff(UUID playerId) {
            try {
                return rankService.isStaff(playerId).join();
            } catch (Exception ex) {
                return false;
            }
        }
    }
}
