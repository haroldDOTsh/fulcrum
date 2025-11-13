package sh.harold.fulcrum.registry.console.commands;

import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankChangeContext;
import sh.harold.fulcrum.api.rank.RankMutationType;
import sh.harold.fulcrum.registry.console.CommandHandler;
import sh.harold.fulcrum.registry.rank.RankMutationService;
import sh.harold.fulcrum.registry.rank.RankMutationService.RankSnapshot;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record RankCommand(RankMutationService rankMutationService) implements CommandHandler {

    public RankCommand {
        Objects.requireNonNull(rankMutationService, "rankMutationService");
    }

    @Override
    public boolean execute(String[] args) {
        if (rankMutationService == null) {
            System.out.println("Rank mutation service is unavailable.");
            return false;
        }

        if (args.length < 3) {
            printUsage();
            return false;
        }

        String action = args[1].toLowerCase();
        UUID playerId;
        try {
            playerId = UUID.fromString(args[2]);
        } catch (IllegalArgumentException ex) {
            System.out.println("Invalid player UUID: " + args[2]);
            return false;
        }

        String playerName = extractName(args);
        RankChangeContext context = RankChangeContext.ofConsole("registry-console");

        try {
            return switch (action) {
                case "info", "list" -> handleInfo(playerId);
                case "set" ->
                        handleMutation(playerId, RankMutationType.SET_PRIMARY, parseRank(args, 3), context, playerName);
                case "add" -> handleMutation(playerId, RankMutationType.ADD, parseRank(args, 3), context, playerName);
                case "remove" ->
                        handleMutation(playerId, RankMutationType.REMOVE, parseRank(args, 3), context, playerName);
                case "reset" -> handleMutation(playerId, RankMutationType.RESET, null, context, playerName);
                default -> {
                    System.out.println("Unknown action: " + action);
                    printUsage();
                    yield false;
                }
            };
        } catch (IllegalArgumentException ex) {
            System.out.println(ex.getMessage());
            return false;
        }
    }

    private boolean handleInfo(UUID playerId) {
        Optional<RankSnapshot> snapshot = rankMutationService.getRankSnapshot(playerId);
        if (snapshot.isEmpty()) {
            System.out.println("No rank data found for player " + playerId);
            return false;
        }
        RankSnapshot data = snapshot.get();
        System.out.println("Primary Rank: " + data.primary());
        System.out.println("All Ranks: " + (data.ranks().isEmpty() ? "(none)" : data.ranks()));
        return true;
    }

    private boolean handleMutation(UUID playerId,
                                   RankMutationType type,
                                   Rank rank,
                                   RankChangeContext context,
                                   String playerName) {
        if ((type == RankMutationType.SET_PRIMARY || type == RankMutationType.ADD || type == RankMutationType.REMOVE)
                && rank == null) {
            throw new IllegalArgumentException("Rank argument is required for " + type.name().toLowerCase());
        }

        var response = rankMutationService.mutateDirect(playerId, type, rank, context, playerName);
        if (!response.isSuccess()) {
            System.out.println("Rank mutation failed: " + response.getError());
            return false;
        }
        System.out.println("Rank mutation successful. Primary: " + response.getPrimaryRankId()
                + " Ranks: " + response.getRankIds());
        return true;
    }

    private Rank parseRank(String[] args, int index) {
        if (args.length <= index) {
            throw new IllegalArgumentException("Rank argument missing");
        }
        String rankName = args[index].toUpperCase();
        try {
            return Rank.valueOf(rankName);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown rank: " + rankName);
        }
    }

    private String extractName(String[] args) {
        for (int i = 3; i < args.length - 1; i++) {
            if ("--name".equalsIgnoreCase(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }

    private void printUsage() {
        System.out.println("Usage: rank <set|add|remove|reset|info> <playerUuid> [rank] [--name <playerName>]");
    }

    @Override
    public String getName() {
        return "rank";
    }

    @Override
    public String[] getAliases() {
        return new String[]{"rank"};
    }

    @Override
    public String getDescription() {
        return "Mutate player ranks through the registry";
    }

    @Override
    public String getUsage() {
        return "rank <set|add|remove|reset|info> <playerUuid> [rank] [--name <playerName>]";
    }
}
