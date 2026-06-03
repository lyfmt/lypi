package cn.lypi.ai.model;

import cn.lypi.contracts.model.ModelDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class StaticModelDescriptorSource implements ModelDescriptorSource {
    private final List<ModelDescriptor> descriptors;

    public StaticModelDescriptorSource(List<ModelDescriptor> descriptors) {
        this.descriptors = List.copyOf(Objects.requireNonNull(descriptors, "descriptors"));
    }

    @Override
    public List<ModelDescriptor> list() {
        return new ArrayList<>(descriptors);
    }
}
