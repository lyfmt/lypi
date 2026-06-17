package cn.lypi.contracts.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.security.FileSystemAccessMode;
import cn.lypi.contracts.security.FileSystemPath;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionRuleValue;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.security.PermissionUpdate;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PermissionRuntimeStateEntryTest {
    private final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new Jdk8Module())
        .registerModule(new JavaTimeModule());

    @Test
    void permissionRuntimeStateChangeEntryRoundTripKeepsCanonicalStateAndLegacyAccessor() throws Exception {
        SessionEntry entry = new PermissionRuntimeStateChangeEntry(
            "entry_permission_runtime",
            "entry_parent",
            PermissionRuntimeState.fromLegacy(PermissionMode.ACCEPT_EDITS),
            Instant.parse("2026-06-17T00:00:00Z")
        );

        String json = mapper.writeValueAsString(entry);
        SessionEntry restored = mapper.readValue(json, SessionEntry.class);

        assertTrue(json.contains("\"type\":\"permission_runtime_state_change\""));
        PermissionRuntimeStateChangeEntry stateEntry = assertInstanceOf(PermissionRuntimeStateChangeEntry.class, restored);
        assertEquals(PermissionRuntimeState.fromLegacy(PermissionMode.ACCEPT_EDITS), stateEntry.permissionRuntimeState());
        assertEquals(PermissionMode.ACCEPT_EDITS, stateEntry.permissionMode());
    }

    @Test
    void permissionAmendmentEntryNormalizesOptionalPayloads() throws Exception {
        PermissionUpdate update = new PermissionUpdate(
            PermissionRuleSource.SESSION,
            new PermissionRule(
                PermissionRuleSource.SESSION,
                PermissionBehavior.ALLOW,
                new PermissionRuleValue("bash", "exec"),
                "remember current session approval"
            )
        );
        PermissionAmendmentEntry entry = new PermissionAmendmentEntry(
            "entry_permission_amendment",
            "entry_parent",
            Optional.of(update),
            Optional.empty(),
            Instant.parse("2026-06-17T00:00:00Z")
        );

        String json = mapper.writeValueAsString(entry);
        PermissionAmendmentEntry restored = mapper.readValue(json, PermissionAmendmentEntry.class);

        assertEquals(Optional.of(update), restored.permissionUpdate());
        assertEquals(Optional.empty(), restored.networkPolicyAmendment());
        assertTrue(json.contains("\"permissionUpdate\""));
    }

    @Test
    void permissionAmendmentEntryRequiresAtLeastOnePayload() {
        assertThrows(IllegalArgumentException.class, () -> new PermissionAmendmentEntry(
            "entry_permission_amendment",
            "entry_parent",
            Optional.empty(),
            Optional.empty(),
            Instant.parse("2026-06-17T00:00:00Z")
        ));
    }

    @Test
    void permissionAmendmentEntryRejectsJsonWithEmptyPayloads() {
        String json = """
            {
              "type": "permission_amendment",
              "id": "entry_permission_amendment",
              "parentId": "entry_parent",
              "permissionUpdate": null,
              "networkPolicyAmendment": null,
              "timestamp": "2026-06-17T00:00:00Z"
            }
            """;

        assertThrows(ValueInstantiationException.class, () -> mapper.readValue(json, SessionEntry.class));
    }
}
