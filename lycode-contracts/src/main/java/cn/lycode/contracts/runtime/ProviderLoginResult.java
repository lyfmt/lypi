package cn.lycode.contracts.runtime;

import cn.lycode.contracts.model.ModelDescriptor;
import java.util.List;
import java.util.Objects;

/**
 * Provider 登录成功后的非敏感结果。
 */
public record ProviderLoginResult(String provider, List<ModelDescriptor> models) {
    public ProviderLoginResult {
        provider = Objects.requireNonNull(provider, "provider");
        models = List.copyOf(Objects.requireNonNull(models, "models"));
    }
}
