package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TuiRenderStateTest {
    @Test
    void pathLabelAbbreviatesHomeAndItsDescendants() {
        Path home = Path.of("/home/tester");

        assertEquals("~", TuiRenderState.pathLabel(home, home));
        assertEquals(
            Path.of("~", "work", "ly-pi").toString(),
            TuiRenderState.pathLabel(home.resolve("work/ly-pi"), home)
        );
    }

    @Test
    void pathLabelKeepsNormalizedAbsolutePathOutsideHome() {
        Path home = Path.of("/home/tester");
        Path cwd = Path.of("/workspace/../workspace/ly-pi");

        assertEquals(
            Path.of("/workspace/ly-pi").toAbsolutePath().normalize().toString(),
            TuiRenderState.pathLabel(cwd, home)
        );
    }

    @Test
    void pathLabelResolvesRelativePathBeforeFormatting() {
        Path cwd = Path.of("relative/project");

        assertEquals(cwd.toAbsolutePath().normalize().toString(), TuiRenderState.pathLabel(cwd, null));
    }

    @Test
    void pathLabelReturnsEmptyForMissingWorkingDirectory() {
        assertEquals("", TuiRenderState.pathLabel(null, Path.of("/home/tester")));
    }
}
