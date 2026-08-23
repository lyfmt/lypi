package cn.lycode.ai.model;

import cn.lycode.contracts.model.ModelDescriptor;
import java.util.List;

public interface ModelDescriptorSource {
    /**
     * 列出该来源可提供的模型描述。
     */
    List<ModelDescriptor> list();
}
