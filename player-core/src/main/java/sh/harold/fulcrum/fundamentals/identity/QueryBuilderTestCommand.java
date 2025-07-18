package sh.harold.fulcrum.fundamentals.identity;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import sh.harold.fulcrum.api.data.backend.core.AutoTableSchema;
import sh.harold.fulcrum.api.data.query.CrossSchemaQueryBuilder;
import sh.harold.fulcrum.api.data.query.CrossSchemaResult;
import sh.harold.fulcrum.api.data.query.QueryFilter;
import sh.harold.fulcrum.api.message.Message;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.papermc.paper.command.brigadier.Commands.literal;

/**
 * Command to test CrossSchemaQueryBuilder functionality by querying players
 * with specific rank criteria: functionalrank="ADMIN" and monthlypackagerank="MVP_PLUS_PLUS"
 */
public class QueryBuilderTestCommand {

    public LiteralCommandNode<CommandSourceStack> build() {
        return literal("querytest")
                .then(literal("qttest")) // Alternative command alias
                .requires(source -> source.getExecutor().isOp())
                .executes(ctx -> {
                    long startTime = System.currentTimeMillis();

                    Message.info("query.starting").send(ctx.getSource().getSender());

                    try {
                        // Create query using CrossSchemaQueryBuilder from PlayerDataRegistry
                        AutoTableSchema<IdentityData> identitySchema = new AutoTableSchema<>(IdentityData.class);

                        CompletableFuture<List<CrossSchemaResult>> queryFuture = CrossSchemaQueryBuilder
                                .from(identitySchema)
                                .where(QueryFilter.equals("functionalRank", "ADMIN", identitySchema))
                                .where(QueryFilter.equals("monthlyPackageRank", "MVP_PLUS_PLUS", identitySchema))
                                .executeAsync();

                        // Add timeout handling
                        CompletableFuture<List<CrossSchemaResult>> timeoutFuture = queryFuture
                                .orTimeout(30, TimeUnit.SECONDS);

                        timeoutFuture.thenAccept(results -> {
                            long executionTime = System.currentTimeMillis() - startTime;

                            // Process results on main thread to ensure thread safety
                            Bukkit.getScheduler().runTask(
                                    Bukkit.getPluginManager().getPlugin("Fulcrum"),
                                    () -> handleQueryResults(ctx.getSource(), results, executionTime)
                            );
                        }).exceptionally(throwable -> {
                            // Handle errors on main thread
                            Bukkit.getScheduler().runTask(
                                    Bukkit.getPluginManager().getPlugin("Fulcrum"),
                                    () -> handleQueryError(ctx.getSource(), throwable)
                            );
                            return null;
                        });

                    } catch (Exception e) {
                        Message.error("query.error.initialization", e.getMessage()).send(ctx.getSource().getSender());
                        e.printStackTrace();
                        return 0;
                    }

                    return 1;
                })
                .build();
    }

    /**
     * Handles successful query results by displaying them to the command sender
     */
    private void handleQueryResults(CommandSourceStack source, List<CrossSchemaResult> results, long executionTime) {
        try {
            // Display result count
            Message.success("query.results.completed", results.size())
                    .send(source.getSender());

            if (results.isEmpty()) {
                Message.info("query.results.empty")
                        .send(source.getSender());
            } else {
                Message.info("query.results.header")
                        .send(source.getSender());

                // Display each matching player
                for (int i = 0; i < results.size(); i++) {
                    CrossSchemaResult result = results.get(i);
                    try {
                        // Extract data using field access since we can't use the schema directly
                        String playerName = (String) result.getField("displayname");
                        if (playerName == null) playerName = "Unknown";
                        String uuid = result.getPlayerUuid().toString();

                        Message.info("query.results.player", (i + 1), playerName, uuid)
                                .send(source.getSender());

                        // Show additional rank info using field access
                        Object functionalRankObj = result.getField("functionalRank");
                        Object monthlyRankObj = result.getField("monthlyPackageRank");
                        Object packageRankObj = result.getField("packageRank");

                        String functionalRank = functionalRankObj != null ? functionalRankObj.toString() : "None";
                        String monthlyRank = monthlyRankObj != null ? monthlyRankObj.toString() : "None";
                        String packageRank = packageRankObj != null ? packageRankObj.toString() : "DEFAULT";

                        Message.info("query.results.ranks", functionalRank, monthlyRank, packageRank)
                                .send(source.getSender());

                    } catch (Exception e) {
                        Message.error("query.error.processing", (i + 1), e.getMessage())
                                .send(source.getSender());
                    }
                }
            }

            // Display execution time
            Message.success("query.execution.time", executionTime)
                    .send(source.getSender());

        } catch (Exception e) {
            Message.error("query.error.display", e.getMessage()).send(source.getSender());
            e.printStackTrace();
        }
    }

    /**
     * Handles query errors by displaying appropriate error messages
     */
    private void handleQueryError(CommandSourceStack source, Throwable throwable) {
        String errorMessage = throwable.getMessage();

        if (throwable instanceof java.util.concurrent.TimeoutException) {
            Message.error("query.error.timeout")
                    .send(source.getSender());
        } else if (throwable.getCause() instanceof java.sql.SQLException) {
            Message.error("query.error.database", errorMessage)
                    .send(source.getSender());
        } else if (throwable instanceof IllegalStateException) {
            Message.error("query.error.configuration", errorMessage)
                    .send(source.getSender());
        } else {
            Message.error("query.error.general", errorMessage)
                    .send(source.getSender());
        }

        // Log the full stack trace for debugging
        throwable.printStackTrace();
        Message.info("query.error.check.console").send(source.getSender());
    }
}