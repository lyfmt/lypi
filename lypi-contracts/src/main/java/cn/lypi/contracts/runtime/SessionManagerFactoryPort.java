package cn.lypi.contracts.runtime;

import java.nio.file.Path;

public interface SessionManagerFactoryPort {
    /**
     * 打开指定 cwd 下的 session manager。
     *
     * NOTE: parent session 和 child session 必须使用独立 manager 实例，避免写入串扰。
     */
    SessionManagerPort open(Path cwd, String sessionId);
}
