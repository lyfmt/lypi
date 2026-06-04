package cn.lypi.ai.provider.openai;

import cn.lypi.contracts.model.AssistantStreamEvent;
import java.util.List;

interface OpenAiStreamNormalizer {
    List<AssistantStreamEvent> normalize(String data);
}
