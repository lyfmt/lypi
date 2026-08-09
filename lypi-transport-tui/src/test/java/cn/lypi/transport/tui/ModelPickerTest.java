package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.CostProfile;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelPickerTest {
    @Test
    void sortsLabelsAndStartsAtCurrentModel() {
        ModelPicker picker = new ModelPicker(
            List.of(descriptor("zen", "kimi-k2.6"), descriptor("openai", "gpt-5-mini")),
            new ModelSelection("openai", "gpt-5-mini", ThinkingLevel.MEDIUM)
        );

        assertEquals(List.of("openai/gpt-5-mini", "zen/kimi-k2.6"), picker.labels());
        assertEquals("openai/gpt-5-mini", picker.selectedLabel().orElseThrow());

        picker.moveDown();

        ModelDescriptor selected = picker.accept().orElseThrow();
        assertEquals("zen", selected.provider());
        assertEquals("kimi-k2.6", selected.modelId());
    }

    @Test
    void deduplicatesProviderAndModelIdKeepingFirstDescriptor() {
        ModelDescriptor first = descriptor("openai", "gpt-5-mini");
        ModelDescriptor duplicate = descriptor("openai", "gpt-5-mini");

        ModelPicker picker = new ModelPicker(List.of(first, duplicate), null);

        assertEquals(List.of("openai/gpt-5-mini"), picker.labels());
        assertSame(first, picker.accept().orElseThrow());
    }

    @Test
    void emptyCandidatesHaveNoSelection() {
        ModelPicker picker = new ModelPicker(List.of(), null);

        picker.moveUp();
        picker.moveDown();

        assertTrue(picker.labels().isEmpty());
        assertEquals(0, picker.selectedIndex());
        assertFalse(picker.selectedLabel().isPresent());
        assertFalse(picker.accept().isPresent());
    }

    @Test
    void movingUpFromFirstWrapsToLast() {
        ModelPicker picker = new ModelPicker(
            List.of(descriptor("openai", "gpt-5-mini"), descriptor("zen", "kimi-k2.6")),
            new ModelSelection("openai", "gpt-5-mini", ThinkingLevel.OFF)
        );

        picker.moveUp();

        assertEquals("zen/kimi-k2.6", picker.selectedLabel().orElseThrow());
    }

    @Test
    void movingDownFromLastWrapsToFirst() {
        ModelPicker picker = new ModelPicker(
            List.of(descriptor("openai", "gpt-5-mini"), descriptor("zen", "kimi-k2.6")),
            new ModelSelection("zen", "kimi-k2.6", ThinkingLevel.HIGH)
        );

        picker.moveDown();

        assertEquals("openai/gpt-5-mini", picker.selectedLabel().orElseThrow());
    }

    private static ModelDescriptor descriptor(String provider, String modelId) {
        return new ModelDescriptor(
            provider,
            modelId,
            URI.create("https://api.example.test/v1"),
            ApiStyle.OPENAI_COMPATIBLE,
            128_000,
            16_384,
            true,
            false,
            new CostProfile(BigDecimal.ZERO, BigDecimal.ZERO, "USD"),
            Map.of()
        );
    }
}
