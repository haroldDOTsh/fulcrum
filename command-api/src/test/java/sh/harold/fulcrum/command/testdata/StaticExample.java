package sh.harold.fulcrum.command.testdata;

import sh.harold.fulcrum.command.annotations.Argument;
import sh.harold.fulcrum.command.annotations.Suggestions;

import java.util.List;

public class StaticExample {
    @Suggestions("staticList")
    @Argument("rank")
    public String rank;

    public static List<String> staticList() {
        return List.of("ADMIN", "MODERATOR", "MEMBER");
    }
}
