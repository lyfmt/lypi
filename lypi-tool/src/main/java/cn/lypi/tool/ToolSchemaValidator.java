package cn.lypi.tool;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ValidationResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 校验工具输入的轻量 JSON Schema 子集。
 *
 * NOTE: 本实现只覆盖工具运行时当前需要的 object、required 和基础类型校验。
 */
public final class ToolSchemaValidator {
    /**
     * 校验工具输入是否满足 schema 的基础约束。
     *
     * 不支持的复杂 JSON Schema 关键字由工具自己的语义校验处理。
     */
    public ValidationResult validate(JsonSchema schema, Map<String, Object> input) {
        if (schema == null || schema.value() == null || schema.value().isEmpty()) {
            return valid();
        }
        Map<String, Object> safeInput = input == null ? Map.of() : input;
        List<String> messages = new ArrayList<>();
        validateRootType(schema.value(), safeInput, messages);
        validateRequired(schema.value(), safeInput, messages);
        validateProperties(schema.value(), safeInput, messages);
        return new ValidationResult(messages.isEmpty(), List.copyOf(messages));
    }

    private void validateRootType(Map<String, Object> schema, Map<String, Object> input, List<String> messages) {
        Object type = schema.get("type");
        if (type != null && !type.equals("object")) {
            messages.add("工具输入 schema 根类型必须为 object。");
        }
    }

    private void validateRequired(Map<String, Object> schema, Map<String, Object> input, List<String> messages) {
        Object required = schema.get("required");
        if (!(required instanceof Iterable<?> fields)) {
            return;
        }
        for (Object field : fields) {
            if (field instanceof String fieldName && !input.containsKey(fieldName)) {
                messages.add("缺少必填字段: " + fieldName);
            }
        }
    }

    private void validateProperties(Map<String, Object> schema, Map<String, Object> input, List<String> messages) {
        Object properties = schema.get("properties");
        if (!(properties instanceof Map<?, ?> propertyMap)) {
            return;
        }
        for (Map.Entry<?, ?> entry : propertyMap.entrySet()) {
            if (!(entry.getKey() instanceof String fieldName) || !input.containsKey(fieldName)) {
                continue;
            }
            Object propertySchema = entry.getValue();
            if (!(propertySchema instanceof Map<?, ?> propertySchemaMap)) {
                continue;
            }
            Object type = propertySchemaMap.get("type");
            Object value = input.get(fieldName);
            if (type instanceof String typeName && !matchesType(typeName, value)) {
                messages.add("字段类型不匹配: " + fieldName + " 应为 " + typeName);
            }
        }
    }

    private boolean matchesType(String typeName, Object value) {
        return switch (typeName) {
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long;
            case "boolean" -> value instanceof Boolean;
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            default -> true;
        };
    }

    private ValidationResult valid() {
        return new ValidationResult(true, List.of());
    }
}
