package sh.harold.fulcrum.runtime.threading;

@FunctionalInterface
public interface RuntimeAction {
    void run(PaperRuntime runtime) throws Exception;
}
