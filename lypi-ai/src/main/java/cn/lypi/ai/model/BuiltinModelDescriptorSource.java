package cn.lypi.ai.model;

import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.CostProfile;
import cn.lypi.contracts.model.ModelDescriptor;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;

public final class BuiltinModelDescriptorSource implements ModelDescriptorSource {
    private static final List<ModelDescriptor> BUILTIN_MODELS = List.of(
        openAiModel("gpt-5.5", 1_050_000, 128_000, "5.00", "30.00"),
        openAiModel("gpt-5.4", 1_050_000, 128_000, "2.50", "15.00"),
        openAiModel("gpt-5.4-mini", 400_000, 128_000, "0.75", "4.50"),
        openAiModel("gpt-5-mini", 128_000, 16_384, "0", "0")
    );

    @Override
    public List<ModelDescriptor> list() {
        return List.copyOf(BUILTIN_MODELS);
    }

    private static ModelDescriptor openAiModel(
        String modelId,
        int contextWindow,
        int maxOutputTokens,
        String inputTokenCost,
        String outputTokenCost
    ) {
        return new ModelDescriptor(
            "openai",
            modelId,
            URI.create("https://api.openai.com/v1"),
            ApiStyle.OPENAI_COMPATIBLE,
            contextWindow,
            maxOutputTokens,
            true,
            true,
            new CostProfile(new BigDecimal(inputTokenCost), new BigDecimal(outputTokenCost), "USD"),
            Map.of()
        );
    }
}
