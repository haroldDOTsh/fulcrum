package sh.harold.fulcrum.command.testdata;

import net.kyori.adventure.audience.Audience;
import sh.harold.fulcrum.command.annotations.Argument;
import sh.harold.fulcrum.command.annotations.Suggestions;

import java.util.List;

public class DynamicExample {
    @Suggestions(value = "onlineFriends", dynamic = true)
    @Argument("friend")
    public String friend;

    public static List<String> onlineFriends(Audience audience) {
        // Example: could filter based on audience permissions, etc.
        return List.of("Alice", "Bob");
    }
}
