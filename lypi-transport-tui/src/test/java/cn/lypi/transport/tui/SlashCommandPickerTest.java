package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SlashCommandPickerTest {
    @Test
    void defaultCommandsIncludeImplementedStateCommandsAndTemplates() {
        SlashCommandPicker picker = SlashCommandPicker.withTemplates(List.of("review", "commit"));

        picker.updateFilter("/");

        assertEquals(
            List.of("/model", "/thinking", "/plan", "/permission-mode", "/compact", "/review", "/commit"),
            picker.visibleCommands()
        );
    }

    @Test
    void fuzzyMatchesCommandsAndAcceptsSelection() {
        SlashCommandPicker picker = new SlashCommandPicker(List.of("/model", "/thinking", "/compact"));

        picker.updateFilter("/thk");

        assertEquals("(1/1)", picker.title());
        assertEquals(List.of("/thinking"), picker.visibleCommands());
        assertEquals("/thinking", picker.accept().orElseThrow());
    }

    @Test
    void supportsSecondaryPickerForModelAndThinking() {
        SlashCommandPicker picker = new SlashCommandPicker(List.of("/model", "/thinking"));

        picker.updateFilter("/model");
        picker.accept();
        picker.updateFilter("gpt");

        assertTrue(picker.secondaryOptions(List.of("gpt-5.4", "claude")).contains("gpt-5.4"));
    }

    @Test
    void noMatchKeepsEmptySelection() {
        SlashCommandPicker picker = new SlashCommandPicker(List.of("/model"));

        picker.updateFilter("/zzz");

        assertEquals("(0/0)", picker.title());
        assertTrue(picker.accept().isEmpty());
    }

    @Test
    void removedModeCommandDoesNotPrefixMatchModelCommand() {
        SlashCommandPicker picker = new SlashCommandPicker(List.of("/model", "/plan", "/compact"));

        picker.updateFilter("/mode");

        assertEquals(List.of(), picker.visibleCommands());
        assertTrue(picker.accept().isEmpty());
    }

    @Test
    void prefixMatchesCommandsAndMovesSelection() {
        SlashCommandPicker picker = SlashCommandPicker.withTemplates(List.of("memory", "review"));

        picker.updateFilter("/m");

        assertEquals(List.of("/model", "/memory"), picker.visibleCommands());
        assertEquals("(1/2)", picker.title());
        picker.moveDown();
        assertEquals("(2/2)", picker.title());
        assertEquals("/memory", picker.accept().orElseThrow());
        picker.moveUp();
        assertEquals("(1/2)", picker.title());
    }
}
