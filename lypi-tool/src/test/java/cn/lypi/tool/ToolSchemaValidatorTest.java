package cn.lypi.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ValidationResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolSchemaValidatorTest {
    @Test
    void acceptsEmptySchema() {
        ToolSchemaValidator validator = new ToolSchemaValidator();

        assertTrue(validator.validate(new JsonSchema(Map.of()), Map.of()).valid());
    }

    @Test
    void rejectsMissingRequiredField() {
        JsonSchema schema = new JsonSchema(Map.of(
            "type", "object",
            "required", List.of("path"),
            "properties", Map.of("path", Map.of("type", "string"))
        ));

        ValidationResult result = new ToolSchemaValidator().validate(schema, Map.of());

        assertFalse(result.valid());
        assertEquals(List.of("缺少必填字段: path"), result.messages());
    }

    @Test
    void rejectsBasicTypeMismatch() {
        JsonSchema schema = new JsonSchema(Map.of(
            "type", "object",
            "properties", Map.of("limit", Map.of("type", "integer"))
        ));

        ValidationResult result = new ToolSchemaValidator().validate(schema, Map.of("limit", "10"));

        assertFalse(result.valid());
        assertTrue(result.messages().getFirst().contains("limit"));
    }
}
