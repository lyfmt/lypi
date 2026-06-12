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
}
