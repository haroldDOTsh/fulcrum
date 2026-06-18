package sh.harold.fulcrum.host.paper;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SlotId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.host.api.HostObservation;

import java.util.Objects;
import java.util.function.Supplier;

public final class PaperPlayerSessionListener implements Listener {
    private final JavaPlugin plugin;
    private final PaperJoinAttachmentHandler attachmentHandler;
    private final Supplier<SlotId> slotIdSupplier;
    private final Supplier<ResolvedManifestId> resolvedManifestIdSupplier;
    private final Supplier<String> traceIdSupplier;
    private final PaperSpawnPoint spawnPoint;
    private final PaperCapabilityBridge capabilityBridge;
    private final PaperRewardSink rewardSink;

    public PaperPlayerSessionListener(
            JavaPlugin plugin,
            PaperJoinAttachmentHandler attachmentHandler,
            Supplier<SlotId> slotIdSupplier,
            Supplier<ResolvedManifestId> resolvedManifestIdSupplier,
            Supplier<String> traceIdSupplier,
            PaperSpawnPoint spawnPoint,
            PaperCapabilityBridge capabilityBridge,
            PaperRewardSink rewardSink) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.attachmentHandler = Objects.requireNonNull(attachmentHandler, "attachmentHandler");
        this.slotIdSupplier = Objects.requireNonNull(slotIdSupplier, "slotIdSupplier");
        this.resolvedManifestIdSupplier = Objects.requireNonNull(
                resolvedManifestIdSupplier,
                "resolvedManifestIdSupplier");
        this.traceIdSupplier = Objects.requireNonNull(traceIdSupplier, "traceIdSupplier");
        this.spawnPoint = Objects.requireNonNull(spawnPoint, "spawnPoint");
        this.capabilityBridge = Objects.requireNonNull(capabilityBridge, "capabilityBridge");
        this.rewardSink = Objects.requireNonNull(rewardSink, "rewardSink");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        World world = world();
        world.getBlockAt(
                spawnPoint.bedrockBlockX(),
                spawnPoint.bedrockBlockY(),
                spawnPoint.bedrockBlockZ()).setType(Material.BEDROCK, false);
        boolean teleported = player.teleport(new Location(
                world,
                spawnPoint.x(),
                spawnPoint.y(),
                spawnPoint.z(),
                spawnPoint.yaw(),
                spawnPoint.pitch()));
        if (!teleported) {
            throw new IllegalStateException("Paper failed to teleport " + player.getName()
                    + " to lobby spawn " + spawnPoint.worldName());
        }
        PaperJoiningSubject joiningSubject = new PaperJoiningSubject(player.getUniqueId(), player.getName());
        RouteId routeId = attachmentHandler.routeId(joiningSubject);
        HostObservation attachment = attachmentHandler.attach(joiningSubject);
        rewardSink.publish(PaperSessionRewardReport.fromAttachmentObservation(attachment));
        PaperSubjectCapabilityView view = subjectView(new SubjectId(player.getUniqueId()), player.getName());
        Component decoratedName = Component.text(view.decoratedDisplayName());
        player.displayName(decoratedName);
        player.playerListName(decoratedName);
        PaperChatDecorationResponse chat = decorateProofChat(player.getUniqueId(), player.getName());
        Location playerLocation = player.getLocation();
        player.sendPluginMessage(
                plugin,
                PaperLobbyProofMessage.CHANNEL,
                PaperLobbyProofMessage.from(
                        attachmentHandler.instanceId(),
                        attachmentHandler.sessionId(),
                        routeId,
                        Objects.requireNonNull(slotIdSupplier.get(), "slotId"),
                        Objects.requireNonNull(resolvedManifestIdSupplier.get(), "resolvedManifestId"),
                        Objects.requireNonNull(traceIdSupplier.get(), "traceId"),
                        spawnPoint,
                        playerLocation.getX(),
                        playerLocation.getY(),
                        playerLocation.getZ(),
                        playerLocation.getYaw(),
                        playerLocation.getPitch(),
                        view,
                        chat).encode());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        var player = event.getPlayer();
        attachmentHandler.detach(new PaperJoiningSubject(player.getUniqueId(), player.getName()));
    }

    @EventHandler
    public void onAsyncChat(AsyncChatEvent event) {
        var player = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (message.isBlank()) {
            return;
        }
        PaperChatDecorationResponse response;
        try {
            response = capabilityBridge.decorateChat(new PaperChatDecorationRequest(
                    new SubjectId(player.getUniqueId()),
                    player.getName(),
                    message));
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Falling back to Paper chat renderer after capability bridge failure: "
                    + exception.getMessage());
            return;
        }
        event.renderer(ChatRenderer.viewerUnaware((source, sourceDisplayName, renderedMessage) ->
                Component.text(response.decoratedMessage())));
    }

    private World world() {
        World world = plugin.getServer().getWorld(spawnPoint.worldName());
        if (world == null) {
            throw new IllegalStateException("Paper spawn world is not loaded: " + spawnPoint.worldName());
        }
        return world;
    }

    private PaperSubjectCapabilityView subjectView(SubjectId subjectId, String username) {
        try {
            return capabilityBridge.subjectView(new PaperSubjectCapabilityRequest(subjectId, username));
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Falling back to Paper username after capability bridge failure: "
                    + exception.getMessage());
            return PaperSubjectCapabilityView.fallback(subjectId, username);
        }
    }

    private PaperChatDecorationResponse decorateProofChat(java.util.UUID playerUuid, String username) {
        SubjectId subjectId = new SubjectId(playerUuid);
        try {
            return capabilityBridge.decorateChat(new PaperChatDecorationRequest(
                    subjectId,
                    username,
                    PaperLobbyProofMessage.PROOF_CHAT_MESSAGE));
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Falling back to Paper chat proof after capability bridge failure: "
                    + exception.getMessage());
            return new PaperChatDecorationResponse(
                    subjectId,
                    username + ": " + PaperLobbyProofMessage.PROOF_CHAT_MESSAGE);
        }
    }
}
