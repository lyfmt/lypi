package cn.lypi.ai;

import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.model.ModelSelection;
import java.util.List;
import java.util.Optional;

public interface ModelRegistry {
    /*
    * @status : 未完成
    * @summary : 列出可用模型描述。
    *@description : 描述来自配置和 provider adapter，不包含运行时 secret。
    *
    *
                              */
    List<ModelDescriptor> list();

    /*
    * @status : 未完成
    * @summary : 按选择查找模型。
    *@description : 查找失败时由上层决定 fallback，并生成可见事件与 session entry。
    *
    *
                              */
    Optional<ModelDescriptor> find(ModelSelection selection);
}

