package cn.lypi.ai;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.model.ThinkingLevel;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleThinkingParameterMapperTest {
    @Test
    void mapsOffToNoProviderParameters() {
        assertThat(OpenAiCompatibleThinkingParameterMapper.map(ThinkingLevel.OFF)).isEmpty();
    }

    @Test
    void mapsStandardLevelsToReasoningEffort() {
        assertThat(OpenAiCompatibleThinkingParameterMapper.map(ThinkingLevel.LOW))
            .containsExactly(Map.entry("reasoning_effort", "low"));
        assertThat(OpenAiCompatibleThinkingParameterMapper.map(ThinkingLevel.MEDIUM))
            .containsExactly(Map.entry("reasoning_effort", "medium"));
        assertThat(OpenAiCompatibleThinkingParameterMapper.map(ThinkingLevel.HIGH))
            .containsExactly(Map.entry("reasoning_effort", "high"));
    }

    @Test
    void mapsExtendedLevelsToOpenAiCompatibleReasoningEffort() {
        assertThat(OpenAiCompatibleThinkingParameterMapper.map(ThinkingLevel.MINIMAL))
            .containsExactly(Map.entry("reasoning_effort", "minimal"));
        assertThat(OpenAiCompatibleThinkingParameterMapper.map(ThinkingLevel.XHIGH))
            .containsExactly(Map.entry("reasoning_effort", "xhigh"));
        assertThat(OpenAiCompatibleThinkingParameterMapper.map(ThinkingLevel.MAX))
            .containsExactly(Map.entry("reasoning_effort", "xhigh"));
    }
}
