package sh.harold.fulcrum.command.testdata;

import sh.harold.fulcrum.command.Suggestions;
import sh.harold.fulcrum.command.Argument;

public class BadExample {
    @Suggestions("nope")
    @Argument("fail")
    public String fail;
}
