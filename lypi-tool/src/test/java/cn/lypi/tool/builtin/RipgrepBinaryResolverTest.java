package cn.lypi.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
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
            relativeToWorkingDirectory(tempDir)
        );

        RipgrepBinary command = resolver.resolve(Map.of());

        Path resolved = Path.of(command.command());
        assertEquals(binary.toRealPath(), resolved);
        assertTrue(resolved.isAbsolute());
        assertTrue(Files.isRegularFile(resolved));
        assertTrue(Files.isExecutable(resolved));
        assertEquals("vendor", command.mode());
    }

    @Test
    void extractsClasspathRipgrepResourceToCacheWhenPackagedInJar() throws Exception {
        Path jar = tempDir.resolve("ripgrep-test.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("ripgrep/x86_64-linux/rg"));
            output.write("#!/bin/sh\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        Path cacheRoot = tempDir.resolve("cache");
        Path relativeCacheRoot = relativeToWorkingDirectory(cacheRoot);
        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            RipgrepBinaryResolver resolver = RipgrepBinaryResolver.forTesting(
                new RipgrepPlatform("linux", "x86_64"),
                tempDir.resolve("missing-resources"),
                relativeCacheRoot,
                classLoader
            );

            RipgrepBinary command = resolver.resolve(Map.of());

            Path extracted = Path.of(command.command());
            assertTrue(extracted.startsWith(cacheRoot));
            assertTrue(extracted.isAbsolute());
            assertTrue(Files.isRegularFile(extracted));
            assertTrue(Files.isExecutable(extracted));
            assertEquals("vendor", command.mode());
        }
    }

    @Test
    void reusesExistingCachedRipgrepWithoutOverwritingRunningBinary() throws Exception {
        Path jar = tempDir.resolve("ripgrep-test.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("ripgrep/x86_64-linux/rg"));
            output.write("first\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        Path cacheRoot = tempDir.resolve("cache");
        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            RipgrepBinaryResolver resolver = RipgrepBinaryResolver.forTesting(
                new RipgrepPlatform("linux", "x86_64"),
                tempDir.resolve("missing-resources"),
                cacheRoot,
                classLoader
            );
            Path extracted = Path.of(resolver.resolve(Map.of()).command());
            Files.writeString(extracted, "running\n");
            extracted.toFile().setExecutable(true);

            Path second = Path.of(resolver.resolve(Map.of()).command());

            assertEquals(extracted, second);
            assertEquals("running\n", Files.readString(second));
        }
    }

    @Test
    void systemModeResolvesAnAbsoluteExecutableFile() throws Exception {
        Path systemDirectory = Files.createDirectories(tempDir.resolve("system-bin"));
        Path executable = systemDirectory.resolve("rg");
        Files.writeString(executable, "#!/bin/sh\n");
        executable.toFile().setExecutable(true);
        RipgrepBinaryResolver resolver = RipgrepBinaryResolver.forTesting(
            new RipgrepPlatform("linux", "x86_64"),
            tempDir,
            tempDir.resolve("cache"),
            RipgrepBinaryResolver.class.getClassLoader(),
            List.of(relativeToWorkingDirectory(systemDirectory))
        );

        RipgrepBinary command = resolver.resolve(Map.of("lypi.tool.grep.ripgrep.mode", "system"));

        Path resolved = Path.of(command.command());
        assertEquals(executable.toRealPath(), resolved);
        assertTrue(resolved.isAbsolute());
        assertTrue(Files.isRegularFile(resolved));
        assertTrue(Files.isExecutable(resolved));
        assertEquals("system", command.mode());
    }

    @Test
    void systemModeRejectsDirectoriesAndNonExecutableFiles() throws Exception {
        Path directoryCandidate = Files.createDirectories(tempDir.resolve("directory-bin/rg"));
        Path nonExecutableDirectory = Files.createDirectories(tempDir.resolve("non-executable-bin"));
        Path nonExecutable = Files.writeString(nonExecutableDirectory.resolve("rg"), "#!/bin/sh\n");
        assertTrue(Files.isDirectory(directoryCandidate));
        assertTrue(Files.isRegularFile(nonExecutable));
        assertFalse(Files.isExecutable(nonExecutable));
        RipgrepBinaryResolver resolver = RipgrepBinaryResolver.forTesting(
            new RipgrepPlatform("linux", "x86_64"),
            tempDir.resolve("missing-resources"),
            tempDir.resolve("cache"),
            RipgrepBinaryResolver.class.getClassLoader(),
            List.of(directoryCandidate.getParent(), nonExecutableDirectory)
        );

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> resolver.resolve(Map.of("lypi.tool.grep.ripgrep.mode", "system"))
        );

        assertTrue(exception.getMessage().contains("未找到可执行的系统 ripgrep"));
    }

    @Test
    void systemModeRejectsAnEmptySearchPath() {
        RipgrepBinaryResolver resolver = RipgrepBinaryResolver.forTesting(
            new RipgrepPlatform("linux", "x86_64"),
            tempDir.resolve("missing-resources"),
            tempDir.resolve("cache"),
            RipgrepBinaryResolver.class.getClassLoader(),
            List.of()
        );

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> resolver.resolve(Map.of("lypi.tool.grep.ripgrep.mode", "system"))
        );

        assertTrue(exception.getMessage().contains("未找到可执行的系统 ripgrep"));
    }

    @Test
    void rejectsMissingVendorBinaryWithoutSystemOverride() throws Exception {
        try (URLClassLoader classLoader = new URLClassLoader(new URL[0], null)) {
            RipgrepBinaryResolver resolver = RipgrepBinaryResolver.forTesting(
                new RipgrepPlatform("linux", "x86_64"),
                tempDir,
                tempDir.resolve("cache"),
                classLoader
            );

            IllegalStateException exception = assertThrows(IllegalStateException.class, () -> resolver.resolve(Map.of()));

            assertTrue(exception.getMessage().contains("未找到随包 ripgrep"));
            assertTrue(exception.getMessage().contains("x86_64-linux"));
        }
    }

    private Path vendorBinary(String relativePath) throws Exception {
        Path binary = tempDir.resolve(relativePath);
        Files.createDirectories(binary.getParent());
        Files.writeString(binary, "#!/bin/sh\n");
        binary.toFile().setExecutable(true);
        return binary;
    }

    private Path relativeToWorkingDirectory(Path path) {
        return Path.of("").toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
    }
}
