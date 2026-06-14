package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DataLayerStorePlacementArchitectureTest {
    private static final Set<String> KNOWN_STORES = Set.of(
        "cassandra",
        "kafka",
        "postgresql",
        "valkey",
        "warehouse"
    );

    @Test
    void architectureStorePlacementRowsUseKnownStoreTaxonomy() throws IOException {
        Map<String, Set<String>> placements = architectureStorePlacements();

        assertThat(placements.keySet())
            .contains(
                "Player presence (online, current proxy/server/slot, session id, last-seen)",
                "Live effective ranks (for permission checks)",
                "Live match state (during a match)",
                "Player profile of record (identity, first_seen, total_playtime, slow attributes)",
                "Rank history + audit",
                "Session history",
                "Match history + participant stats",
                "Command audit",
                "Snapshot cache",
                "Per-aggregate state changelog (restore source)"
            );
        placements.forEach((concern, stores) -> {
            assertThat(stores).as(concern + " stores").isNotEmpty();
            assertThat(stores).as(concern + " known stores").allMatch(KNOWN_STORES::contains);
        });
    }

    @Test
    void commandContractsMatchDocumentedStorePlacements() throws IOException {
        Map<String, Set<String>> placements = architectureStorePlacements();

        assertCommandPlacement(
            placements,
            DataAuthority.CommandType.RECORD_PLAYER_LOGIN,
            "Player presence (online, current proxy/server/slot, session id, last-seen)",
            "Player profile of record (identity, first_seen, total_playtime, slow attributes)"
        );
        assertCommandPlacement(
            placements,
            DataAuthority.CommandType.RECORD_PLAYER_LOGOUT,
            "Player presence (online, current proxy/server/slot, session id, last-seen)",
            "Player profile of record (identity, first_seen, total_playtime, slow attributes)"
        );
        assertCommandPlacement(
            placements,
            DataAuthority.CommandType.START_SESSION,
            "Player presence (online, current proxy/server/slot, session id, last-seen)",
            "Session history"
        );
        assertCommandPlacement(
            placements,
            DataAuthority.CommandType.RENEW_SESSION,
            "Player presence (online, current proxy/server/slot, session id, last-seen)",
            "Session history"
        );
        assertCommandPlacement(
            placements,
            DataAuthority.CommandType.END_SESSION,
            "Player presence (online, current proxy/server/slot, session id, last-seen)",
            "Session history"
        );
        assertCommandPlacement(
            placements,
            DataAuthority.CommandType.GRANT_RANK,
            "Live effective ranks (for permission checks)",
            "Rank history + audit"
        );
        assertCommandPlacement(
            placements,
            DataAuthority.CommandType.REVOKE_RANK,
            "Live effective ranks (for permission checks)",
            "Rank history + audit"
        );
        assertCommandPlacement(
            placements,
            DataAuthority.CommandType.RECORD_MATCH_START,
            "Live match state (during a match)",
            "Match history + participant stats"
        );
        assertCommandPlacement(
            placements,
            DataAuthority.CommandType.RECORD_MATCH_END,
            "Live match state (during a match)",
            "Match history + participant stats"
        );
    }

    @Test
    void readContractsMatchDocumentedStorePlacements() throws IOException {
        Map<String, Set<String>> placements = architectureStorePlacements();

        assertReadPlacement(
            placements,
            DataAuthorityReadContracts.ReadType.PLAYER_PROFILE,
            "Player profile of record (identity, first_seen, total_playtime, slow attributes)"
        );
        assertReadPlacement(
            placements,
            DataAuthorityReadContracts.ReadType.PLAYER_RANK,
            "Live effective ranks (for permission checks)"
        );
    }

    private static void assertCommandPlacement(
        Map<String, Set<String>> placements,
        DataAuthority.CommandType type,
        String hotConcern,
        String historyConcern
    ) {
        DataAuthorityCommandContracts.CommandContract contract = DataAuthorityCommandContracts.contract(type);

        assertThat(storesFor(placements, "Command audit"))
            .as(type + " command log store")
            .contains(normalizedStore(contract.commandLogStore()));
        assertThat(storesFor(placements, hotConcern))
            .as(type + " hot projection store")
            .contains(normalizedStore(contract.hotProjectionStore()));
        assertThat(storesFor(placements, historyConcern))
            .as(type + " history store")
            .contains(normalizedStore(contract.historyStore()));
        assertThat(storesFor(placements, "Snapshot cache"))
            .as(type + " cache store")
            .contains(normalizedStore(contract.cacheStore()));
    }

    private static void assertReadPlacement(
        Map<String, Set<String>> placements,
        DataAuthorityReadContracts.ReadType type,
        String servingConcern
    ) {
        DataAuthorityReadContracts.ReadContract contract = DataAuthorityReadContracts.contract(type);

        assertThat(storesFor(placements, servingConcern))
            .as(type + " serving store")
            .contains(normalizedStore(contract.servingStore()));
        assertThat(storesFor(placements, "Snapshot cache"))
            .as(type + " cache store")
            .contains(normalizedStore(contract.cacheStore()));
    }

    private static Set<String> storesFor(Map<String, Set<String>> placements, String concern) {
        assertThat(placements).containsKey(concern);
        return placements.get(concern);
    }

    private static Map<String, Set<String>> architectureStorePlacements() throws IOException {
        Path architecture = architecturePath();
        Map<String, Set<String>> placements = new LinkedHashMap<>();
        boolean inPlacementTable = false;

        for (String line : Files.readAllLines(architecture)) {
            if (line.startsWith("| Concern | Authoritative store |")) {
                inPlacementTable = true;
                continue;
            }
            if (!inPlacementTable) {
                continue;
            }
            if (!line.startsWith("|")) {
                break;
            }
            if (line.contains("---")) {
                continue;
            }

            String[] cells = line.split("\\|", -1);
            if (cells.length < 3) {
                continue;
            }
            placements.put(stripMarkdown(cells[1]), storesIn(cells[2]));
        }

        return placements;
    }

    private static Path architecturePath() {
        Path fromModule = Path.of("..", "refactor", "data-layer-architecture.md");
        if (Files.exists(fromModule)) {
            return fromModule;
        }
        return Path.of("refactor", "data-layer-architecture.md");
    }

    private static Set<String> storesIn(String cell) {
        String normalized = stripMarkdown(cell).toLowerCase(Locale.ROOT);
        Set<String> stores = new LinkedHashSet<>();
        if (normalized.contains("cassandra")) {
            stores.add("cassandra");
        }
        if (normalized.contains("kafka")
            || normalized.contains("cmd.*")
            || normalized.contains("evt.*")
            || normalized.contains("state.*")) {
            stores.add("kafka");
        }
        if (normalized.contains("postgres")) {
            stores.add("postgresql");
        }
        if (normalized.contains("valkey")) {
            stores.add("valkey");
        }
        if (normalized.contains("warehouse")) {
            stores.add("warehouse");
        }
        return Set.copyOf(stores);
    }

    private static String normalizedStore(String store) {
        String normalized = stripMarkdown(store).toLowerCase(Locale.ROOT);
        if (normalized.startsWith("postgresql")) {
            return "postgresql";
        }
        return normalized;
    }

    private static String stripMarkdown(String value) {
        return value.replace("`", "")
            .replace("**", "")
            .trim();
    }
}
