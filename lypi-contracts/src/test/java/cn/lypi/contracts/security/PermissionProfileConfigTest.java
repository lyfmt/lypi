package cn.lypi.contracts.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PermissionProfileConfigTest {
    @Test
    void normalizesNullOptionalFieldsAndWorkspaceRoots() {
        PermissionProfileConfig config = new PermissionProfileConfig(
            null,
            null,
            null,
            null,
            null
        );

        assertEquals("", config.description());
        assertEquals(Optional.empty(), config.extendsProfile());
        assertEquals(List.of(), config.workspaceRoots());
        assertEquals(Optional.empty(), config.fileSystem());
        assertEquals(Optional.empty(), config.network());
    }

    @Test
    void defensivelyCopiesWorkspaceRoots() {
        List<Path> roots = new ArrayList<>();
        roots.add(Path.of("/workspace"));

        PermissionProfileConfig config = new PermissionProfileConfig(
            "Dev profile",
            Optional.of(":workspace"),
            roots,
            Optional.empty(),
            Optional.empty()
        );

        roots.add(Path.of("/other"));

        assertEquals(List.of(Path.of("/workspace")), config.workspaceRoots());
        assertThrows(UnsupportedOperationException.class, () -> config.workspaceRoots().add(Path.of("/third")));
    }

    @Test
    void rejectsBlankParentAndWorkspaceRoot() {
        assertThrows(IllegalArgumentException.class, () -> new PermissionProfileConfig(
            "Blank parent",
            Optional.of(" "),
            List.of(),
            Optional.empty(),
            Optional.empty()
        ));

        assertThrows(IllegalArgumentException.class, () -> new PermissionProfileConfig(
            "Blank workspace root",
            Optional.empty(),
            List.of(Path.of("")),
            Optional.empty(),
            Optional.empty()
        ));
    }
}
