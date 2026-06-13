package cn.lypi.contracts.model;

import java.util.Optional;

public interface ModelCatalogPort {
    /**
     * 按模型选择查找模型描述。
     *
     * 查找失败时调用方负责使用本地 fallback。
     */
    Optional<ModelDescriptor> find(ModelSelection selection);
}
