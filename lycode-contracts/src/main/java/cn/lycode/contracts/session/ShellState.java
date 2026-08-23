package cn.lycode.contracts.session;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 会话级 shell 状态。
 *
 * NOTE: 第一阶段只承载当前 shell 工作目录；与 SessionHeader.cwd（session 创建/存储位置）语义不同，
 * 该值随 Bash 捕获的 cd 结果更新。
 */
public record ShellState(Path cwd) {
    public ShellState {
        Objects.requireNonNull(cwd, "cwd must not be null");
    }

    @JsonCreator
    public static ShellState create(@JsonProperty("cwd") Path cwd) {
        return new ShellState(cwd);
    }

    public static ShellState of(Path cwd) {
        return new ShellState(cwd);
    }
}
