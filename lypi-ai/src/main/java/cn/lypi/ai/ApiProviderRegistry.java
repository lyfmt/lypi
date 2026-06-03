package cn.lypi.ai;

import cn.lypi.contracts.model.ApiStyle;
import java.util.Optional;

public interface ApiProviderRegistry {
    /**
     * 按 API 风格查找 provider。
     */
    Optional<ApiProvider> find(ApiStyle apiStyle);
}
