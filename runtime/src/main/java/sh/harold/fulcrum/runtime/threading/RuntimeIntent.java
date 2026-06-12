package sh.harold.fulcrum.runtime.threading;

public interface RuntimeIntent {
    String operation();

    RuntimeEpoch epoch();

    void run(PaperRuntime runtime) throws Exception;

    static RuntimeIntent of(String operation, RuntimeEpoch epoch, RuntimeAction action) {
        return new NamedRuntimeIntent(operation, epoch, action);
    }
}
