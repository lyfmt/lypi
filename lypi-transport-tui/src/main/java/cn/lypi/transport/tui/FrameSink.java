package cn.lypi.transport.tui;

import java.util.List;

@FunctionalInterface
interface FrameSink {
    /**
     * 渲染一帧终端行。
     */
    void render(List<String> lines);

    /**
     * 渲染已经过物理行校验并携带底部 chrome 元数据的一帧。
     * 兼容 sink 可消费文本视图；真实终端 sink 应覆盖此方法并保留类型化帧。
     */
    default void render(TuiRenderFrame frame) {
        render(frame.lines());
    }
}
