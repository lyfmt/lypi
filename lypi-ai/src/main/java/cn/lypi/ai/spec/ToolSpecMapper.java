package cn.lypi.ai.spec;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.tool.ToolDescriptor;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ToolSpecMapper {
    private ToolSpecMapper() {
    }

    /**
     * 将工具注册表快照转换为模型请求工具规格。
     *
     * NOTE: 输入必须来自 ToolRuntimePort 快照，不应暴露工具实现对象给 AI adapter。
     */
    public static List<LypiToolSpec> fromDescriptors(List<ToolDescriptor> descriptors) {
        Objects.requireNonNull(descriptors, "descriptors");
        return descriptors.stream()
            .map(ToolSpecMapper::fromDescriptor)
            .toList();
    }

    private static LypiToolSpec fromDescriptor(ToolDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        return new LypiToolSpec(
            descriptor.name(),
            description(descriptor),
            schemaValue(descriptor.inputSchema())
        );
    }

    private static String description(ToolDescriptor descriptor) {
        String description = descriptor.description();
        return description == null || description.isBlank() ? descriptor.name() : description;
    }

    private static Map<String, Object> schemaValue(JsonSchema schema) {
        if (schema == null || schema.value() == null) {
            return Map.of();
        }
        return schema.value();
    }
}
