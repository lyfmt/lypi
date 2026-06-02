package cn.lypi.resource;

import cn.lypi.contracts.resource.ResourceSnapshot;
import java.nio.file.Path;

public interface ResourceLoader {
    /**
     * TODO: 加载项目资源快照。
     *
     * 只负责发现和解析资源，不拼接 system prompt，也不执行工具。
     */
    ResourceSnapshot load(Path cwd);
}
