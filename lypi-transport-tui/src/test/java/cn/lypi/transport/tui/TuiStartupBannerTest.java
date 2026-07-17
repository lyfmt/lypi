package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class TuiStartupBannerTest {
    @Test
    void wideBannerRestoresFinalWelcomeArtworkAndCentersWithinAvailableRows() {
        List<TerminalLine> lines = TuiStartupBanner.render(80, 12);

        String plain = stripAnsi(lines);
        assertEquals(12, lines.size());
        assertEquals("", lines.get(0).text());
        assertEquals("", lines.get(1).text());
        assertEquals("", lines.get(10).text());
        assertEquals("", lines.get(11).text());
        assertTrue(plain.contains("LY-PI"));
        assertTrue(plain.contains("coding agent cockpit"));
        assertFalse(plain.contains("local-first"));
        assertTrue(plain.contains("██████╗ "));
        assertTrue(plain.contains("██╔══██╗"));
        assertTrue(plain.contains("██████╔╝"));
        assertTrue(lines.stream().allMatch(line -> AnsiWidth.displayWidth(line.text()) <= 80));
    }

    @Test
    void narrowBannerUsesCompactArtworkWithoutOverflow() {
        List<TerminalLine> lines = TuiStartupBanner.render(40, 0);

        String plain = stripAnsi(lines);
        assertEquals(2, lines.size());
        assertTrue(plain.contains("LY-PI"));
        assertTrue(plain.contains("coding agent"));
        assertFalse(plain.contains("cockpit"));
        assertTrue(lines.stream().allMatch(line -> AnsiWidth.displayWidth(line.text()) <= 40));
    }

    private String stripAnsi(List<TerminalLine> lines) {
        return lines.stream()
            .map(TerminalLine::text)
            .collect(Collectors.joining("\n"))
            .replaceAll("\\u001B\\[[0-9;?]*[A-Za-z]", "");
    }
}
