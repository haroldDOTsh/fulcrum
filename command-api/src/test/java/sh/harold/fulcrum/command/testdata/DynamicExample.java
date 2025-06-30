package sh.harold.fulcrum.command.testdata;

import org.bukkit.command.CommandSender;
import sh.harold.fulcrum.command.Argument;
import sh.harold.fulcrum.command.Suggestions;

import java.util.List;

public class DynamicExample {
    @Suggestions(value = "onlineFriends", dynamic = true)
    @Argument("friend")
    public String friend;

    public static List<String> onlineFriends(CommandSender sender) {
        return List.of("Alice", "Bob");
    }
}
