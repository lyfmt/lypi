package cn.lypi.contracts.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PermissionRuntimeStateTest {
    private final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new Jdk8Module())
        .findAndRegisterModules();

    @Test
    void mapsLegacyDefaultExecuteToWorkspaceRuntimeBehavior() {
        PermissionRuntimeState state = PermissionRuntimeState.fromLegacy(PermissionMode.DEFAULT_EXECUTE);

        assertEquals(":workspace", state.activePermissionProfile().id());
        assertEquals(ApprovalMode.ON_REQUEST, state.approvalPolicy().mode());
        assertFalse(state.legacyBehavior().defaultBashRequiresEscalation());
        assertFalse(state.legacyBehavior().allowExplicitEscalationWithoutPrompt());
        assertTrue(state.legacyBehavior().hardSafetyEnabled());
        assertEquals(PermissionMode.DEFAULT_EXECUTE, state.legacyPermissionMode());
    }

    @Test
    void mapsLegacyAcceptEditsToFullRuntimeBehavior() {
        PermissionRuntimeState state = PermissionRuntimeState.fromLegacy(PermissionMode.ACCEPT_EDITS);

        assertEquals(":workspace", state.activePermissionProfile().id());
        assertEquals(ApprovalMode.ON_REQUEST, state.approvalPolicy().mode());
        assertTrue(state.legacyBehavior().defaultBashRequiresEscalation());
        assertFalse(state.legacyBehavior().allowExplicitEscalationWithoutPrompt());
        assertTrue(state.legacyBehavior().hardSafetyEnabled());
        assertEquals(PermissionMode.ACCEPT_EDITS, state.legacyPermissionMode());
    }

    @Test
    void mapsLegacyBypassToDangerousRuntimeBehaviorWithoutDisablingHardSafety() {
        PermissionRuntimeState state = PermissionRuntimeState.fromLegacy(PermissionMode.BYPASS);

        assertEquals(":danger-full-access", state.activePermissionProfile().id());
        assertEquals(ApprovalMode.NEVER, state.approvalPolicy().mode());
        assertFalse(state.legacyBehavior().defaultBashRequiresEscalation());
        assertTrue(state.legacyBehavior().allowExplicitEscalationWithoutPrompt());
        assertTrue(state.legacyBehavior().hardSafetyEnabled());
        assertEquals(PermissionMode.BYPASS, state.legacyPermissionMode());
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
        PermissionRuntimeState state = PermissionRuntimeState.fromLegacy(PermissionMode.BYPASS);

        String json = mapper.writeValueAsString(state);
        PermissionRuntimeState restored = mapper.readValue(json, PermissionRuntimeState.class);

        assertTrue(json.contains("\"approvalPolicy\""));
        assertTrue(json.contains("\"activePermissionProfile\""));
        assertTrue(json.contains("\"legacyBehavior\""));
        assertEquals(ApprovalMode.NEVER, restored.approvalPolicy().mode());
        assertEquals(":danger-full-access", restored.activePermissionProfile().id());
        assertTrue(restored.legacyBehavior().allowExplicitEscalationWithoutPrompt());
        assertEquals(PermissionMode.BYPASS, restored.legacyPermissionMode());
    }
}
