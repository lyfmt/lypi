package cn.lypi.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolInputsTest {
    @Test
    void intInputUsesDefaultParsesNumbersAndClampsRange() {
        assertEquals(7, ToolInputs.intInput(Map.of(), "limit", 7, 1, 10));
        assertEquals(4, ToolInputs.intInput(Map.of("limit", 4L), "limit", 7, 1, 10));
        assertEquals(10, ToolInputs.intInput(Map.of("limit", "99"), "limit", 7, 1, 10));
        assertEquals(1, ToolInputs.intInput(Map.of("limit", "-2"), "limit", 7, 1, 10));
    }

    @Test
    void intInputKeepsExistingNumberFormatBehaviorForInvalidText() {
        assertThrows(NumberFormatException.class, () ->
            ToolInputs.intInput(Map.of("limit", "abc"), "limit", 7, 1, 10)
        );
    }
}
