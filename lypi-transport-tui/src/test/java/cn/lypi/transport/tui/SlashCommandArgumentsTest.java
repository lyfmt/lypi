package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SlashCommandArgumentsTest {
    @Test
    void parsesCommandAndPositionalArguments() {
        SlashCommandArguments arguments = SlashCommandArguments.parse("/model openai/gpt-5.4");

        assertEquals(List.of("/model", "openai/gpt-5.4"), arguments.tokens());
        assertEquals(List.of("openai/gpt-5.4"), arguments.positionals());
    }

    @Test
    void parsesQuotedNamedArguments() {
        SlashCommandArguments arguments = SlashCommandArguments.parse("/review scope=\"staged diff\" tone=concise");

        assertEquals(List.of("/review", "scope=staged diff", "tone=concise"), arguments.tokens());
        assertEquals(Map.of("scope", "staged diff", "tone", "concise"), arguments.named());
    }

    @Test
    void keepsUnclosedQuoteAsSingleToken() {
        SlashCommandArguments arguments = SlashCommandArguments.parse("/review scope=\"staged diff");

        assertEquals(List.of("/review", "scope=staged diff"), arguments.tokens());
        assertEquals(Map.of("scope", "staged diff"), arguments.named());
    }
}
