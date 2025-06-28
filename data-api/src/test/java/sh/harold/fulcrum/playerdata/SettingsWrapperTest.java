package sh.harold.fulcrum.playerdata;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.util.SettingsWrapper;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SettingsWrapperTest {
    @Test
    void setAndGetDeepPath() {
        Map<String, Object> map = new HashMap<>();
        SettingsWrapper wrapper = new SettingsWrapper(map);
        wrapper.set("hud.scale", 1.5);
        assertEquals(1.5, wrapper.get("hud.scale"));
        assertEquals(1.5, wrapper.get("hud.scale", Double.class));
    }

    @Test
    void autoCreateIntermediateMaps() {
        Map<String, Object> map = new HashMap<>();
        SettingsWrapper wrapper = new SettingsWrapper(map);
        wrapper.set("a.b.c.d", 42);
        assertEquals(42, ((Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) map.get("a")).get("b")).get("c")).get("d"));
    }

    @Test
    void removeNestedValue() {
        Map<String, Object> map = new HashMap<>();
        SettingsWrapper wrapper = new SettingsWrapper(map);
        wrapper.set("foo.bar.baz", 99);
        wrapper.remove("foo.bar.baz");
        assertNull(wrapper.get("foo.bar.baz"));
        assertFalse(wrapper.contains("foo.bar.baz"));
    }

    @Test
    void fallbackOnMissingPathOrType() {
        Map<String, Object> map = new HashMap<>();
        SettingsWrapper wrapper = new SettingsWrapper(map);
        assertNull(wrapper.get("not.exists"));
        assertNull(wrapper.get("not.exists", String.class));
        wrapper.set("x.y", "str");
        assertThrows(ClassCastException.class, () -> wrapper.get("x.y", Integer.class));
    }

    @Test
    void toMapReflectsChanges() {
        Map<String, Object> map = new HashMap<>();
        SettingsWrapper wrapper = new SettingsWrapper(map);
        wrapper.set("foo.bar", 123);
        Map<String, Object> out = wrapper.toMap();
        assertEquals(123, ((Map<String, Object>) out.get("foo")).get("bar"));
    }

    @Test
    void threadSafety() throws InterruptedException {
        Map<String, Object> map = new HashMap<>();
        SettingsWrapper wrapper = new SettingsWrapper(map);
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
