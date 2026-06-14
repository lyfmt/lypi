package cn.lypi.runtime.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryConsolidationPromptFactoryTest {
    @Test
    void promptContainsSettlementRulesAndSkipInstruction() {
        String prompt = new MemoryConsolidationPromptFactory().prompt();

        assertThat(prompt)
            .contains("后台记忆沉淀任务")
            .contains("memory-settlement")
            .contains("No Verification, No Memory")
            .contains("长期价值")
            .contains("无可沉淀")
            .contains("不要保存临时状态")
            .contains("不要保存敏感信息");
    }
}
