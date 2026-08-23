package cn.lycode.contracts.common;

@FunctionalInterface
public interface SignalSubscription extends AutoCloseable {
    @Override
    void close();

    static SignalSubscription none() {
        return () -> {
        };
    }
}
