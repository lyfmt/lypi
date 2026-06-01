package cn.lypi.tool;

import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import java.util.Optional;

public interface ToolRegistry extends ToolRuntimePort {
    /*
    * @status : 未完成
    * @summary : 注册一个工具协议对象。
    *@description : 注册过程必须处理名称冲突、别名冲突和 diagnostic 生成。
    *
    *
                              */
    void register(Tool<?, ?> tool);

    /*
    * @status : 未完成
    * @summary : 按工具名称或别名查找工具。
    *@description : 查找结果必须保留主工具名用于审计。
    *
    *
                              */
    Optional<Tool<?, ?>> resolve(String nameOrAlias);

    /*
    * @status : 未完成
    * @summary : 生成工具注册表快照。
    *@description : 快照供启动上下文、诊断和 prompt 构建消费，不暴露工具实现对象。
    *
    *
                              */
    ToolRegistrySnapshot snapshot();
}

