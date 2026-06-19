package cn.lypi.contracts.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class PermissionProfileTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readOnlyProfileRestrictsFilesystemAndNetwork() {
        ManagedPermissionProfile profile = PermissionProfiles.readOnly();

        assertEquals(PermissionProfile.Kind.MANAGED, profile.kind());
        assertEquals(FileSystemPolicyKind.RESTRICTED, profile.fileSystem().kind());
        assertEquals(NetworkPolicyMode.RESTRICTED, profile.network().mode());
        assertTrue(hasEntry(profile.fileSystem(), FileSystemPath.special(FileSystemSpecialPath.ROOT), FileSystemAccessMode.READ));
        assertFalse(hasEntry(
            profile.fileSystem(),
            FileSystemPath.special(FileSystemSpecialPath.PROJECT_ROOTS),
            FileSystemAccessMode.WRITE
        ));
    }

    @Test
    void workspaceProfileAllowsWorkspaceAndTempWritesWithRestrictedNetwork() {
        ManagedPermissionProfile profile = PermissionProfiles.workspace();

        assertEquals(PermissionProfile.Kind.MANAGED, profile.kind());
        assertEquals(FileSystemPolicyKind.RESTRICTED, profile.fileSystem().kind());
        assertEquals(NetworkPolicyMode.RESTRICTED, profile.network().mode());
        assertTrue(hasEntry(profile.fileSystem(), FileSystemPath.special(FileSystemSpecialPath.ROOT), FileSystemAccessMode.READ));
        assertTrue(hasEntry(
            profile.fileSystem(),
            FileSystemPath.special(FileSystemSpecialPath.PROJECT_ROOTS),
            FileSystemAccessMode.WRITE
        ));
        assertTrue(hasEntry(profile.fileSystem(), FileSystemPath.special(FileSystemSpecialPath.TMPDIR), FileSystemAccessMode.WRITE));
        assertTrue(hasEntry(profile.fileSystem(), FileSystemPath.special(FileSystemSpecialPath.SLASH_TMP), FileSystemAccessMode.WRITE));
        assertTrue(hasEntry(profile.fileSystem(), FileSystemPath.exactPath(".git"), FileSystemAccessMode.READ));
        assertTrue(hasEntry(profile.fileSystem(), FileSystemPath.exactPath(".agents"), FileSystemAccessMode.READ));
        assertTrue(hasEntry(profile.fileSystem(), FileSystemPath.exactPath(".codex"), FileSystemAccessMode.READ));
    }

    @Test
    void dangerFullAccessProfileProjectsToUnrestrictedFilesystemAndEnabledNetwork() {
        PermissionProfile profile = PermissionProfiles.dangerFullAccess();

        assertInstanceOf(DisabledPermissionProfile.class, profile);
        assertEquals(PermissionProfile.Kind.DISABLED, profile.kind());
        assertEquals(FileSystemPolicyKind.UNRESTRICTED, profile.fileSystem().kind());
        assertEquals(List.of(), profile.fileSystem().entries());
        assertEquals(NetworkPolicyMode.ENABLED, profile.network().mode());
    }

    @Test
    void externalProfileKeepsNetworkPolicyAndDelegatesFilesystemSandboxing() {
        NetworkPermissionPolicy network = NetworkPermissionPolicy.enabled();
        ExternalPermissionProfile profile = PermissionProfiles.external(network);

        assertEquals(PermissionProfile.Kind.EXTERNAL, profile.kind());
        assertEquals(FileSystemPolicyKind.EXTERNAL_SANDBOX, profile.fileSystem().kind());
        assertSame(network, profile.network());
    }

    @Test
    void permissionProfileRoundTripKeepsConcreteManagedProfile() throws Exception {
        PermissionProfile profile = PermissionProfiles.workspace();

        String json = mapper.writeValueAsString(profile);
        PermissionProfile restored = mapper.readValue(json, PermissionProfile.class);

        assertTrue(json.contains("\"type\":\"managed\""));
        assertInstanceOf(ManagedPermissionProfile.class, restored);
        assertEquals(PermissionProfile.Kind.MANAGED, restored.kind());
        assertTrue(hasEntry(restored.fileSystem(), FileSystemPath.special(FileSystemSpecialPath.PROJECT_ROOTS), FileSystemAccessMode.WRITE));
    }

    @Test
    void workspaceRootsSpecialPathUsesCodexJsonString() throws Exception {
        String json = mapper.writeValueAsString(FileSystemSpecialPath.PROJECT_ROOTS);

        assertEquals("\":workspace_roots\"", json);
        assertEquals(
            FileSystemSpecialPath.PROJECT_ROOTS,
            mapper.readValue("\":workspace_roots\"", FileSystemSpecialPath.class)
        );
    }

    @Test
    void specialPathDoesNotExposeUnknownAsCanonicalValue() {
        assertFalse(Arrays.asList(FileSystemSpecialPath.values()).contains(null));
        assertTrue(Arrays.stream(FileSystemSpecialPath.values())
            .map(FileSystemSpecialPath::jsonValue)
            .allMatch(value -> value.startsWith(":")));
    }

    @Test
    void rejectsUnknownSpecialPathJson() {
        assertThrows(JsonMappingException.class, () ->
            mapper.readValue("\"unknown\"", FileSystemSpecialPath.class));
        assertThrows(JsonMappingException.class, () ->
            mapper.readValue("\":unknown\"", FileSystemSpecialPath.class));
    }

    @Test
    void rejectsUnknownSpecialPathInsideFilesystemPath() {
        assertThrows(IllegalArgumentException.class, () ->
            new FileSystemPath(FileSystemPath.Kind.SPECIAL, ":unknown"));
        assertThrows(IllegalArgumentException.class, () ->
            new FileSystemPath(FileSystemPath.Kind.SPECIAL, "/tmp"));
        assertThrows(IllegalArgumentException.class, () ->
            new FileSystemPath(FileSystemPath.Kind.EXACT_PATH, ":root"));
    }

    @Test
    void rejectsUnknownSpecialPathInsideFilesystemPathJson() {
        String json = """
            {
              "kind": "SPECIAL",
              "value": ":unknown"
            }
            """;

        assertThrows(JsonMappingException.class, () -> mapper.readValue(json, FileSystemPath.class));
    }

    @Test
    void unrestrictedAndExternalFilesystemPoliciesRejectEntries() {
        FileSystemPermissionEntry entry = new FileSystemPermissionEntry(
            FileSystemPath.special(FileSystemSpecialPath.ROOT),
            FileSystemAccessMode.READ
        );

        assertThrows(IllegalArgumentException.class, () ->
            new FileSystemPermissionPolicy(FileSystemPolicyKind.UNRESTRICTED, List.of(entry)));
        assertThrows(IllegalArgumentException.class, () ->
            new FileSystemPermissionPolicy(FileSystemPolicyKind.EXTERNAL_SANDBOX, List.of(entry)));
        assertThrows(IllegalArgumentException.class, () ->
            new FileSystemPermissionPolicy(FileSystemPolicyKind.RESTRICTED, null));
    }

    private boolean hasEntry(FileSystemPermissionPolicy policy, FileSystemPath path, FileSystemAccessMode access) {
        return policy.entries().stream()
            .anyMatch(entry -> entry.path().equals(path) && entry.access() == access);
    }
}
