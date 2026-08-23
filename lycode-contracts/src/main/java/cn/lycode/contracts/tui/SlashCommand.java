package cn.lycode.contracts.tui;

import cn.lycode.contracts.prompt.PromptParameter;
import java.util.List;

public record SlashCommand(
    String name,
    String description,
    List<PromptParameter> parameters,
    SlashCommandHandler handler
) {}

