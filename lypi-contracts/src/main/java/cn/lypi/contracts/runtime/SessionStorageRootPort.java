package cn.lypi.contracts.runtime;

import java.nio.file.Path;

public interface SessionStorageRootPort {
    /**
     * 返回 session JSONL 持久化根目录。
     *
     * NOTE: 这是存储 cwd，不一定等同于 agent 执行 cwd。
     */
    Path sessionStorageRoot();
}
