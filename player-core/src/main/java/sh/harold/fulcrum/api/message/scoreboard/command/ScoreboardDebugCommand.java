package sh.harold.fulcrum.api.message.scoreboard.command;

import static io.papermc.paper.command.brigadier.Commands.*;
import static io.papermc.paper.command.brigadier.argument.ArgumentTypes.*;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import sh.harold.fulcrum.api.message.scoreboard.ScoreboardService;
import sh.harold.fulcrum.api.message.scoreboard.module.ContentProvider;
import sh.harold.fulcrum.api.message.scoreboard.module.ScoreboardModule;
import sh.harold.fulcrum.api.message.scoreboard.module.StaticContentProvider;
import sh.harold.fulcrum.api.message.scoreboard.player.ModuleOverride;
import sh.harold.fulcrum.api.message.scoreboard.player.PlayerScoreboardManager;
import sh.harold.fulcrum.api.message.scoreboard.player.PlayerScoreboardState;
import sh.harold.fulcrum.api.message.scoreboard.registry.ScoreboardDefinition;
import sh.harold.fulcrum.api.message.scoreboard.registry.ScoreboardRegistry;
import sh.harold.fulcrum.api.message.scoreboard.render.RenderedScoreboard;
import sh.harold.fulcrum.api.message.scoreboard.render.RenderingPipeline;
import sh.harold.fulcrum.api.message.scoreboard.render.TitleManager;
import sh.harold.fulcrum.api.message.scoreboard.util.ScoreboardFlashTask;
import sh.harold.fulcrum.api.message.scoreboard.impl.DefaultPlayerScoreboardManager;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ScoreboardDebugCommand {
    
    private final ScoreboardService scoreboardService;
    private final ScoreboardRegistry scoreboardRegistry;
    private final PlayerScoreboardManager playerManager;
    private final RenderingPipeline renderingPipeline;
    private final TitleManager titleManager;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.legacyAmpersand();
    private final ScheduledExecutorService scheduler;
    
    private void sendMessage(CommandSender sender, String message) {
        Component component = legacySerializer.deserialize(message);
        sender.sendMessage(component);
    }
    
    public ScoreboardDebugCommand(ScoreboardService scoreboardService,
                                 ScoreboardRegistry scoreboardRegistry,
                                 PlayerScoreboardManager playerManager,
                                 RenderingPipeline renderingPipeline,
                                 TitleManager titleManager) {
        this.scoreboardService = scoreboardService;
        this.scoreboardRegistry = scoreboardRegistry;
        this.playerManager = playerManager;
        this.renderingPipeline = renderingPipeline;
        this.titleManager = titleManager;
        this.scheduler = Executors.newScheduledThreadPool(2);
    }
    
    public LiteralCommandNode<CommandSourceStack> build() {
        return literal("fulcrumscoreboard")
                .requires(source -> source.getSender().hasPermission("fulcrum.scoreboard.debug"))
                .then(literal("debug")
                    .then(literal("list")
                        .executes(ctx -> executeList(ctx.getSource().getSender())))
                    .then(literal("modules")
                        .then(argument("scoreboardId", StringArgumentType.word())
                            .executes(ctx -> executeModules(ctx.getSource().getSender(), 
                                    ctx.getArgument("scoreboardId", String.class)))))
                    .then(literal("player")
                        .then(argument("playerName", player())
                            .executes(ctx -> executePlayer(ctx.getSource(), 
                                    ctx.getArgument("playerName", PlayerSelectorArgumentResolver.class)))
                            .then(literal("modules")
                                .executes(ctx -> executePlayerModules(ctx.getSource(), 
                                        ctx.getArgument("playerName", PlayerSelectorArgumentResolver.class))))))
                    .then(literal("refresh")
                        .then(argument("playerName", player())
                            .executes(ctx -> executeRefresh(ctx.getSource(), 
                                    ctx.getArgument("playerName", PlayerSelectorArgumentResolver.class)))))
                    .then(literal("toggle")
                        .then(argument("playerName", player())
                            .executes(ctx -> executeToggle(ctx.getSource(), 
                                    ctx.getArgument("playerName", PlayerSelectorArgumentResolver.class)))))
                    .then(literal("stats")
                        .executes(ctx -> executeStats(ctx.getSource().getSender())))
                    .then(literal("test")
                        .then(argument("playerName", player())
                            .then(argument("scoreboardId", StringArgumentType.word())
                                .executes(ctx -> executeTest(ctx.getSource(),
                                        ctx.getArgument("playerName", PlayerSelectorArgumentResolver.class),
                                        ctx.getArgument("scoreboardId", String.class))))))
                    .then(literal("title")
                        .then(argument("playerName", player())
                            .then(literal("clear")
                                .executes(ctx -> executeClearPlayerTitle(ctx.getSource(),
                                        ctx.getArgument("playerName", PlayerSelectorArgumentResolver.class))))
                            .then(argument("title", StringArgumentType.greedyString())
                                .executes(ctx -> executeSetPlayerTitle(ctx.getSource(),
                                        ctx.getArgument("playerName", PlayerSelectorArgumentResolver.class),
                                        ctx.getArgument("title", String.class))))
                            .then(argument("scoreboardId", StringArgumentType.word())
                                .then(literal("clear")
                                    .executes(ctx -> executeClearPlayerScoreboardTitle(ctx.getSource(),
                                            ctx.getArgument("playerName", PlayerSelectorArgumentResolver.class),
                                            ctx.getArgument("scoreboardId", String.class))))
                                .then(argument("title", StringArgumentType.greedyString())
                                    .executes(ctx -> executeSetPlayerScoreboardTitle(ctx.getSource(),
                                            ctx.getArgument("playerName", PlayerSelectorArgumentResolver.class),
                                            ctx.getArgument("scoreboardId", String.class),
                                            ctx.getArgument("title", String.class)))))))
                    .then(literal("flash")
                        .then(argument("playerName", player())
                            .then(literal("clear")
                                .executes(ctx -> executeClearAllFlashes(ctx.getSource(),
                                        ctx.getArgument("playerName", PlayerSelectorArgumentResolver.class)))
                                .then(argument("index", IntegerArgumentType.integer(0, 14))
                                    .executes(ctx -> executeClearFlash(ctx.getSource(),
                                            ctx.getArgument("playerName", PlayerSelectorArgumentResolver.class),
                                            ctx.getArgument("index", Integer.class)))))
                            .then(literal("list")
                                .executes(ctx -> executeListFlashes(ctx.getSource(),
                                        ctx.getArgument("playerName", PlayerSelectorArgumentResolver.class))))
                            .then(argument("index", IntegerArgumentType.integer(0, 14))
                                .then(argument("duration", IntegerArgumentType.integer(1, 300))
                                    .then(argument("lines", IntegerArgumentType.integer(1, 15))
                                        .executes(ctx -> executeFlash(ctx.getSource(),
                                                ctx.getArgument("playerName", PlayerSelectorArgumentResolver.class),
                                                ctx.getArgument("index", Integer.class),
                                                ctx.getArgument("duration", Integer.class),
                                                ctx.getArgument("lines", Integer.class),
                                                null))
                                        .then(argument("content", StringArgumentType.greedyString())
                                            .executes(ctx -> executeFlash(ctx.getSource(),
                                                    ctx.getArgument("playerName", PlayerSelectorArgumentResolver.class),
                                                    ctx.getArgument("index", Integer.class),
                                                    ctx.getArgument("duration", Integer.class),
                                                    ctx.getArgument("lines", Integer.class),
                                                    ctx.getArgument("content", String.class))))))))))
                .executes(ctx -> {
                    sendMessage(ctx.getSource().getSender(), "&6&lFulcrum Scoreboard Debug Commands:");
                    sendMessage(ctx.getSource().getSender(), "&7/fulcrumscoreboard debug list &f- List all registered scoreboards");
                    sendMessage(ctx.getSource().getSender(), "&7/fulcrumscoreboard debug modules <id> &f- Show modules for a scoreboard");
                    sendMessage(ctx.getSource().getSender(), "&7/fulcrumscoreboard debug player <player> &f- Show player's scoreboard state");
                    sendMessage(ctx.getSource().getSender(), "&7/fulcrumscoreboard debug refresh <player> &f- Refresh player's scoreboard");
                    sendMessage(ctx.getSource().getSender(), "&7/fulcrumscoreboard debug toggle <player> &f- Toggle player's scoreboard");
                    sendMessage(ctx.getSource().getSender(), "&7/fulcrumscoreboard debug stats &f- Show system statistics");
                    sendMessage(ctx.getSource().getSender(), "&7/fulcrumscoreboard debug test <player> <id> &f- Test scoreboard on player");
                    sendMessage(ctx.getSource().getSender(), "&7/fulcrumscoreboard debug title <player> <title> &f- Set global title for player");
                    sendMessage(ctx.getSource().getSender(), "&7/fulcrumscoreboard debug title <player> <scoreboard> <title> &f- Set title for player on scoreboard");
                    sendMessage(ctx.getSource().getSender(), "&7/fulcrumscoreboard debug title <player> clear &f- Clear player's global title");
                    sendMessage(ctx.getSource().getSender(), "&7/fulcrumscoreboard debug title <player> <scoreboard> clear &f- Clear player's scoreboard title");
                    sendMessage(ctx.getSource().getSender(), "&7/fulcrumscoreboard debug flash <player> <index> <duration> <lines> [content] &f- Flash module at index");
                    sendMessage(ctx.getSource().getSender(), "&7/fulcrumscoreboard debug flash <player> clear &f- Clear all flashes for player");
                    sendMessage(ctx.getSource().getSender(), "&7/fulcrumscoreboard debug flash <player> clear <index> &f- Clear flash at index");
                    sendMessage(ctx.getSource().getSender(), "&7/fulcrumscoreboard debug flash <player> list &f- List active flashes for player");
                    return 0;
                })
                .build();
    }
    
    private int executeList(CommandSender sender) {
        sendMessage(sender, "&6&l=== Scoreboard Debug: List ===");
        
        Map<String, ScoreboardDefinition> definitions = scoreboardRegistry.getScoreboardMap();
        
        if (definitions.isEmpty()) {
            sendMessage(sender, "&c&lNo scoreboards registered");
            return 0;
        }
        
        sendMessage(sender, "&7Total registered scoreboards: &f" + definitions.size());
        sendMessage(sender, "");
        
        for (Map.Entry<String, ScoreboardDefinition> entry : definitions.entrySet()) {
            ScoreboardDefinition definition = entry.getValue();
            String createdTime = dateFormat.format(new Date(definition.getCreatedTime()));
            
            sendMessage(sender, "&a&l" + definition.getScoreboardId());
            sendMessage(sender, "  &7Title: &f" + (definition.hasTitle() ? definition.getTitle() : "&7<none>"));
            sendMessage(sender, "  &7Modules: &f" + definition.getModuleCount());
            sendMessage(sender, "  &7Created: &f" + createdTime);
            sendMessage(sender, "  &7Status: &a&lActive");
            sendMessage(sender, "");
        }
        
        return 1;
    }
    
    private int executeModules(CommandSender sender, String scoreboardId) {
        ScoreboardDefinition definition = scoreboardRegistry.get(scoreboardId);
        if (definition == null) {
            sendMessage(sender, "&c&lScoreboard not found: &f" + scoreboardId);
            return 0;
        }
        
        sendMessage(sender, "&6&l=== Scoreboard Debug: Modules ===");
        sendMessage(sender, "&7Scoreboard: &f" + scoreboardId);
        sendMessage(sender, "&7Title: &f" + definition.getEffectiveTitle());
        sendMessage(sender, "");
        
        List<ScoreboardModule> modules = definition.getModules();
        
        if (modules.isEmpty()) {
            sendMessage(sender, "&c&lNo modules found");
            return 0;
        }
        
        sendMessage(sender, "&7Total modules: &f" + modules.size());
        sendMessage(sender, "");
        
        for (int i = 0; i < modules.size(); i++) {
            ScoreboardModule module = modules.get(i);
            
            sendMessage(sender, "&a&lModule: &f" + module.getModuleId());
            sendMessage(sender, "  &7Index: &f" + i);
            sendMessage(sender, "  &7Provider: &f" + module.getContentProvider().getClass().getSimpleName());
            sendMessage(sender, "  &7Default Enabled: &f" + (module.isEnabledFor(null) ? "&a&lYes" : "&c&lNo"));
            
            // Show content preview
            try {
                List<String> content = module.getContentProvider().getContent(null);
                if (content != null && !content.isEmpty()) {
                    sendMessage(sender, "  &7Content Preview:");
                    for (int j = 0; j < Math.min(3, content.size()); j++) {
                        sendMessage(sender, "    &7" + (j + 1) + ": &f" + content.get(j));
                    }
                    if (content.size() > 3) {
                        sendMessage(sender, "    &7... and " + (content.size() - 3) + " more lines");
                    }
                } else {
                    sendMessage(sender, "  &7Content: &c&lEmpty");
                }
            } catch (Exception e) {
                sendMessage(sender, "  &7Content: &c&lError: " + e.getMessage());
            }
            
            sendMessage(sender, "");
        }
        
        return 1;
    }
    
    private int executePlayer(CommandSourceStack source, PlayerSelectorArgumentResolver resolver) {
        try {
            Player target = resolver.resolve(source).getFirst();
            CommandSender sender = source.getSender();
            UUID playerId = target.getUniqueId();
            
            sendMessage(sender, "&6&l=== Scoreboard Debug: Player ===");
            sendMessage(sender, "&7Player: &f" + target.getName() + " &7(" + playerId + ")");
            sendMessage(sender, "");
            
            PlayerScoreboardState state = playerManager.getPlayerState(playerId);
            
            if (state == null) {
                sendMessage(sender, "&c&lNo scoreboard state found for player");
                return 0;
            }
            
            // Basic state information
            sendMessage(sender, "&7Current Scoreboard: &f" + (state.hasScoreboard() ? state.getCurrentScoreboardId() : "&c&lNone"));
            sendMessage(sender, "&7Custom Title: &f" + (state.hasCustomTitle() ? state.getCustomTitle() : "&7<none>"));
            sendMessage(sender, "&7Needs Refresh: &f" + (state.needsRefresh() ? "&a&lYes" : "&c&lNo"));
            sendMessage(sender, "&7Created: &f" + dateFormat.format(new Date(state.getCreatedTime())));
            sendMessage(sender, "&7Last Updated: &f" + dateFormat.format(new Date(state.getLastUpdated())));
            sendMessage(sender, "");
            
            // Title override information
            sendMessage(sender, "&7Title Override Information:");
            String globalTitle = titleManager.getPlayerTitle(playerId);
            if (globalTitle != null) {
                sendMessage(sender, "  &7Global Title: &f" + globalTitle);
            } else {
                sendMessage(sender, "  &7Global Title: &7<none>");
            }
            
            if (state.hasScoreboard()) {
                String scoreboardTitle = titleManager.getPlayerScoreboardTitle(playerId, state.getCurrentScoreboardId());
                if (scoreboardTitle != null) {
                    sendMessage(sender, "  &7Current Scoreboard Title: &f" + scoreboardTitle);
                } else {
                    sendMessage(sender, "  &7Current Scoreboard Title: &7<none>");
                }
            }
            
            // Show effective title
            if (state.hasScoreboard()) {
                ScoreboardDefinition definition = scoreboardRegistry.get(state.getCurrentScoreboardId());
                if (definition != null) {
                    String effectiveTitle = titleManager.getEffectiveTitle(playerId, state.getCurrentScoreboardId(), definition.getTitle());
                    sendMessage(sender, "  &7Effective Title: &f" + effectiveTitle);
                }
            }
            sendMessage(sender, "");
            
            // Module overrides
            Map<String, ModuleOverride> overrides = state.getModuleOverrides();
            if (!overrides.isEmpty()) {
                sendMessage(sender, "&7Module Overrides: &f" + overrides.size());
                for (Map.Entry<String, ModuleOverride> entry : overrides.entrySet()) {
                    ModuleOverride override = entry.getValue();
                    sendMessage(sender, "  &7" + entry.getKey() + ": &f" + (override.isEnabled() ? "&a&lEnabled" : "&c&lDisabled"));
                }
                sendMessage(sender, "");
            }
            
            // Flash states
            Map<Integer, PlayerScoreboardState.FlashState> flashes = state.getActiveFlashes();
            if (!flashes.isEmpty()) {
                sendMessage(sender, "&7Active Flashes: &f" + flashes.size());
                for (Map.Entry<Integer, PlayerScoreboardState.FlashState> entry : flashes.entrySet()) {
                    PlayerScoreboardState.FlashState flash = entry.getValue();
                    long remainingTime = flash.getExpirationTime() - System.currentTimeMillis();
                    sendMessage(sender, "  &7Priority " + entry.getKey() + ": &f" + flash.getModule().getModuleId() +
                               " &7(expires in " + (remainingTime / 1000) + "s)");
                }
                sendMessage(sender, "");
            }
            
            // Rendered content preview
            if (state.hasScoreboard()) {
                ScoreboardDefinition definition = scoreboardRegistry.get(state.getCurrentScoreboardId());
                if (definition != null) {
                    try {
                        RenderedScoreboard rendered = renderingPipeline.renderScoreboard(playerId, definition);
                        sendMessage(sender, "&7Rendered Content Preview:");
                        sendMessage(sender, "  &7Title: &f" + rendered.getEffectiveTitle());
                        sendMessage(sender, "  &7Lines: &f" + rendered.getLineCount());
                        if (rendered.wasTruncated()) {
                            sendMessage(sender, "  &7Truncated: &c&lYes &7(" + rendered.getTruncatedLineCount() + " lines removed)");
                        }
                        
                        for (int i = 0; i < Math.min(5, rendered.getContent().size()); i++) {
                            sendMessage(sender, "    &7" + (i + 1) + ": &f" + rendered.getContent().get(i));
                        }
                        if (rendered.getContent().size() > 5) {
                            sendMessage(sender, "    &7... and " + (rendered.getContent().size() - 5) + " more lines");
                        }
                    } catch (Exception e) {
                        sendMessage(sender, "&7Rendered Content: &c&lError: " + e.getMessage());
                    }
                }
            }
            
            return 1;
        } catch (CommandSyntaxException e) {
            sendMessage(source.getSender(), "&c&lError resolving player: " + e.getMessage());
            return 0;
        }
    }
    
    private int executePlayerModules(CommandSourceStack source, PlayerSelectorArgumentResolver resolver) {
        try {
            Player target = resolver.resolve(source).getFirst();
            CommandSender sender = source.getSender();
            UUID playerId = target.getUniqueId();
            
            sendMessage(sender, "&6&l=== Scoreboard Debug: Player Modules ===");
            sendMessage(sender, "&7Player: &f" + target.getName());
            sendMessage(sender, "");
            
            PlayerScoreboardState state = playerManager.getPlayerState(playerId);
            
            if (state == null || !state.hasScoreboard()) {
                sendMessage(sender, "&c&lPlayer has no active scoreboard");
                return 0;
            }
            
            ScoreboardDefinition definition = scoreboardRegistry.get(state.getCurrentScoreboardId());
            if (definition == null) {
                sendMessage(sender, "&c&lScoreboard definition not found: " + state.getCurrentScoreboardId());
                return 0;
            }
            
            List<ScoreboardModule> modules = definition.getModules();
            Map<String, ModuleOverride> overrides = state.getModuleOverrides();
            
            sendMessage(sender, "&7Scoreboard: &f" + state.getCurrentScoreboardId());
            sendMessage(sender, "&7Total modules: &f" + modules.size());
            sendMessage(sender, "");
            
            for (int i = 0; i < modules.size(); i++) {
                ScoreboardModule module = modules.get(i);
                String moduleId = module.getModuleId();
                
                sendMessage(sender, "&a&lModule: &f" + moduleId);
                sendMessage(sender, "  &7Index: &f" + i);
                
                // Check for overrides
                ModuleOverride override = overrides.get(moduleId);
                boolean effectiveEnabled = override != null ? override.isEnabled() : module.isEnabledFor(playerId);
                
                sendMessage(sender, "  &7Base Enabled: &f" + (module.isEnabledFor(playerId) ? "&a&lYes" : "&c&lNo"));
                if (override != null) {
                    sendMessage(sender, "  &7Override: &f" + (override.isEnabled() ? "&a&lEnabled" : "&c&lDisabled"));
                }
                sendMessage(sender, "  &7Effective State: &f" + (effectiveEnabled ? "&a&lEnabled" : "&c&lDisabled"));
                
                // Show effective content after overrides
                if (effectiveEnabled) {
                    try {
                        List<String> content = module.getContentProvider().getContent(playerId);
                        if (content != null && !content.isEmpty()) {
                            sendMessage(sender, "  &7Effective Content:");
                            for (int j = 0; j < Math.min(3, content.size()); j++) {
                                sendMessage(sender, "    &7" + (j + 1) + ": &f" + content.get(j));
                            }
                            if (content.size() > 3) {
                                sendMessage(sender, "    &7... and " + (content.size() - 3) + " more lines");
                            }
                        } else {
                            sendMessage(sender, "  &7Effective Content: &c&lEmpty");
                        }
                    } catch (Exception e) {
                        sendMessage(sender, "  &7Effective Content: &c&lError: " + e.getMessage());
                    }
                } else {
                    sendMessage(sender, "  &7Effective Content: &c&lDisabled");
                }
                
                sendMessage(sender, "");
            }
            
            return 1;
        } catch (CommandSyntaxException e) {
            sendMessage(source.getSender(), "&c&lError resolving player: " + e.getMessage());
            return 0;
        }
    }
    
    private int executeRefresh(CommandSourceStack source, PlayerSelectorArgumentResolver resolver) {
        try {
            Player target = resolver.resolve(source).getFirst();
            CommandSender sender = source.getSender();
            UUID playerId = target.getUniqueId();
            
            PlayerScoreboardState stateBefore = playerManager.getPlayerState(playerId);
            String beforeState = stateBefore != null ? stateBefore.toString() : "null";
            
            sendMessage(sender, "&6&l=== Scoreboard Debug: Refresh ===");
            sendMessage(sender, "&7Player: &f" + target.getName());
            sendMessage(sender, "&7Before: &f" + beforeState);
            
            if (stateBefore == null || !stateBefore.hasScoreboard()) {
                sendMessage(sender, "&c&lPlayer has no active scoreboard to refresh");
                return 0;
            }
            
            // Force refresh
            scoreboardService.refreshPlayerScoreboard(playerId);
            
            PlayerScoreboardState stateAfter = playerManager.getPlayerState(playerId);
            String afterState = stateAfter != null ? stateAfter.toString() : "null";
            
            sendMessage(sender, "&7After: &f" + afterState);
            sendMessage(sender, "&a&lScoreboard refreshed successfully");
            
            return 1;
        } catch (CommandSyntaxException e) {
            sendMessage(source.getSender(), "&c&lError refreshing scoreboard: " + e.getMessage());
            return 0;
        }
    }
    
    private int executeToggle(CommandSourceStack source, PlayerSelectorArgumentResolver resolver) {
        try {
            Player target = resolver.resolve(source).getFirst();
            CommandSender sender = source.getSender();
            UUID playerId = target.getUniqueId();
            
            sendMessage(sender, "&6&l=== Scoreboard Debug: Toggle ===");
            sendMessage(sender, "&7Player: &f" + target.getName());
            
            boolean hadScoreboard = scoreboardService.hasScoreboardDisplayed(playerId);
            
            if (hadScoreboard) {
                scoreboardService.hideScoreboard(playerId);
                sendMessage(sender, "&a&lScoreboard hidden");
            } else {
                // Show default scoreboard if available
                String defaultScoreboard = "default";
                if (scoreboardService.isScoreboardRegistered(defaultScoreboard)) {
                    scoreboardService.showScoreboard(playerId, defaultScoreboard);
                    sendMessage(sender, "&a&lScoreboard shown: " + defaultScoreboard);
                } else {
                    sendMessage(sender, "&c&lNo default scoreboard available to show");
                    return 0;
                }
            }
            
            return 1;
        } catch (CommandSyntaxException e) {
            sendMessage(source.getSender(), "&c&lError toggling scoreboard: " + e.getMessage());
            return 0;
        }
    }
    
    private int executeStats(CommandSender sender) {
        sendMessage(sender, "&6&l=== Scoreboard Debug: Stats ===");
        
        // Registry stats
        Map<String, ScoreboardDefinition> definitions = scoreboardRegistry.getScoreboardMap();
        sendMessage(sender, "&7Total Registered Scoreboards: &f" + definitions.size());
        
        // Player stats
        int activePlayers = playerManager.getActivePlayerCount();
        sendMessage(sender, "&7Active Players with Scoreboards: &f" + activePlayers);
        
        // Online players with scoreboards
        int onlineWithScoreboards = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (scoreboardService.hasScoreboardDisplayed(player.getUniqueId())) {
                onlineWithScoreboards++;
            }
        }
        sendMessage(sender, "&7Online Players with Scoreboards: &f" + onlineWithScoreboards);
        
        // Flash tasks
        int totalFlashes = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerScoreboardState state = playerManager.getPlayerState(player.getUniqueId());
            if (state != null) {
                totalFlashes += state.getActiveFlashes().size();
            }
        }
        sendMessage(sender, "&7Total Active Flash Tasks: &f" + totalFlashes);
        
        // Memory usage (rough estimate)
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory() / (1024 * 1024); // MB
        long freeMemory = runtime.freeMemory() / (1024 * 1024); // MB
        long usedMemory = totalMemory - freeMemory;
        
        sendMessage(sender, "&7System Memory Usage: &f" + usedMemory + "MB / " + totalMemory + "MB");
        
        // Scoreboard breakdown
        sendMessage(sender, "");
        sendMessage(sender, "&7Scoreboard Breakdown:");
        
        if (definitions.isEmpty()) {
            sendMessage(sender, "  &c&lNo scoreboards registered");
        } else {
            for (ScoreboardDefinition def : definitions.values()) {
                int playersUsingIt = 0;
                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                    String currentId = scoreboardService.getCurrentScoreboardId(onlinePlayer.getUniqueId());
                    if (def.getScoreboardId().equals(currentId)) {
                        playersUsingIt++;
                    }
                }
                sendMessage(sender, "  &7" + def.getScoreboardId() + ": &f" + playersUsingIt + " players, " + def.getModuleCount() + " modules");
            }
        }
        
        return 1;
    }
    
    private int executeTest(CommandSourceStack source, PlayerSelectorArgumentResolver resolver, String scoreboardId) {
        try {
            Player target = resolver.resolve(source).getFirst();
            CommandSender sender = source.getSender();
            UUID playerId = target.getUniqueId();
            
            sendMessage(sender, "&6&l=== Scoreboard Debug: Test ===");
            sendMessage(sender, "&7Player: &f" + target.getName());
            sendMessage(sender, "&7Scoreboard: &f" + scoreboardId);
            
            if (!scoreboardService.isScoreboardRegistered(scoreboardId)) {
                sendMessage(sender, "&c&lScoreboard not registered: " + scoreboardId);
                return 0;
            }
            
            String previousScoreboard = scoreboardService.getCurrentScoreboardId(playerId);
            
            // Show the test scoreboard
            scoreboardService.showScoreboard(playerId, scoreboardId);
            
            sendMessage(sender, "&a&lTest scoreboard shown");
            if (previousScoreboard != null) {
                sendMessage(sender, "&7Previous scoreboard: &f" + previousScoreboard);
                sendMessage(sender, "&7Use &f/scoreboard debug test " + target.getName() + " " + previousScoreboard + " &7to restore");
            } else {
                sendMessage(sender, "&7Player had no previous scoreboard");
                sendMessage(sender, "&7Use &f/scoreboard debug toggle " + target.getName() + " &7to hide");
            }
            
            return 1;
        } catch (CommandSyntaxException e) {
            sendMessage(source.getSender(), "&c&lError testing scoreboard: " + e.getMessage());
            return 0;
        }
    }
    
    private int executeSetPlayerTitle(CommandSourceStack source, PlayerSelectorArgumentResolver resolver, String title) {
        try {
            Player target = resolver.resolve(source).getFirst();
            CommandSender sender = source.getSender();
            UUID playerId = target.getUniqueId();
            
            sendMessage(sender, "&6&l=== Scoreboard Debug: Set Player Title ===");
            sendMessage(sender, "&7Player: &f" + target.getName());
            sendMessage(sender, "&7New Title: &f" + title);
            
            // Set the global title for the player
            titleManager.setPlayerTitle(playerId, title);
            
            sendMessage(sender, "&a&lGlobal title set successfully");
            sendMessage(sender, "&7This title will be used on all scoreboards for this player");
            
            // Refresh the player's scoreboard to show the new title
            if (scoreboardService.hasScoreboardDisplayed(playerId)) {
                scoreboardService.refreshPlayerScoreboard(playerId);
                sendMessage(sender, "&7Scoreboard refreshed to show new title");
            }
            
            return 1;
        } catch (CommandSyntaxException e) {
            sendMessage(source.getSender(), "&c&lError setting player title: " + e.getMessage());
            return 0;
        }
    }
    
    private int executeSetPlayerScoreboardTitle(CommandSourceStack source, PlayerSelectorArgumentResolver resolver, String scoreboardId, String title) {
        try {
            Player target = resolver.resolve(source).getFirst();
            CommandSender sender = source.getSender();
            UUID playerId = target.getUniqueId();
            
            sendMessage(sender, "&6&l=== Scoreboard Debug: Set Player Scoreboard Title ===");
            sendMessage(sender, "&7Player: &f" + target.getName());
            sendMessage(sender, "&7Scoreboard: &f" + scoreboardId);
            sendMessage(sender, "&7New Title: &f" + title);
            
            // Validate that the scoreboard exists
            if (!scoreboardService.isScoreboardRegistered(scoreboardId)) {
                sendMessage(sender, "&c&lScoreboard not registered: " + scoreboardId);
                return 0;
            }
            
            // Set the title for the player on the specific scoreboard
            titleManager.setPlayerScoreboardTitle(playerId, scoreboardId, title);
            
            sendMessage(sender, "&a&lScoreboard-specific title set successfully");
            sendMessage(sender, "&7This title will only be used on the '" + scoreboardId + "' scoreboard");
            
            // Refresh the player's scoreboard if they're currently viewing this one
            String currentScoreboardId = scoreboardService.getCurrentScoreboardId(playerId);
            if (scoreboardId.equals(currentScoreboardId)) {
                scoreboardService.refreshPlayerScoreboard(playerId);
                sendMessage(sender, "&7Scoreboard refreshed to show new title");
            }
            
            return 1;
        } catch (CommandSyntaxException e) {
            sendMessage(source.getSender(), "&c&lError setting player scoreboard title: " + e.getMessage());
            return 0;
        }
    }
    
    private int executeClearPlayerTitle(CommandSourceStack source, PlayerSelectorArgumentResolver resolver) {
        try {
            Player target = resolver.resolve(source).getFirst();
            CommandSender sender = source.getSender();
            UUID playerId = target.getUniqueId();
            
            sendMessage(sender, "&6&l=== Scoreboard Debug: Clear Player Title ===");
            sendMessage(sender, "&7Player: &f" + target.getName());
            
            // Check if the player had a global title
            String previousTitle = titleManager.getPlayerTitle(playerId);
            if (previousTitle == null) {
                sendMessage(sender, "&c&lPlayer has no global title to clear");
                return 0;
            }
            
            sendMessage(sender, "&7Previous Title: &f" + previousTitle);
            
            // Clear the global title
            titleManager.clearPlayerTitle(playerId);
            
            sendMessage(sender, "&a&lGlobal title cleared successfully");
            sendMessage(sender, "&7Player will now use default titles or scoreboard-specific titles");
            
            // Refresh the player's scoreboard to show the change
            if (scoreboardService.hasScoreboardDisplayed(playerId)) {
                scoreboardService.refreshPlayerScoreboard(playerId);
                sendMessage(sender, "&7Scoreboard refreshed to show updated title");
            }
            
            return 1;
        } catch (CommandSyntaxException e) {
            sendMessage(source.getSender(), "&c&lError clearing player title: " + e.getMessage());
            return 0;
        }
    }
    
    private int executeClearPlayerScoreboardTitle(CommandSourceStack source, PlayerSelectorArgumentResolver resolver, String scoreboardId) {
        try {
            Player target = resolver.resolve(source).getFirst();
            CommandSender sender = source.getSender();
            UUID playerId = target.getUniqueId();
            
            sendMessage(sender, "&6&l=== Scoreboard Debug: Clear Player Scoreboard Title ===");
            sendMessage(sender, "&7Player: &f" + target.getName());
            sendMessage(sender, "&7Scoreboard: &f" + scoreboardId);
            
            // Validate that the scoreboard exists
            if (!scoreboardService.isScoreboardRegistered(scoreboardId)) {
                sendMessage(sender, "&c&lScoreboard not registered: " + scoreboardId);
                return 0;
            }
            
            // Check if the player had a title for this scoreboard
            String previousTitle = titleManager.getPlayerScoreboardTitle(playerId, scoreboardId);
            if (previousTitle == null) {
                sendMessage(sender, "&c&lPlayer has no title for this scoreboard to clear");
                return 0;
            }
            
            sendMessage(sender, "&7Previous Title: &f" + previousTitle);
            
            // Clear the scoreboard-specific title
            titleManager.clearPlayerScoreboardTitle(playerId, scoreboardId);
            
            sendMessage(sender, "&a&lScoreboard-specific title cleared successfully");
            sendMessage(sender, "&7Player will now use global title or default title for this scoreboard");
            
            // Refresh the player's scoreboard if they're currently viewing this one
            String currentScoreboardId = scoreboardService.getCurrentScoreboardId(playerId);
            if (scoreboardId.equals(currentScoreboardId)) {
                scoreboardService.refreshPlayerScoreboard(playerId);
                sendMessage(sender, "&7Scoreboard refreshed to show updated title");
            }
            
            return 1;
        } catch (CommandSyntaxException e) {
            sendMessage(source.getSender(), "&c&lError clearing player scoreboard title: " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * Creates a test flash module with the specified content.
     */
    private ScoreboardModule createTestFlashModule(String moduleId, int lines, String content) {
        List<String> contentLines = new ArrayList<>();
        
        if (content != null && !content.trim().isEmpty()) {
            // Split content by spaces and distribute across lines
            String[] words = content.split(" ");
            int wordsPerLine = Math.max(1, words.length / lines);
            
            for (int i = 0; i < lines; i++) {
                if (i < words.length) {
                    int startIndex = i * wordsPerLine;
                    int endIndex = Math.min((i + 1) * wordsPerLine, words.length);
                    
                    if (startIndex < words.length) {
                        String line = String.join(" ", Arrays.copyOfRange(words, startIndex, endIndex));
                        contentLines.add("&e" + line);
                    } else {
                        contentLines.add("&cFLASH LINE " + (i + 1));
                    }
                } else {
                    contentLines.add("&cFLASH LINE " + (i + 1));
                }
            }
        } else {
            // Default content
            for (int i = 0; i < lines; i++) {
                contentLines.add("&cFLASH LINE " + (i + 1));
            }
        }
        
        return new ScoreboardModule() {
            @Override
            public String getModuleId() {
                return moduleId;
            }
            
            @Override
            public ContentProvider getContentProvider() {
                return new StaticContentProvider(contentLines);
            }
            
            @Override
            public String getDisplayName() {
                return "Debug Flash Module";
            }
        };
    }
    
    private int executeFlash(CommandSourceStack source, PlayerSelectorArgumentResolver resolver,
                           int index, int duration, int lines, String content) {
        try {
            Player target = resolver.resolve(source).getFirst();
            CommandSender sender = source.getSender();
            UUID playerId = target.getUniqueId();
            
            sendMessage(sender, "&6&l=== Scoreboard Debug: Flash Module ===");
            sendMessage(sender, "&7Player: &f" + target.getName());
            sendMessage(sender, "&7Index: &f" + index);
            sendMessage(sender, "&7Duration: &f" + duration + " seconds");
            sendMessage(sender, "&7Lines: &f" + lines);
            sendMessage(sender, "&7Content: &f" + (content != null ? content : "&7<default>"));
            
            // Validate player has a scoreboard
            if (!scoreboardService.hasScoreboardDisplayed(playerId)) {
                sendMessage(sender, "&c&lPlayer has no active scoreboard to flash on");
                return 0;
            }
            
            // Create flash module
            String moduleId = "debug_flash_" + index + "_" + System.currentTimeMillis();
            ScoreboardModule flashModule = createTestFlashModule(moduleId, lines, content);
            
            // Start flash using module index to avoid conflicts
            int moduleIndex = index;
            Duration flashDuration = Duration.ofSeconds(duration);

            // Create and schedule the flash task for automatic expiration
            ScoreboardFlashTask flashTask = new ScoreboardFlashTask(playerId, moduleIndex, flashModule, playerManager, flashDuration);
            
            // Set the refresh callback to trigger immediate refresh on expiration
            flashTask.setRefreshCallback(scoreboardService::refreshPlayerScoreboard);
            
            // Schedule the task to run after the flash duration
            ScheduledFuture<?> scheduledTask = scheduler.schedule(flashTask, flashDuration.toMillis(), TimeUnit.MILLISECONDS);
            
            // Set the scheduled future on the task for proper cancellation handling
            flashTask.setScheduledFuture(scheduledTask);
            
            // Start the flash with the scheduled task for automatic expiration
            if (playerManager instanceof DefaultPlayerScoreboardManager) {
                ((DefaultPlayerScoreboardManager) playerManager).startFlashWithTask(playerId, moduleIndex, flashModule, flashDuration, scheduledTask);
            } else {
                // Fallback to regular flash if not DefaultPlayerScoreboardManager
                playerManager.startFlash(playerId, moduleIndex, flashModule, flashDuration);
            }
            
            sendMessage(sender, "&a&lFlash started successfully");
            sendMessage(sender, "&7Flash will expire in " + duration + " seconds");
            
            // Refresh player's scoreboard to show the flash
            scoreboardService.refreshPlayerScoreboard(playerId);
            
            return 1;
        } catch (CommandSyntaxException e) {
            sendMessage(source.getSender(), "&c&lError starting flash: " + e.getMessage());
            return 0;
        }
    }
    
    private int executeClearAllFlashes(CommandSourceStack source, PlayerSelectorArgumentResolver resolver) {
        try {
            Player target = resolver.resolve(source).getFirst();
            CommandSender sender = source.getSender();
            UUID playerId = target.getUniqueId();
            
            sendMessage(sender, "&6&l=== Scoreboard Debug: Clear All Flashes ===");
            sendMessage(sender, "&7Player: &f" + target.getName());
            
            PlayerScoreboardState state = playerManager.getPlayerState(playerId);
            if (state == null) {
                sendMessage(sender, "&c&lPlayer has no scoreboard state");
                return 0;
            }
            
            Map<Integer, PlayerScoreboardState.FlashState> activeFlashes = state.getActiveFlashes();
            int flashCount = activeFlashes.size();
            
            if (flashCount == 0) {
                sendMessage(sender, "&c&lPlayer has no active flashes to clear");
                return 0;
            }
            
            // Clear all flashes
            playerManager.stopAllFlashes(playerId);
            
            sendMessage(sender, "&a&lCleared " + flashCount + " active flash(es)");
            
            // Refresh player's scoreboard
            if (scoreboardService.hasScoreboardDisplayed(playerId)) {
                scoreboardService.refreshPlayerScoreboard(playerId);
                sendMessage(sender, "&7Scoreboard refreshed");
            }
            
            return 1;
        } catch (CommandSyntaxException e) {
            sendMessage(source.getSender(), "&c&lError clearing flashes: " + e.getMessage());
            return 0;
        }
    }
    
    private int executeClearFlash(CommandSourceStack source, PlayerSelectorArgumentResolver resolver, int index) {
        try {
            Player target = resolver.resolve(source).getFirst();
            CommandSender sender = source.getSender();
            UUID playerId = target.getUniqueId();
            
            sendMessage(sender, "&6&l=== Scoreboard Debug: Clear Flash ===");
            sendMessage(sender, "&7Player: &f" + target.getName());
            sendMessage(sender, "&7Index: &f" + index);
            
            PlayerScoreboardState state = playerManager.getPlayerState(playerId);
            if (state == null) {
                sendMessage(sender, "&c&lPlayer has no scoreboard state");
                return 0;
            }
            
            // Check if flash exists at this index
            if (!state.hasActiveFlash(index)) {
                sendMessage(sender, "&c&lNo active flash at index " + index);
                return 0;
            }
            
            // Clear the specific flash
            playerManager.stopFlash(playerId, index);
            
            sendMessage(sender, "&a&lFlash at index " + index + " cleared successfully");
            
            // Refresh player's scoreboard
            if (scoreboardService.hasScoreboardDisplayed(playerId)) {
                scoreboardService.refreshPlayerScoreboard(playerId);
                sendMessage(sender, "&7Scoreboard refreshed");
            }
            
            return 1;
        } catch (CommandSyntaxException e) {
            sendMessage(source.getSender(), "&c&lError clearing flash: " + e.getMessage());
            return 0;
        }
    }
    
    private int executeListFlashes(CommandSourceStack source, PlayerSelectorArgumentResolver resolver) {
        try {
            Player target = resolver.resolve(source).getFirst();
            CommandSender sender = source.getSender();
            UUID playerId = target.getUniqueId();
            
            sendMessage(sender, "&6&l=== Scoreboard Debug: List Flashes ===");
            sendMessage(sender, "&7Player: &f" + target.getName());
            sendMessage(sender, "");
            
            PlayerScoreboardState state = playerManager.getPlayerState(playerId);
            if (state == null) {
                sendMessage(sender, "&c&lPlayer has no scoreboard state");
                return 0;
            }
            
            Map<Integer, PlayerScoreboardState.FlashState> activeFlashes = state.getActiveFlashes();
            
            if (activeFlashes.isEmpty()) {
                sendMessage(sender, "&c&lNo active flashes found");
                return 0;
            }
            
            sendMessage(sender, "&7Active Flashes: &f" + activeFlashes.size());
            sendMessage(sender, "");
            
            for (Map.Entry<Integer, PlayerScoreboardState.FlashState> entry : activeFlashes.entrySet()) {
                int moduleIndex = entry.getKey();
                PlayerScoreboardState.FlashState flash = entry.getValue();
                long remainingTime = flash.getExpirationTime() - System.currentTimeMillis();
                
                sendMessage(sender, "&a&lFlash at Index " + moduleIndex + ":");
                sendMessage(sender, "  &7Module: &f" + flash.getModule().getModuleId());
                sendMessage(sender, "  &7Expires in: &f" + (remainingTime / 1000) + " seconds");
                sendMessage(sender, "  &7Expiration: &f" + dateFormat.format(new Date(flash.getExpirationTime())));
                
                // Show flash content preview
                try {
                    List<String> content = flash.getModule().getContentProvider().getContent(playerId);
                    if (content != null && !content.isEmpty()) {
                        sendMessage(sender, "  &7Content Preview:");
                        for (int i = 0; i < Math.min(3, content.size()); i++) {
                            sendMessage(sender, "    &7" + (i + 1) + ": &f" + content.get(i));
                        }
                        if (content.size() > 3) {
                            sendMessage(sender, "    &7... and " + (content.size() - 3) + " more lines");
                        }
                    }
                } catch (Exception e) {
                    sendMessage(sender, "  &7Content: &c&lError: " + e.getMessage());
                }
                
                sendMessage(sender, "");
            }
            
            return 1;
        } catch (CommandSyntaxException e) {
            sendMessage(source.getSender(), "&c&lError listing flashes: " + e.getMessage());
            return 0;
        }
    }
}