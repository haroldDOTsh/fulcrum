package sh.harold.fulcrum.command.testdata;

import sh.harold.fulcrum.command.Argument;
import sh.harold.fulcrum.command.Suggestions;

public class BadExample {
    @Suggestions("nope")
    @Argument("fail")
    public String fail;
}
