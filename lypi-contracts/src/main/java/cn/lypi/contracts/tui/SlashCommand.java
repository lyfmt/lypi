package cn.lypi.contracts.tui;

import cn.lypi.contracts.prompt.PromptParameter;
import java.util.List;

public record SlashCommand(
    String name,
    String description,
    List<PromptParameter> parameters,
    SlashCommandHandler handler
) {}

