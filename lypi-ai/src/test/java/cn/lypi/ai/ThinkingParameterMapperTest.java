package cn.lypi.ai;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.model.ThinkingLevel;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ThinkingParameterMapperTest {
    @Test
    void mapsOffToNoProviderParameters() {
        assertThat(ThinkingParameterMapper.openAiCompatible(ThinkingLevel.OFF)).isEmpty();
    }

    @Test
    void mapsStandardLevelsToReasoningEffort() {
        assertThat(ThinkingParameterMapper.openAiCompatible(ThinkingLevel.LOW))
            .containsExactly(Map.entry("reasoning_effort", "low"));
        assertThat(ThinkingParameterMapper.openAiCompatible(ThinkingLevel.MEDIUM))
            .containsExactly(Map.entry("reasoning_effort", "medium"));
        assertThat(ThinkingParameterMapper.openAiCompatible(ThinkingLevel.HIGH))
            .containsExactly(Map.entry("reasoning_effort", "high"));
    }

    @Test
    void mapsExtendedLevelsToCompatReasoningEffort() {
        assertThat(ThinkingParameterMapper.openAiCompatible(ThinkingLevel.MINIMAL))
            .containsExactly(Map.entry("reasoning_effort", "minimal"));
        assertThat(ThinkingParameterMapper.openAiCompatible(ThinkingLevel.XHIGH))
            .containsExactly(Map.entry("reasoning_effort", "xhigh"));
        assertThat(ThinkingParameterMapper.openAiCompatible(ThinkingLevel.MAX))
            .containsExactly(Map.entry("reasoning_effort", "max"));
    }
}
