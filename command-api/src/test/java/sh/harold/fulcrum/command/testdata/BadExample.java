package sh.harold.fulcrum.command.testdata;

import sh.harold.fulcrum.command.annotations.Argument;
import sh.harold.fulcrum.command.annotations.Suggestions;

public class BadExample {
    @Suggestions("nope")
    @Argument("fail")
    public String fail;
}
