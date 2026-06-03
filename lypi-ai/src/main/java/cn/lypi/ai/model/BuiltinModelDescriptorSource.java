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
        new ModelDescriptor(
            "openai",
            "gpt-5-mini",
            URI.create("https://api.openai.com/v1"),
            ApiStyle.OPENAI_COMPATIBLE,
            128_000,
            16_384,
            true,
            true,
            new CostProfile(BigDecimal.ZERO, BigDecimal.ZERO, "USD"),
            Map.of()
        )
    );

    @Override
    public List<ModelDescriptor> list() {
        return List.copyOf(BUILTIN_MODELS);
    }
}
