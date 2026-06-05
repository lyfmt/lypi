package cn.lypi.contracts.runtime;

public interface StateStream<T> {
    /**
     * 返回当前运行态快照。
     */
    T current();
}
