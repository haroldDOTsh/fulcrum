package sh.harold.fulcrum.distribution.launcher;

interface RuntimeServiceEngine extends AutoCloseable {
    void start();

    boolean live();

    boolean ready();

    long loopCount();

    @Override
    void close();
}
