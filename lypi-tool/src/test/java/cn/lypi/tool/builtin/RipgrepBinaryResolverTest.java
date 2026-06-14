package cn.lypi.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RipgrepBinaryResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void mapsKnownPlatformsToVendorResourcePaths() {
        assertEquals("ripgrep/x86_64-linux/rg", new RipgrepPlatform("Linux", "amd64").resourcePath());
        assertEquals("ripgrep/aarch64-linux/rg", new RipgrepPlatform("Linux", "aarch64").resourcePath());
        assertEquals("ripgrep/x86_64-macos/rg", new RipgrepPlatform("Mac OS X", "x86_64").resourcePath());
        assertEquals("ripgrep/aarch64-macos/rg", new RipgrepPlatform("Darwin", "arm64").resourcePath());
        assertEquals("ripgrep/x86_64-windows/rg.exe", new RipgrepPlatform("Windows 11", "amd64").resourcePath());
    }

    @Test
    void prefersVendorRipgrepByDefault() throws Exception {
        Path binary = vendorBinary("ripgrep/x86_64-linux/rg");
        RipgrepBinaryResolver resolver = RipgrepBinaryResolver.forTesting(
            new RipgrepPlatform("linux", "x86_64"),
            tempDir
        );

        RipgrepBinary command = resolver.resolve(Map.of());

        assertEquals(binary.toString(), command.command());
        assertEquals("vendor", command.mode());
    }

    @Test
    void systemModeUsesCommandNameOnly() {
        RipgrepBinaryResolver resolver = RipgrepBinaryResolver.forTesting(
            new RipgrepPlatform("linux", "x86_64"),
            tempDir
        );

        RipgrepBinary command = resolver.resolve(Map.of("lypi.tool.grep.ripgrep.mode", "system"));

        assertEquals("rg", command.command());
        assertEquals("system", command.mode());
    }

    @Test
    void rejectsMissingVendorBinaryWithoutSystemOverride() {
        RipgrepBinaryResolver resolver = RipgrepBinaryResolver.forTesting(
            new RipgrepPlatform("linux", "x86_64"),
            tempDir
        );

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> resolver.resolve(Map.of()));

        assertTrue(exception.getMessage().contains("未找到随包 ripgrep"));
        assertTrue(exception.getMessage().contains("x86_64-linux"));
    }

    private Path vendorBinary(String relativePath) throws Exception {
        Path binary = tempDir.resolve(relativePath);
        Files.createDirectories(binary.getParent());
        Files.writeString(binary, "#!/bin/sh\n");
        binary.toFile().setExecutable(true);
        return binary;
    }
}
