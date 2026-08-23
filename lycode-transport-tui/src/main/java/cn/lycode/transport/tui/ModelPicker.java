package cn.lycode.transport.tui;

import cn.lycode.contracts.model.ModelDescriptor;
import cn.lycode.contracts.model.ModelSelection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ModelPicker {
    private final List<ModelDescriptor> models;
    private int selectedIndex;

    ModelPicker(List<ModelDescriptor> models, ModelSelection current) {
        this.models = distinctAndSort(models);
        this.selectedIndex = initialIndex(current);
    }

    List<String> labels() {
        return models.stream().map(ModelPicker::label).toList();
    }

    int selectedIndex() {
        return selectedIndex;
    }

    Optional<String> selectedLabel() {
        return accept().map(ModelPicker::label);
    }

    Optional<ModelDescriptor> accept() {
        return models.isEmpty() ? Optional.empty() : Optional.of(models.get(selectedIndex));
    }

    void moveDown() {
        if (!models.isEmpty()) {
            selectedIndex = Math.floorMod(selectedIndex + 1, models.size());
        }
    }

    void moveUp() {
        if (!models.isEmpty()) {
            selectedIndex = Math.floorMod(selectedIndex - 1, models.size());
        }
    }

    static String label(ModelDescriptor model) {
        return model.provider() + "/" + model.modelId();
    }

    private static List<ModelDescriptor> distinctAndSort(List<ModelDescriptor> candidates) {
        Map<ModelKey, ModelDescriptor> distinct = new LinkedHashMap<>();
        for (ModelDescriptor candidate : candidates == null ? List.<ModelDescriptor>of() : candidates) {
            if (candidate != null) {
                distinct.putIfAbsent(new ModelKey(candidate.provider(), candidate.modelId()), candidate);
            }
        }
        return distinct.values().stream()
            .sorted(Comparator.comparing(ModelDescriptor::provider).thenComparing(ModelDescriptor::modelId))
            .toList();
    }

    private int initialIndex(ModelSelection current) {
        if (current == null) {
            return 0;
        }
        for (int index = 0; index < models.size(); index++) {
            ModelDescriptor candidate = models.get(index);
            if (candidate.provider().equals(current.provider()) && candidate.modelId().equals(current.modelId())) {
                return index;
            }
        }
        return 0;
    }

    private record ModelKey(String provider, String modelId) {
    }
}
