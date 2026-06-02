package cn.lypi.boot;

import cn.lypi.contracts.bootstrap.BootstrapContext;
import cn.lypi.contracts.bootstrap.BootstrapRequest;

public interface BootstrapService {
    /**
     * TODO: 组装启动上下文。
     *
     * 负责把设置、资源、工具、模型和 session 装配成可运行上下文，不执行 turn loop。
     */
    BootstrapContext bootstrap(BootstrapRequest request);
}

