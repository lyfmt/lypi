package cn.lypi.contracts.model;

import java.util.List;
import java.util.Optional;

public interface ModelCatalogPort {
    /**
     * 列出当前进程启动时可用的模型快照。
     */
    default List<ModelDescriptor> list() {
        return List.of();
    }

    /**
     * 按模型选择查找模型描述。
     *
     * 查找失败时调用方负责使用本地 fallback。
     */
    Optional<ModelDescriptor> find(ModelSelection selection);
}
