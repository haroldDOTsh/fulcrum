package sh.harold.fulcrum.command.testdata;

import sh.harold.fulcrum.command.Suggestions;
import sh.harold.fulcrum.command.Argument;
import java.util.List;
import org.bukkit.command.CommandSender;

public class DynamicExample {
    @Suggestions(value = "onlineFriends", dynamic = true)
    @Argument("friend")
    public String friend;
    public static List<String> onlineFriends(CommandSender sender) {
        return List.of("Alice", "Bob");
    }
}
