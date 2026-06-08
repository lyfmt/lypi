package cn.lypi.transport.tui;

import java.util.List;

@FunctionalInterface
interface FrameSink {
    void render(List<String> lines);
}
