package sh.harold.fulcrum.playerdata;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.util.SettingsWrapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlayerSettingsDotPathTest {
    @Test
    void dotPathNestingWorksForSetAndGet() {
        PlayerSettings settings = new PlayerSettings();
        SettingsWrapper wrapper = settings.getSettingsWrapper();
        wrapper.set("hud.scale", 2.5);
        wrapper.set("hud.visible", true);
        wrapper.set("chat.color", "red");
        wrapper.set("fairysouls.unlocked.1", true);
        wrapper.set("fairysouls.unlocked.2", false);
        assertEquals(2.5, wrapper.get("hud.scale"));
        assertEquals(true, wrapper.get("hud.visible"));
        assertEquals("red", wrapper.get("chat.color"));
        assertEquals(true, wrapper.get("fairysouls.unlocked.1"));
        assertEquals(false, wrapper.get("fairysouls.unlocked.2"));
        assertNull(wrapper.get("hud.missing"));
    }

    @Test
    void dotPathNestingCreatesIntermediateMaps() {
        PlayerSettings settings = new PlayerSettings();
        SettingsWrapper wrapper = settings.getSettingsWrapper();
        wrapper.set("a.b.c.d.e", 123);
        assertEquals(123, wrapper.get("a.b.c.d.e"));
        Map<String, Object> map = settings.getSettingsMap();
        assertTrue(map.containsKey("a"));
        assertTrue(((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) map.get("a")).get("b")).get("c")).get("d")).containsKey("e"));
    }

    @Test
    void dotPathNestingRemovesValues() {
        PlayerSettings settings = new PlayerSettings();
        SettingsWrapper wrapper = settings.getSettingsWrapper();
        wrapper.set("foo.bar.baz", 99);
        assertEquals(99, wrapper.get("foo.bar.baz"));
        wrapper.remove("foo.bar.baz");
        assertNull(wrapper.get("foo.bar.baz"));
        assertFalse(wrapper.contains("foo.bar.baz"));
    }

    @Test
    void dotPathNestingTypeSafety() {
        PlayerSettings settings = new PlayerSettings();
        SettingsWrapper wrapper = settings.getSettingsWrapper();
        wrapper.set("x.y", "str");
        assertThrows(ClassCastException.class, () -> wrapper.get("x.y", Integer.class));
    }

    @Test
    void dotPathNestingToMapReflectsChanges() {
        PlayerSettings settings = new PlayerSettings();
        SettingsWrapper wrapper = settings.getSettingsWrapper();
        wrapper.set("foo.bar", 123);
        Map<String, Object> out = wrapper.toMap();
        assertEquals(123, ((Map<String, Object>) out.get("foo")).get("bar"));
    }

    @Test
    void dotPathNestingThreadSafety() throws InterruptedException {
        PlayerSettings settings = new PlayerSettings();
        SettingsWrapper wrapper = settings.getSettingsWrapper();
        Runnable writer = () -> {
            for (int i = 0; i < 1000; i++) wrapper.set("a.b." + i, i);
        };
        Runnable reader = () -> {
            for (int i = 0; i < 1000; i++) wrapper.get("a.b." + i);
        };
        Thread t1 = new Thread(writer);
        Thread t2 = new Thread(reader);
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        assertEquals(999, wrapper.get("a.b.999"));
    }
}
