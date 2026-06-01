package cn.lypi.ai;

import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.model.ModelSelection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class DefaultModelRegistry implements ModelRegistry {
    private final List<ModelDescriptor> descriptors;

    public DefaultModelRegistry(List<ModelDescriptor> descriptors) {
        this.descriptors = List.copyOf(Objects.requireNonNull(descriptors, "descriptors"));
    }

    @Override
    public List<ModelDescriptor> list() {
        return descriptors;
    }

    @Override
    public Optional<ModelDescriptor> find(ModelSelection selection) {
        Objects.requireNonNull(selection, "selection");
        return descriptors.stream()
            .filter(descriptor -> descriptor.provider().equals(selection.provider()))
            .filter(descriptor -> descriptor.modelId().equals(selection.modelId()))
            .findFirst();
    }
}
