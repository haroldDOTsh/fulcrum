package sh.harold.fulcrum.command.annotations;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.command.CommandContext;
import sh.harold.fulcrum.command.CommandExecutor;

import static org.junit.jupiter.api.Assertions.*;

@Command("spawn")
@Aliases({"hub", "lobby"})
@Rank(RankLevel.ADMIN)
class TestSpawnCommand implements CommandExecutor {
    @Override
    public void execute(CommandContext ctx) {
        // no-op
    }
}

public class CommandAnnotationsTest {
    @Test
    void sanityCheck() {
        assertTrue(true);
    }

    @Test
    void testAnnotationsPresentAndCorrect() {
        var clazz = TestSpawnCommand.class;
        Command command = clazz.getAnnotation(Command.class);
        assertNotNull(command);
        assertEquals("spawn", command.value());

        Aliases aliases = clazz.getAnnotation(Aliases.class);
        assertNotNull(aliases);
        assertArrayEquals(new String[]{"hub", "lobby"}, aliases.value());

        Rank rank = clazz.getAnnotation(Rank.class);
        assertNotNull(rank);
        assertEquals(RankLevel.ADMIN, rank.value());
    }

    @Test
    void testImplementsCommandExecutor() {
        assertTrue(CommandExecutor.class.isAssignableFrom(TestSpawnCommand.class));
    }
}
