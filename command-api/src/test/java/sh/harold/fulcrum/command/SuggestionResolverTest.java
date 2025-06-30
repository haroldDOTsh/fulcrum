package sh.harold.fulcrum.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import java.lang.reflect.Field;
import java.util.List;
import sh.harold.fulcrum.command.testdata.StaticExample;
import sh.harold.fulcrum.command.testdata.DynamicExample;
import sh.harold.fulcrum.command.testdata.EnumExample;
import sh.harold.fulcrum.command.testdata.BadExample;

class SuggestionResolverTest {
    @Test
    void staticSuggestionsTest() throws Exception {
        var ex = new StaticExample();
        Field field = StaticExample.class.getField("rank");
        List<String> suggestions = SuggestionResolver.resolveValues(field, ex);
        Assertions.assertTrue(suggestions.contains("ADMIN"));
    }
    @Test
    void dynamicSuggestionsTest() throws Exception {
        var ex = new DynamicExample();
        Field field = DynamicExample.class.getField("friend");
        List<String> suggestions = SuggestionResolver.resolveValues(field, ex);
        Assertions.assertTrue(suggestions.contains("Alice"));
    }
    @Test
    void enumFallbackTest() throws Exception {
        var ex = new EnumExample();
        Field field = EnumExample.class.getField("mode");
        List<String> suggestions = SuggestionResolver.resolveValues(field, ex);
        Assertions.assertTrue(suggestions.contains("A")); // assuming EnumExample.Mode.A exists
    }
    @Test
    void missingMethodTest() throws Exception {
        var ex = new BadExample();
        Field field = BadExample.class.getField("fail");
        List<String> suggestions = SuggestionResolver.resolveValues(field, ex);
        Assertions.assertNull(suggestions);
    }
}
