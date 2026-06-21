package cn.lypi.contracts.hook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ToolHookInputSnapshots {
    private ToolHookInputSnapshots() {
    }

    static Map<String, Object> snapshot(Map<String, Object> input) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        input.forEach((key, value) -> snapshot.put(key, snapshotValue(value)));
        return Collections.unmodifiableMap(snapshot);
    }

    private static Object snapshotValue(Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            Map<Object, Object> snapshot = new LinkedHashMap<>();
            mapValue.forEach((key, nestedValue) -> snapshot.put(key, snapshotValue(nestedValue)));
            return Collections.unmodifiableMap(snapshot);
        }
        if (value instanceof List<?> listValue) {
            List<Object> snapshot = new ArrayList<>(listValue.size());
            listValue.forEach(item -> snapshot.add(snapshotValue(item)));
            return Collections.unmodifiableList(snapshot);
        }
        if (value instanceof Set<?> setValue) {
            Set<Object> snapshot = new LinkedHashSet<>();
            setValue.forEach(item -> snapshot.add(snapshotValue(item)));
            return Collections.unmodifiableSet(snapshot);
        }
        return value;
    }
}
