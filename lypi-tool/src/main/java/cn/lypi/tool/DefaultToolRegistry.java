package cn.lypi.tool;

import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolDescriptor;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 默认工具注册表。
 *
 * NOTE: 注册表只负责名称解析和快照，不负责执行、权限或审计。
 */
public final class DefaultToolRegistry implements ToolRegistry {
    private final Map<String, Tool<?, ?>> toolsByName = new LinkedHashMap<>();
    private final Map<String, Tool<?, ?>> toolsByLookupName = new LinkedHashMap<>();

    @Override
    public synchronized void register(Tool<?, ?> tool) {
        Objects.requireNonNull(tool, "tool must not be null");
        String name = requireLookupName(tool.name(), "tool name");
        ensureAvailable(name);
        for (String alias : safeAliases(tool)) {
            ensureAvailable(requireLookupName(alias, "tool alias"));
        }

        toolsByName.put(name, tool);
        toolsByLookupName.put(name, tool);
        for (String alias : safeAliases(tool)) {
            toolsByLookupName.put(alias, tool);
        }
    }

    @Override
    public synchronized Optional<Tool<?, ?>> resolve(String nameOrAlias) {
        if (nameOrAlias == null || nameOrAlias.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(toolsByLookupName.get(nameOrAlias));
    }

    @Override
    public synchronized ToolRegistrySnapshot snapshot() {
        List<ToolDescriptor> descriptors = toolsByName.values().stream()
            .map(tool -> new ToolDescriptor(
                tool.name(),
                safeAliases(tool),
                readOnly(tool),
                destructive(tool)
            ))
            .toList();
        return new ToolRegistrySnapshot(descriptors);
    }

    private String requireLookupName(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    private void ensureAvailable(String lookupName) {
        if (toolsByLookupName.containsKey(lookupName)) {
            throw new IllegalArgumentException("tool name or alias already registered: " + lookupName);
        }
    }

    private List<String> safeAliases(Tool<?, ?> tool) {
        List<String> aliases = tool.aliases();
        if (aliases == null) {
            return List.of();
        }
        return List.copyOf(aliases);
    }

    @SuppressWarnings("unchecked")
    private boolean readOnly(Tool<?, ?> tool) {
        return ((Tool<Object, ?>) tool).isReadOnly(Map.of());
    }

    @SuppressWarnings("unchecked")
    private boolean destructive(Tool<?, ?> tool) {
        return ((Tool<Object, ?>) tool).isDestructive(Map.of());
    }
}
