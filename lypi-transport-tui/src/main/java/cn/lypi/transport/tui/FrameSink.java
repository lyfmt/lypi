package cn.lypi.transport.tui;

import java.util.List;

@FunctionalInterface
interface FrameSink {
    /**
     * 渲染一帧终端行。
     */
    void render(List<String> lines);

    /**
     * 渲染一帧终端行。
     */
    default void render(TuiRenderFrame frame) {
        render(frame.lines());
    }

    /**
     * 在指定 viewport 内渲染一帧终端行。
     *
     * NOTE: history 插入和 live frame 绘制必须共享同一个 viewport，
     * 避免同一帧内重复推导导致底部输入区错位。
     */
    default void render(TuiRenderFrame frame, TuiViewportArea area) {
        render(frame);
    }
}
