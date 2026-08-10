package cn.lypi.ai;

import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.model.ModelSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class DefaultModelRegistry implements RuntimeModelRegistry {
    private final AtomicReference<List<ModelDescriptor>> descriptors;

    public DefaultModelRegistry(List<ModelDescriptor> descriptors) {
        this.descriptors = new AtomicReference<>(immutableDescriptors(descriptors));
    }

    @Override
    public List<ModelDescriptor> list() {
        return descriptors.get();
    }

    @Override
    public Optional<ModelDescriptor> find(ModelSelection selection) {
        Objects.requireNonNull(selection, "selection");
        List<ModelDescriptor> current = descriptors.get();
        return current.stream()
            .filter(descriptor -> descriptor.provider().equals(selection.provider()))
            .filter(descriptor -> descriptor.modelId().equals(selection.modelId()))
            .findFirst();
    }

    @Override
    public void replaceProvider(String provider, List<ModelDescriptor> replacement) {
        String requiredProvider = Objects.requireNonNull(provider, "provider");
        List<ModelDescriptor> requiredReplacement = immutableDescriptors(replacement);
        for (ModelDescriptor descriptor : requiredReplacement) {
            if (!requiredProvider.equals(descriptor.provider())) {
                throw new IllegalArgumentException("Replacement descriptor provider must match provider.");
            }
        }
        descriptors.updateAndGet(current -> {
            List<ModelDescriptor> next = new ArrayList<>(current.size() + requiredReplacement.size());
            current.stream()
                .filter(descriptor -> !requiredProvider.equals(descriptor.provider()))
                .forEach(next::add);
            next.addAll(requiredReplacement);
            return List.copyOf(next);
        });
    }

    private static List<ModelDescriptor> immutableDescriptors(List<ModelDescriptor> descriptors) {
        return List.copyOf(Objects.requireNonNull(descriptors, "descriptors"));
    }
}
