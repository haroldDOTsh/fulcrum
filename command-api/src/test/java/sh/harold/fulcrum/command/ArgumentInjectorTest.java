package sh.harold.fulcrum.command;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MinimalSender implements org.bukkit.command.CommandSender {
    @Override
    public void sendMessage(String message) {
    }

    @Override
    public void sendMessage(String[] messages) {
    }

    @Override
    public void sendMessage(java.util.UUID sender, String message) {
    }

    @Override
    public void sendMessage(java.util.UUID sender, String[] messages) {
    }

    @Override
    public org.bukkit.Server getServer() {
        return null;
    }

    @Override
    public String getName() {
        return "dummy";
    }

    @Override
    public boolean isOp() {
        return false;
    }

    @Override
    public void setOp(boolean value) {
    }

    @Override
    public boolean isPermissionSet(String name) {
        return false;
    }

    @Override
    public boolean isPermissionSet(org.bukkit.permissions.Permission perm) {
        return false;
    }

    @Override
    public boolean hasPermission(String name) {
        return false;
    }

    @Override
    public boolean hasPermission(org.bukkit.permissions.Permission perm) {
        return false;
    }

    @Override
    public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin, String name, boolean value) {
        return null;
    }

    @Override
    public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin) {
        return null;
    }

    @Override
    public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin, String name, boolean value, int ticks) {
        return null;
    }

    @Override
    public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin, int ticks) {
        return null;
    }

    @Override
    public void removeAttachment(org.bukkit.permissions.PermissionAttachment attachment) {
    }

    @Override
    public void recalculatePermissions() {
    }

    @Override
    public java.util.Set<org.bukkit.permissions.PermissionAttachmentInfo> getEffectivePermissions() {
        return java.util.Collections.emptySet();
    }

    // Paper API 1.21.6+ methods
    @Override
    public Component name() {
        return Component.text("dummy");
    }

    @Override
    public org.bukkit.command.CommandSender.Spigot spigot() {
        throw new UnsupportedOperationException();
    }
}

class ArgumentInjectorTest {
    private static Object getPrivate(Object obj, String field) {
        try {
            var f = obj.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Exception e) {
            return null;
        }
    }

    @Test
    void basicArgumentInjection() {
        var cmd = new TestCommand();
        var ctx = new FakeCommandContext(Map.of("name", "Alice", "age", 25, "privateField", "secret"));
        ArgumentInjector.inject(ctx, cmd);
        assertEquals("Alice", cmd.name);
        assertEquals(25, cmd.age);
        assertNull(cmd.id);
        assertEquals("secret", getPrivate(cmd, "privateField"));
    }

    @Test
    void missingArgument() {
        var cmd = new TestCommand();
        var ctx = new FakeCommandContext(Map.of("name", "Bob"));
        ArgumentInjector.inject(ctx, cmd);
        assertEquals("Bob", cmd.name);
        assertEquals(0, cmd.age);
        assertNull(cmd.id);
        assertNull(getPrivate(cmd, "privateField"));
    }

    @Test
    void unsupportedArgumentType() {
        var cmd = new TestCommand();
        var ctx = new FakeCommandContext(Map.of("id", UUID.randomUUID()));
        ArgumentInjector.inject(ctx, cmd);
        assertNull(cmd.id);
    }

    @Test
    void privateFieldInjection() {
        var cmd = new TestCommand();
        var ctx = new FakeCommandContext(Map.of("privateField", "hidden"));
        ArgumentInjector.inject(ctx, cmd);
        assertEquals("hidden", getPrivate(cmd, "privateField"));
    }

    @Test
    void incorrectTypeHandling() {
        var cmd = new TestCommand();
        var ctx = new FakeCommandContext(Map.of("age", "not-a-number"));
        assertDoesNotThrow(() -> ArgumentInjector.inject(ctx, cmd));
        assertEquals(0, cmd.age);
    }

    static class FakeCommandContext extends CommandContext {
        private final Map<String, Object> values;

        public FakeCommandContext(Map<String, Object> values) {
            super(new MinimalSender());
            this.values = values;
        }

        @Override
        public <T> T argument(String name, Class<T> type) {
            Object v = values.get(name);
            if (v == null) return null;
            return type.isInstance(v) ? type.cast(v) : null;
        }
    }

    static class TestCommand implements CommandExecutor {
        @Argument("name")
        public String name;
        @Argument("age")
        public int age;
        @Argument("id")
        public UUID id;
        @Argument("privateField")
        private String privateField;

        @Override
        public void execute(CommandContext ctx) {
        }
    }
}
