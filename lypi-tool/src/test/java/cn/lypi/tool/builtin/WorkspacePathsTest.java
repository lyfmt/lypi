package cn.lypi.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.FileSystemAccessMode;
import cn.lypi.contracts.security.FileSystemPath;
import cn.lypi.contracts.security.FileSystemPermissionEntry;
import cn.lypi.contracts.security.FileSystemPermissionPolicy;
import cn.lypi.contracts.security.FileSystemSpecialPath;
import cn.lypi.contracts.tool.ToolUseContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspacePathsTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesMissingPathRelativeToWorkspaceAndRejectsTraversal() {
        ToolUseContext context = context();

        assertEquals(tempDir.resolve("notes.txt"), WorkspacePaths.resolvePath(Map.of("path", "notes.txt"), context, "path"));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> WorkspacePaths.resolvePath(Map.of("path", "../outside.txt"), context, "path")
        );
        assertTrue(exception.getMessage().contains("路径越过当前工作目录"));
    }

    @Test
    void resolvesApprovedOutsidePathFromAdditionalPermissions(@TempDir Path outsideDir) {
        ToolUseContext context = context(additionalFileSystem(outsideDir, FileSystemAccessMode.WRITE));

        Path resolved = WorkspacePaths.resolvePath(
            Map.of("path", outsideDir.resolve("notes.txt").toString()),
            context,
            "path",
            FileSystemAccessMode.WRITE
        );

        assertEquals(outsideDir.resolve("notes.txt"), resolved);
    }

    @Test
    void readOnlyAdditionalPermissionDoesNotAllowWriteResolution(@TempDir Path outsideDir) {
        ToolUseContext context = context(additionalFileSystem(outsideDir, FileSystemAccessMode.READ));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> WorkspacePaths.resolvePath(
                Map.of("path", outsideDir.resolve("notes.txt").toString()),
                context,
                "path",
                FileSystemAccessMode.WRITE
            )
        );

        assertTrue(exception.getMessage().contains("路径越过当前工作目录"));
    }

    @Test
    void approvedAdditionalPermissionsDoNotResolveWidePolicies(@TempDir Path outsideDir) {
        IllegalArgumentException unrestricted = assertThrows(
            IllegalArgumentException.class,
            () -> WorkspacePaths.resolvePath(
                Map.of("path", outsideDir.resolve("notes.txt").toString()),
                context(new AdditionalPermissionProfile(
                    Optional.of(FileSystemPermissionPolicy.unrestricted()),
                    Optional.empty()
                )),
                "path",
                FileSystemAccessMode.WRITE
            )
        );
        IllegalArgumentException specialRoot = assertThrows(
            IllegalArgumentException.class,
            () -> WorkspacePaths.resolvePath(
                Map.of("path", outsideDir.resolve("notes.txt").toString()),
                context(new AdditionalPermissionProfile(
                    Optional.of(FileSystemPermissionPolicy.restricted(List.of(
                        new FileSystemPermissionEntry(
                            FileSystemPath.special(FileSystemSpecialPath.ROOT),
                            FileSystemAccessMode.WRITE
                        )
                    ))),
                    Optional.empty()
                )),
                "path",
                FileSystemAccessMode.WRITE
            )
        );

        assertTrue(unrestricted.getMessage().contains("路径越过当前工作目录"));
        assertTrue(specialRoot.getMessage().contains("路径越过当前工作目录"));
    }

    @Test
    void rendersRelativePathOnlyForWorkspaceChildren() {
        ToolUseContext context = context();

        assertEquals("nested/file.txt", WorkspacePaths.relativePath(tempDir.resolve("nested/file.txt"), context));
        assertEquals(".", WorkspacePaths.relativePath(tempDir, context));
        assertTrue(WorkspacePaths.relativePath(tempDir.getParent(), context).endsWith(tempDir.getParent().toString()));
    }

    @Test
    void realPathChecksRejectSymlinkEscapes() throws Exception {
        Path outside = Files.createTempDirectory("lypi-outside");
        Path link = tempDir.resolve("escape");
        try {
            Files.createSymbolicLink(link, outside);

            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> WorkspacePaths.requireRealPathInsideWorkspace(link, context())
            );

            assertTrue(exception.getMessage().contains("路径经符号链接越过当前工作目录"));
            assertFalse(WorkspacePaths.realPathInsideWorkspace(link, context()));
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void writesAtomicallyAndCleansTempFileOnFailure() throws Exception {
        Path file = tempDir.resolve("a.txt");

        WorkspacePaths.writeAtomically(file, "hello");

        assertEquals("hello", Files.readString(file));
    }

    private ToolUseContext context() {
        return new ToolUseContext("ses_1", "msg_1", tempDir, Map.of());
    }

    private ToolUseContext context(AdditionalPermissionProfile additionalPermissions) {
        return new ToolUseContext("ses_1", "msg_1", tempDir, Map.of(
            "additionalPermissions", additionalPermissions,
            "approvedAdditionalPermissions", true
        ));
    }

    private AdditionalPermissionProfile additionalFileSystem(Path path, FileSystemAccessMode accessMode) {
        return new AdditionalPermissionProfile(
            Optional.of(FileSystemPermissionPolicy.restricted(List.of(
                new FileSystemPermissionEntry(FileSystemPath.exactPath(path.toString()), accessMode)
            ))),
            Optional.empty()
        );
    }
}
