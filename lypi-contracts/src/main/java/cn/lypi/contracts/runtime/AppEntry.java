package cn.lypi.contracts.runtime;

import cn.lypi.contracts.bootstrap.BootstrapRequest;

public interface AppEntry {
    /*
    * @status : 未完成
    * @summary : 启动 ly-pi 运行时。
    *@description : 入口只负责装配和启动，不承载 turn loop 或 session 状态事实源。
    *
    *
                              */
    void start(BootstrapRequest request);
}

