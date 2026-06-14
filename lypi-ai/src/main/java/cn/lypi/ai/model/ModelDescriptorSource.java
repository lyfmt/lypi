package cn.lypi.ai.model;

import cn.lypi.contracts.model.ModelDescriptor;
import java.util.List;

public interface ModelDescriptorSource {
    /**
     * 列出该来源可提供的模型描述。
     */
    List<ModelDescriptor> list();
}
