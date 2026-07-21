package cn.lypi.contracts.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PermissionRuntimeStateTest {
    private final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new Jdk8Module())
        .findAndRegisterModules();

    @Test
    void exposesOnlyThreePermissionModes() {
        assertEquals(List.of(PermissionMode.ASK, PermissionMode.AUTO, PermissionMode.BYPASS), Arrays.asList(PermissionMode.values()));
    }

    @Test
    void mapsAskToWorkspaceRuntimeBehavior() {
        PermissionRuntimeState state = PermissionRuntimeState.forMode(PermissionMode.ASK);

        assertEquals(":workspace", state.activePermissionProfile().id());
        assertEquals(PermissionProfiles.workspace(), state.permissionProfile());
        assertEquals(ApprovalMode.ON_REQUEST, state.approvalPolicy().mode());
        assertFalse(state.legacyBehavior().defaultBashRequiresEscalation());
        assertFalse(state.legacyBehavior().allowExplicitEscalationWithoutPrompt());
        assertTrue(state.legacyBehavior().hardSafetyEnabled());
        assertEquals(PermissionMode.ASK, state.mode());
    }

    @Test
    void mapsAutoToWorkspaceRuntimeBehavior() {
        PermissionRuntimeState state = PermissionRuntimeState.forMode(PermissionMode.AUTO);

        assertEquals(":workspace", state.activePermissionProfile().id());
        assertEquals(PermissionProfiles.workspace(), state.permissionProfile());
        assertEquals(ApprovalMode.ON_REQUEST, state.approvalPolicy().mode());
        assertTrue(state.legacyBehavior().defaultBashRequiresEscalation());
        assertFalse(state.legacyBehavior().allowExplicitEscalationWithoutPrompt());
        assertTrue(state.legacyBehavior().hardSafetyEnabled());
        assertEquals(PermissionMode.AUTO, state.mode());
    }

    @Test
    void mapsBypassToDangerousRuntimeBehavior() {
        PermissionRuntimeState state = PermissionRuntimeState.forMode(PermissionMode.BYPASS);

        assertEquals(":danger-full-access", state.activePermissionProfile().id());
        assertEquals(PermissionProfiles.dangerFullAccess(), state.permissionProfile());
        assertEquals(ApprovalMode.NEVER, state.approvalPolicy().mode());
        assertFalse(state.legacyBehavior().defaultBashRequiresEscalation());
        assertTrue(state.legacyBehavior().allowExplicitEscalationWithoutPrompt());
        assertTrue(state.legacyBehavior().hardSafetyEnabled());
        assertEquals(PermissionMode.BYPASS, state.mode());
    }

    @Test
    void rejectsBlankActivePermissionProfileId() {
        assertThrows(IllegalArgumentException.class, () -> new ActivePermissionProfile(" "));
    }

    @Test
    void defensivelyCopiesWorkspaceRootsInOverrides() {
        List<Path> roots = new ArrayList<>();
        roots.add(Path.of("/workspace"));
        PermissionRuntimeOverrides overrides = new PermissionRuntimeOverrides(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(roots),
            Optional.empty(),
            Optional.empty()
        );

        roots.add(Path.of("/other"));

        assertEquals(List.of(Path.of("/workspace")), overrides.workspaceRoots().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> overrides.workspaceRoots().orElseThrow().add(Path.of("/third")));
    }

    @Test
    void runtimeStateRoundTripKeepsCanonicalFields() throws Exception {
        PermissionRuntimeState state = PermissionRuntimeState.forMode(PermissionMode.BYPASS);

        String json = mapper.writeValueAsString(state);
        PermissionRuntimeState restored = mapper.readValue(json, PermissionRuntimeState.class);

        assertTrue(json.contains("\"approvalPolicy\""));
        assertTrue(json.contains("\"activePermissionProfile\""));
        assertTrue(json.contains("\"permissionProfile\""));
        assertTrue(json.contains("\"legacyBehavior\""));
        assertTrue(json.contains("\"legacyPermissionMode\":\"bypass\""));
        assertEquals(ApprovalMode.NEVER, restored.approvalPolicy().mode());
        assertEquals(":danger-full-access", restored.activePermissionProfile().id());
        assertEquals(PermissionProfiles.dangerFullAccess(), restored.permissionProfile());
        assertTrue(restored.legacyBehavior().allowExplicitEscalationWithoutPrompt());
        assertEquals(PermissionMode.BYPASS, restored.legacyPermissionMode());
    }

    @Test
    void readsCanonicalAndLegacyPermissionModeStrings() throws Exception {
        assertEquals(PermissionMode.ASK, mapper.readValue("\"ask\"", PermissionMode.class));
        assertEquals(PermissionMode.AUTO, mapper.readValue("\"auto\"", PermissionMode.class));
        assertEquals(PermissionMode.BYPASS, mapper.readValue("\"bypass\"", PermissionMode.class));
        assertEquals(PermissionMode.ASK, mapper.readValue("\"DEFAULT_EXECUTE\"", PermissionMode.class));
        assertEquals(PermissionMode.AUTO, mapper.readValue("\"ACCEPT_EDITS\"", PermissionMode.class));
    }

    @Test
    void oldRuntimeStateJsonRestoresBuiltinProfileFromActiveId() throws Exception {
        String json = """
            {
              "approvalPolicy": {"mode": "ON_REQUEST"},
              "activePermissionProfile": {"id": ":workspace"},
              "legacyBehavior": {
                "defaultBashRequiresEscalation": false,
                "allowExplicitEscalationWithoutPrompt": false,
                "hardSafetyEnabled": true
              },
              "legacyPermissionMode": "DEFAULT_EXECUTE"
            }
            """;

        PermissionRuntimeState restored = mapper.readValue(json, PermissionRuntimeState.class);

        assertEquals(":workspace", restored.activePermissionProfile().id());
        assertEquals(PermissionProfiles.workspace(), restored.permissionProfile());
    }

    @Test
    void oldRuntimeStateJsonFallsBackToReadOnlyForUnknownProfileId() throws Exception {
        String json = """
            {
              "approvalPolicy": {"mode": "ON_REQUEST"},
              "activePermissionProfile": {"id": "dev"},
              "legacyBehavior": {
                "defaultBashRequiresEscalation": false,
                "allowExplicitEscalationWithoutPrompt": false,
                "hardSafetyEnabled": true
              },
              "legacyPermissionMode": "DEFAULT_EXECUTE"
            }
            """;

        PermissionRuntimeState restored = mapper.readValue(json, PermissionRuntimeState.class);

        assertEquals("dev", restored.activePermissionProfile().id());
        assertEquals(PermissionProfiles.readOnly(), restored.permissionProfile());
    }
}
