package sh.harold.fulcrum.api.message.scoreboard.command;

import static io.papermc.paper.command.brigadier.Commands.*;
import static io.papermc.paper.command.brigadier.argument.ArgumentTypes.*;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.message.Message;
import sh.harold.fulcrum.api.message.scoreboard.ScoreboardService;
import sh.harold.fulcrum.api.message.scoreboard.module.ScoreboardModule;
import sh.harold.fulcrum.api.message.scoreboard.player.ModuleOverride;
import sh.harold.fulcrum.api.message.scoreboard.player.PlayerScoreboardManager;
import sh.harold.fulcrum.api.message.scoreboard.player.PlayerScoreboardState;
import sh.harold.fulcrum.api.message.scoreboard.registry.ScoreboardDefinition;
import sh.harold.fulcrum.api.message.scoreboard.registry.ScoreboardRegistry;
import sh.harold.fulcrum.api.message.scoreboard.render.RenderedScoreboard;
import sh.harold.fulcrum.api.message.scoreboard.render.RenderingPipeline;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ScoreboardDebugCommand {
    
    private final ScoreboardService scoreboardService;
    private final ScoreboardRegistry scoreboardRegistry;
    private final PlayerScoreboardManager playerManager;
    private final RenderingPipeline renderingPipeline;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    
    public ScoreboardDebugCommand(ScoreboardService scoreboardService, 
                                 ScoreboardRegistry scoreboardRegistry,
                                 PlayerScoreboardManager playerManager,
                                 RenderingPipeline renderingPipeline) {
        this.scoreboardService = scoreboardService;
        this.scoreboardRegistry = scoreboardRegistry;
        this.playerManager = playerManager;
        this.renderingPipeline = renderingPipeline;
    }
    
    public LiteralCommandNode<CommandSourceStack> build() {
        return literal("scoreboard")
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
                                        ctx.getArgument("scoreboardId", String.class)))))))
                .executes(ctx -> {
                    Message.info("scoreboard.debug.usage").send(ctx.getSource().getSender());
                    return 0;
                })
                .build();
    }
    
    private int executeList(CommandSender sender) {
        Message.info("&6&l=== Scoreboard Debug: List ===").send(sender);
        
        Map<String, ScoreboardDefinition> definitions = scoreboardRegistry.getScoreboardMap();
        
        if (definitions.isEmpty()) {
            Message.info("&c&lNo scoreboards registered").send(sender);
            return 0;
        }
        
        Message.info("&7Total registered scoreboards: &f" + definitions.size()).send(sender);
        Message.info("").send(sender);
        
        for (Map.Entry<String, ScoreboardDefinition> entry : definitions.entrySet()) {
            ScoreboardDefinition definition = entry.getValue();
            String createdTime = dateFormat.format(new Date(definition.getCreatedTime()));
            
            Message.info("&a&l" + definition.getScoreboardId()).send(sender);
            Message.info("  &7Title: &f" + (definition.hasTitle() ? definition.getTitle() : "&7<none>")).send(sender);
            Message.info("  &7Modules: &f" + definition.getModuleCount()).send(sender);
            Message.info("  &7Created: &f" + createdTime).send(sender);
            Message.info("  &7Status: &a&lActive").send(sender);
            Message.info("").send(sender);
        }
        
        return 1;
    }
    
    private int executeModules(CommandSender sender, String scoreboardId) {
        ScoreboardDefinition definition = scoreboardRegistry.get(scoreboardId);
        if (definition == null) {
            Message.info("&c&lScoreboard not found: &f" + scoreboardId).send(sender);
            return 0;
        }
        
        Message.info("&6&l=== Scoreboard Debug: Modules ===").send(sender);
        Message.info("&7Scoreboard: &f" + scoreboardId).send(sender);
        Message.info("&7Title: &f" + definition.getEffectiveTitle()).send(sender);
        Message.info("").send(sender);
        
        Map<Integer, ScoreboardModule> modules = definition.getModulesDescending();
        
        if (modules.isEmpty()) {
            Message.info("&c&lNo modules found").send(sender);
            return 0;
        }
        
        Message.info("&7Total modules: &f" + modules.size()).send(sender);
        Message.info("").send(sender);
        
        for (Map.Entry<Integer, ScoreboardModule> entry : modules.entrySet()) {
            int priority = entry.getKey();
            ScoreboardModule module = entry.getValue();
            
            Message.info("&a&lModule: &f" + module.getModuleId()).send(sender);
            Message.info("  &7Priority: &f" + priority).send(sender);
            Message.info("  &7Provider: &f" + module.getContentProvider().getClass().getSimpleName()).send(sender);
            Message.info("  &7Default Enabled: &f" + (module.isEnabledFor(null) ? "&a&lYes" : "&c&lNo")).send(sender);
            
            // Show content preview
            try {
                List<String> content = module.getContentProvider().getContent(null);
                if (content != null && !content.isEmpty()) {
                    Message.info("  &7Content Preview:").send(sender);
                    for (int i = 0; i < Math.min(3, content.size()); i++) {
                        Message.info("    &7" + (i + 1) + ": &f" + content.get(i)).send(sender);
                    }
                    if (content.size() > 3) {
                        Message.info("    &7... and " + (content.size() - 3) + " more lines").send(sender);
                    }
                } else {
                    Message.info("  &7Content: &c&lEmpty").send(sender);
                }
            } catch (Exception e) {
                Message.info("  &7Content: &c&lError: " + e.getMessage()).send(sender);
            }
            
            Message.info("").send(sender);
        }
        
        return 1;
    }
    
    private int executePlayer(CommandSourceStack source, PlayerSelectorArgumentResolver resolver) {
        try {
            Player target = resolver.resolve(source).getFirst();
            CommandSender sender = source.getSender();
            UUID playerId = target.getUniqueId();
            
            Message.info("&6&l=== Scoreboard Debug: Player ===").send(sender);
            Message.info("&7Player: &f" + target.getName() + " &7(" + playerId + ")").send(sender);
            Message.info("").send(sender);
            
            PlayerScoreboardState state = playerManager.getPlayerState(playerId);
            
            if (state == null) {
                Message.info("&c&lNo scoreboard state found for player").send(sender);
                return 0;
            }
            
            // Basic state information
            Message.info("&7Current Scoreboard: &f" + (state.hasScoreboard() ? state.getCurrentScoreboardId() : "&c&lNone")).send(sender);
            Message.info("&7Custom Title: &f" + (state.hasCustomTitle() ? state.getCustomTitle() : "&7<none>")).send(sender);
            Message.info("&7Needs Refresh: &f" + (state.needsRefresh() ? "&a&lYes" : "&c&lNo")).send(sender);
            Message.info("&7Created: &f" + dateFormat.format(new Date(state.getCreatedTime()))).send(sender);
            Message.info("&7Last Updated: &f" + dateFormat.format(new Date(state.getLastUpdated()))).send(sender);
            Message.info("").send(sender);
            
            // Module overrides
            Map<String, ModuleOverride> overrides = state.getModuleOverrides();
            if (!overrides.isEmpty()) {
                Message.info("&7Module Overrides: &f" + overrides.size()).send(sender);
                for (Map.Entry<String, ModuleOverride> entry : overrides.entrySet()) {
                    ModuleOverride override = entry.getValue();
                    Message.info("  &7" + entry.getKey() + ": &f" + (override.isEnabled() ? "&a&lEnabled" : "&c&lDisabled")).send(sender);
                }
                Message.info("").send(sender);
            }
            
            // Flash states
            Map<Integer, PlayerScoreboardState.FlashState> flashes = state.getActiveFlashes();
            if (!flashes.isEmpty()) {
                Message.info("&7Active Flashes: &f" + flashes.size()).send(sender);
                for (Map.Entry<Integer, PlayerScoreboardState.FlashState> entry : flashes.entrySet()) {
                    PlayerScoreboardState.FlashState flash = entry.getValue();
                    long remainingTime = flash.getExpirationTime() - System.currentTimeMillis();
                    Message.info("  &7Priority " + entry.getKey() + ": &f" + flash.getModule().getModuleId() + 
                               " &7(expires in " + (remainingTime / 1000) + "s)").send(sender);
                }
                Message.info("").send(sender);
            }
            
            // Rendered content preview
            if (state.hasScoreboard()) {
                ScoreboardDefinition definition = scoreboardRegistry.get(state.getCurrentScoreboardId());
                if (definition != null) {
                    try {
                        RenderedScoreboard rendered = renderingPipeline.renderScoreboard(playerId, definition);
                        Message.info("&7Rendered Content Preview:").send(sender);
                        Message.info("  &7Title: &f" + rendered.getEffectiveTitle()).send(sender);
                        Message.info("  &7Lines: &f" + rendered.getLineCount()).send(sender);
                        if (rendered.wasTruncated()) {
                            Message.info("  &7Truncated: &c&lYes &7(" + rendered.getTruncatedLineCount() + " lines removed)").send(sender);
                        }
                        
                        for (int i = 0; i < Math.min(5, rendered.getContent().size()); i++) {
                            Message.info("    &7" + (i + 1) + ": &f" + rendered.getContent().get(i)).send(sender);
                        }
                        if (rendered.getContent().size() > 5) {
                            Message.info("    &7... and " + (rendered.getContent().size() - 5) + " more lines").send(sender);
                        }
                    } catch (Exception e) {
                        Message.info("&7Rendered Content: &c&lError: " + e.getMessage()).send(sender);
                    }
                }
            }
            
            return 1;
        } catch (CommandSyntaxException e) {
            Message.info("&c&lError resolving player: " + e.getMessage()).send(source.getSender());
            return 0;
        }
    }
    
    private int executePlayerModules(CommandSourceStack source, PlayerSelectorArgumentResolver resolver) {
        try {
            Player target = resolver.resolve(source).getFirst();
            CommandSender sender = source.getSender();
            UUID playerId = target.getUniqueId();
            
            Message.info("&6&l=== Scoreboard Debug: Player Modules ===").send(sender);
            Message.info("&7Player: &f" + target.getName()).send(sender);
            Message.info("").send(sender);
            
            PlayerScoreboardState state = playerManager.getPlayerState(playerId);
            
            if (state == null || !state.hasScoreboard()) {
                Message.info("&c&lPlayer has no active scoreboard").send(sender);
                return 0;
            }
            
            ScoreboardDefinition definition = scoreboardRegistry.get(state.getCurrentScoreboardId());
            if (definition == null) {
                Message.info("&c&lScoreboard definition not found: " + state.getCurrentScoreboardId()).send(sender);
                return 0;
            }
            
            Map<Integer, ScoreboardModule> modules = definition.getModulesDescending();
            Map<String, ModuleOverride> overrides = state.getModuleOverrides();
            
            Message.info("&7Scoreboard: &f" + state.getCurrentScoreboardId()).send(sender);
            Message.info("&7Total modules: &f" + modules.size()).send(sender);
            Message.info("").send(sender);
            
            for (Map.Entry<Integer, ScoreboardModule> entry : modules.entrySet()) {
                int priority = entry.getKey();
                ScoreboardModule module = entry.getValue();
                String moduleId = module.getModuleId();
                
                Message.info("&a&lModule: &f" + moduleId).send(sender);
                Message.info("  &7Priority: &f" + priority).send(sender);
                
                // Check for overrides
                ModuleOverride override = overrides.get(moduleId);
                boolean effectiveEnabled = override != null ? override.isEnabled() : module.isEnabledFor(playerId);
                
                Message.info("  &7Base Enabled: &f" + (module.isEnabledFor(playerId) ? "&a&lYes" : "&c&lNo")).send(sender);
                if (override != null) {
                    Message.info("  &7Override: &f" + (override.isEnabled() ? "&a&lEnabled" : "&c&lDisabled")).send(sender);
                }
                Message.info("  &7Effective State: &f" + (effectiveEnabled ? "&a&lEnabled" : "&c&lDisabled")).send(sender);
                
                // Show effective content after overrides
                if (effectiveEnabled) {
                    try {
                        List<String> content = module.getContentProvider().getContent(playerId);
                        if (content != null && !content.isEmpty()) {
                            Message.info("  &7Effective Content:").send(sender);
                            for (int i = 0; i < Math.min(3, content.size()); i++) {
                                Message.info("    &7" + (i + 1) + ": &f" + content.get(i)).send(sender);
                            }
                            if (content.size() > 3) {
                                Message.info("    &7... and " + (content.size() - 3) + " more lines").send(sender);
                            }
                        } else {
                            Message.info("  &7Effective Content: &c&lEmpty").send(sender);
                        }
                    } catch (Exception e) {
                        Message.info("  &7Effective Content: &c&lError: " + e.getMessage()).send(sender);
                    }
                } else {
                    Message.info("  &7Effective Content: &c&lDisabled").send(sender);
                }
                
                Message.info("").send(sender);
            }
            
            return 1;
        } catch (CommandSyntaxException e) {
            Message.info("&c&lError resolving player: " + e.getMessage()).send(source.getSender());
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
            
            Message.info("&6&l=== Scoreboard Debug: Refresh ===").send(sender);
            Message.info("&7Player: &f" + target.getName()).send(sender);
            Message.info("&7Before: &f" + beforeState).send(sender);
            
            if (stateBefore == null || !stateBefore.hasScoreboard()) {
                Message.info("&c&lPlayer has no active scoreboard to refresh").send(sender);
                return 0;
            }
            
            // Force refresh
            scoreboardService.refreshPlayerScoreboard(playerId);
            
            PlayerScoreboardState stateAfter = playerManager.getPlayerState(playerId);
            String afterState = stateAfter != null ? stateAfter.toString() : "null";
            
            Message.info("&7After: &f" + afterState).send(sender);
            Message.info("&a&lScoreboard refreshed successfully").send(sender);
            
            return 1;
        } catch (CommandSyntaxException e) {
            Message.info("&c&lError refreshing scoreboard: " + e.getMessage()).send(source.getSender());
            return 0;
        }
    }
    
    private int executeToggle(CommandSourceStack source, PlayerSelectorArgumentResolver resolver) {
        try {
            Player target = resolver.resolve(source).getFirst();
            CommandSender sender = source.getSender();
            UUID playerId = target.getUniqueId();
            
            Message.info("&6&l=== Scoreboard Debug: Toggle ===").send(sender);
            Message.info("&7Player: &f" + target.getName()).send(sender);
            
            boolean hadScoreboard = scoreboardService.hasScoreboardDisplayed(playerId);
            
            if (hadScoreboard) {
                scoreboardService.hideScoreboard(playerId);
                Message.info("&a&lScoreboard hidden").send(sender);
            } else {
                // Show default scoreboard if available
                String defaultScoreboard = "default";
                if (scoreboardService.isScoreboardRegistered(defaultScoreboard)) {
                    scoreboardService.showScoreboard(playerId, defaultScoreboard);
                    Message.info("&a&lScoreboard shown: " + defaultScoreboard).send(sender);
                } else {
                    Message.info("&c&lNo default scoreboard available to show").send(sender);
                    return 0;
                }
            }
            
            return 1;
        } catch (CommandSyntaxException e) {
            Message.info("&c&lError toggling scoreboard: " + e.getMessage()).send(source.getSender());
            return 0;
        }
    }
    
    private int executeStats(CommandSender sender) {
        Message.info("&6&l=== Scoreboard Debug: Stats ===").send(sender);
        
        // Registry stats
        Map<String, ScoreboardDefinition> definitions = scoreboardRegistry.getScoreboardMap();
        Message.info("&7Total Registered Scoreboards: &f" + definitions.size()).send(sender);
        
        // Player stats
        int activePlayers = playerManager.getActivePlayerCount();
        Message.info("&7Active Players with Scoreboards: &f" + activePlayers).send(sender);
        
        // Online players with scoreboards
        int onlineWithScoreboards = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (scoreboardService.hasScoreboardDisplayed(player.getUniqueId())) {
                onlineWithScoreboards++;
            }
        }
        Message.info("&7Online Players with Scoreboards: &f" + onlineWithScoreboards).send(sender);
        
        // Flash tasks
        int totalFlashes = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerScoreboardState state = playerManager.getPlayerState(player.getUniqueId());
            if (state != null) {
                totalFlashes += state.getActiveFlashes().size();
            }
        }
        Message.info("&7Total Active Flash Tasks: &f" + totalFlashes).send(sender);
        
        // Memory usage (rough estimate)
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory() / (1024 * 1024); // MB
        long freeMemory = runtime.freeMemory() / (1024 * 1024); // MB
        long usedMemory = totalMemory - freeMemory;
        
        Message.info("&7System Memory Usage: &f" + usedMemory + "MB / " + totalMemory + "MB").send(sender);
        
        // Scoreboard breakdown
        Message.info("").send(sender);
        Message.info("&7Scoreboard Breakdown:").send(sender);
        
        if (definitions.isEmpty()) {
            Message.info("  &c&lNo scoreboards registered").send(sender);
        } else {
            for (ScoreboardDefinition def : definitions.values()) {
                int playersUsingIt = 0;
                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                    String currentId = scoreboardService.getCurrentScoreboardId(onlinePlayer.getUniqueId());
                    if (def.getScoreboardId().equals(currentId)) {
                        playersUsingIt++;
                    }
                }
                Message.info("  &7" + def.getScoreboardId() + ": &f" + playersUsingIt + " players, " + def.getModuleCount() + " modules").send(sender);
            }
        }
        
        return 1;
    }
    
    private int executeTest(CommandSourceStack source, PlayerSelectorArgumentResolver resolver, String scoreboardId) {
        try {
            Player target = resolver.resolve(source).getFirst();
            CommandSender sender = source.getSender();
            UUID playerId = target.getUniqueId();
            
            Message.info("&6&l=== Scoreboard Debug: Test ===").send(sender);
            Message.info("&7Player: &f" + target.getName()).send(sender);
            Message.info("&7Scoreboard: &f" + scoreboardId).send(sender);
            
            if (!scoreboardService.isScoreboardRegistered(scoreboardId)) {
                Message.info("&c&lScoreboard not registered: " + scoreboardId).send(sender);
                return 0;
            }
            
            String previousScoreboard = scoreboardService.getCurrentScoreboardId(playerId);
            
            // Show the test scoreboard
            scoreboardService.showScoreboard(playerId, scoreboardId);
            
            Message.info("&a&lTest scoreboard shown").send(sender);
            if (previousScoreboard != null) {
                Message.info("&7Previous scoreboard: &f" + previousScoreboard).send(sender);
                Message.info("&7Use &f/scoreboard debug test " + target.getName() + " " + previousScoreboard + " &7to restore").send(sender);
            } else {
                Message.info("&7Player had no previous scoreboard").send(sender);
                Message.info("&7Use &f/scoreboard debug toggle " + target.getName() + " &7to hide").send(sender);
            }
            
            return 1;
        } catch (CommandSyntaxException e) {
            Message.info("&c&lError testing scoreboard: " + e.getMessage()).send(source.getSender());
            return 0;
        }
    }
}