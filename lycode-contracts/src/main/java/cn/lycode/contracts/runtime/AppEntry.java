package cn.lycode.contracts.runtime;

import cn.lycode.contracts.bootstrap.BootstrapRequest;

public interface AppEntry {
    /**
     * 启动 ly-code 运行时。
     *
     * NOTE: 入口只负责装配和启动，不承载 turn loop 或 session 状态事实源。
     */
    void start(BootstrapRequest request);
}
