package cn.lycode.ai;

import cn.lycode.contracts.model.ModelDescriptor;
import java.util.List;

/**
 * Boot 装配层用于替换一个 provider 的运行时模型快照。
 */
public interface RuntimeModelRegistry extends ModelRegistry {
    void replaceProvider(String provider, List<ModelDescriptor> descriptors);
}
