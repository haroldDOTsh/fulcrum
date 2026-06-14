package sh.harold.fulcrum.runtime.threading;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadBoundaryStaticTest {
    private static final Path MAIN_SOURCES = Path.of("src/main/java");

    @Test
    void paperHotPathsUseRuntimeBoundaryInsteadOfAdHocAsync() throws IOException {
        List<String> failures = new ArrayList<>();
        List<String> forbidden = List.of(
            "runTaskAsynchronously",
            "CompletableFuture.runAsync",
            "CompletableFuture.supplyAsync"
        );

        for (String source : managedPaperHotPaths()) {
            String text = readSource(source);
            for (String fragment : forbidden) {
                if (text.contains(fragment)) {
                    failures.add(source + " contains " + fragment);
                }
            }
        }

        assertTrue(failures.isEmpty(), String.join(System.lineSeparator(), failures));
    }

    @Test
    void migratedFlowsDoNotBlockOnNestedRuntimeFutures() throws IOException {
        String rankFeature = readSource("sh/harold/fulcrum/fundamentals/rank/RankFeature.java");
        assertFalse(rankFeature.contains("savePlayerRanks(playerId).join()"));
        assertFalse(rankFeature.contains("setPrimaryRank(playerId, Rank.DEFAULT).join()"));

        String worldManager = readSource("sh/harold/fulcrum/api/world/impl/DefaultWorldManager.java");
        assertFalse(worldManager.contains(".join()"));
    }

    @Test
    void worldFeatureUsesRemoteWorldMapStore() throws IOException {
        String worldFeature = readSource("sh/harold/fulcrum/fundamentals/world/WorldFeature.java");

        assertTrue(worldFeature.contains("MessageBusWorldMapStoreClient"));
        assertFalse(worldFeature.contains("PostgresConnectionAdapter"));
        assertFalse(worldFeature.contains("createStandalonePostgresAdapter"));
        assertFalse(worldFeature.contains("postgres.jdbc-url"));
    }

    @Test
    void paperRankFeatureUsesQuotedAuthorityReads() throws IOException {
        String rankFeature = readSource("sh/harold/fulcrum/fundamentals/rank/RankFeature.java");

        assertTrue(rankFeature.contains("rankReader.quoteRanks"));
        assertFalse(rankFeature.contains("rankReader.findRanks"));
    }

    @Test
    void minigameMatchesRequireAuthorityCommandPort() throws IOException {
        String minigameFeature = readSource("sh/harold/fulcrum/minigame/MinigameEngineFeature.java");
        String minigameEngine = readSource("sh/harold/fulcrum/minigame/MinigameEngine.java");

        assertTrue(minigameFeature.contains("container.get(DataAuthority.CommandPort.class)"));
        assertTrue(minigameFeature.contains("DataAuthority.CommandPort.class"));
        assertFalse(minigameFeature.contains("minigame match logs will not be persisted"));
        assertTrue(minigameEngine.contains("DataAuthority.CommandType.RECORD_MATCH_START"));
        assertTrue(minigameEngine.contains("DataAuthority.CommandType.RECORD_MATCH_END"));
    }

    private List<String> managedPaperHotPaths() {
        return List.of(
            "sh/harold/fulcrum/fundamentals/data/DataAuthorityFeature.java",
            "sh/harold/fulcrum/fundamentals/playerdata/PlayerDataFeature.java",
            "sh/harold/fulcrum/fundamentals/rank/RankFeature.java",
            "sh/harold/fulcrum/fundamentals/messagebus/MessageBusFeature.java",
            "sh/harold/fulcrum/fundamentals/messagebus/PaperMessageBusAdapter.java",
            "sh/harold/fulcrum/fundamentals/lifecycle/ServerLifecycleFeature.java",
            "sh/harold/fulcrum/fundamentals/world/WorldFeature.java",
            "sh/harold/fulcrum/fundamentals/world/WorldManager.java",
            "sh/harold/fulcrum/fundamentals/creative/CreativeLibraryFeature.java",
            "sh/harold/fulcrum/api/world/impl/DefaultWorldManager.java",
            "sh/harold/fulcrum/api/world/paste/impl/FAWEWorldPaster.java",
            "sh/harold/fulcrum/minigame/MinigameEngine.java",
            "sh/harold/fulcrum/minigame/environment/MinigameEnvironmentService.java"
        );
    }

    private String readSource(String relativePath) throws IOException {
        return Files.readString(MAIN_SOURCES.resolve(relativePath));
    }
}
