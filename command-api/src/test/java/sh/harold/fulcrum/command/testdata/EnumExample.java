package sh.harold.fulcrum.command.testdata;

import sh.harold.fulcrum.command.Argument;

public class EnumExample {
    @Argument("mode")
    public TestEnum mode;
    public enum TestEnum { A, B, C }
}
