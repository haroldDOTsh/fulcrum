package sh.harold.fulcrum.command;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.command.annotations.*;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Command("test")
@Aliases({"t", "debug"})
@Rank(RankLevel.ADMIN)
class TestCommand implements CommandExecutor {
    @Override
    public void execute(CommandContext ctx) {}
}

public class CommandScannerTest {
    @Test
    void testScanSingleCommand() {
        var defs = CommandScanner.scan(Set.of(TestCommand.class));
        assertEquals(1, defs.size());
        var def = defs.get(0);
        assertEquals("test", def.name());
        assertArrayEquals(new String[]{"t", "debug"}, def.aliases());
        assertEquals(RankLevel.ADMIN, def.requiredRank());
        assertEquals(TestCommand.class, def.implementationClass());
    }

    @Test
    void testScanIgnoresNonCommand() {
        class NotACommand {}
        var defs = CommandScanner.scan(Set.of(NotACommand.class));
        assertTrue(defs.isEmpty());
    }
}
