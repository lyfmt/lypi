package cn.lypi.transport.tui;

@FunctionalInterface
interface FrameSink {
    /**
     * 提交一次 history + mutable surface 终端事务。
     */
    void render(TuiRenderBatch batch);
}
