package sh.harold.fulcrum.command.runtime;


import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.command.annotations.Argument;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class CommandAutoDiscoveryTest {
    @Test
    void findsAnnotatedCommandClasses() {
        Set<Class<?>> found = CommandScanner.findCommandClasses();
        assertTrue(found.stream().anyMatch(c -> c == TestCommand.class), "Should find TestCommand");
    }

    @Test
    void commandClassHasNoArgConstructorAndImplementsExecutor() {
        Set<Class<?>> found = CommandScanner.findCommandClasses();
        Class<?> clazz = found.stream().filter(c -> c == TestCommand.class).findFirst().orElseThrow();
        assertDoesNotThrow(() -> clazz.getDeclaredConstructor());
        assertTrue(sh.harold.fulcrum.command.CommandExecutor.class.isAssignableFrom(clazz));
    }

    @sh.harold.fulcrum.command.annotations.Command("test")
    public static class TestCommand implements sh.harold.fulcrum.command.CommandExecutor {
        public static final AtomicBoolean executed = new AtomicBoolean(false);
        @Argument("player") public String player;
        @Override
        public void execute(sh.harold.fulcrum.command.CommandContext ctx) {
            executed.set(true);
        }
    }
}
