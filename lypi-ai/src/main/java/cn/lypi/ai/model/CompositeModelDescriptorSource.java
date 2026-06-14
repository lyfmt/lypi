package cn.lypi.ai.model;

import cn.lypi.contracts.model.ModelDescriptor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CompositeModelDescriptorSource implements ModelDescriptorSource {
    private final List<ModelDescriptorSource> sources;

    public CompositeModelDescriptorSource(List<? extends ModelDescriptorSource> sources) {
        this.sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
    }

    @Override
    public List<ModelDescriptor> list() {
        Map<ModelKey, ModelDescriptor> merged = new LinkedHashMap<>();
        for (ModelDescriptorSource source : sources) {
            for (ModelDescriptor descriptor : source.list()) {
                merged.put(new ModelKey(descriptor.provider(), descriptor.modelId()), descriptor);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private record ModelKey(String provider, String modelId) {
    }
}
