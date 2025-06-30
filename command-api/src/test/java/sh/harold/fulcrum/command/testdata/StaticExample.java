package sh.harold.fulcrum.command.testdata;

import sh.harold.fulcrum.command.Suggestions;
import sh.harold.fulcrum.command.Argument;
import java.util.List;

public class StaticExample {
    @Suggestions("staticList")
    @Argument("rank")
    public String rank;
    public static List<String> staticList() {
        return List.of("ADMIN", "MODERATOR", "MEMBER");
    }
}
