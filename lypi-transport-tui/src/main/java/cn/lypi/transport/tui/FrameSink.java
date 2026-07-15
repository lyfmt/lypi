package cn.lypi.transport.tui;

import java.util.List;

@FunctionalInterface
interface FrameSink {
    /**
     * 渲染一帧终端行。
     */
    void render(List<String> lines);

    /**
     * 渲染已经过物理行校验的完整帧。
     */
    default void render(TuiRenderFrame frame) {
        render(frame.lines());
    }
}
