package cn.lypi.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.security.NetworkPermissionPolicy;
import cn.lypi.contracts.security.NetworkPolicyAmendment;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionGrantScope;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionRuleValue;
import cn.lypi.contracts.security.PermissionUpdate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PermissionAmendmentStoreTest {
    @TempDir
    private Path tempDir;

    @Test
    void appendsAndReadsExecPolicyPrefixAmendment() {
        FilePermissionAmendmentStore store = new FilePermissionAmendmentStore(tempDir);
        PermissionUpdate update = prefixUpdate(PermissionRuleSource.USER, "go test");

        store.appendPermissionUpdate(update, PermissionGrantScope.SESSION);

        List<PermissionUpdate> updates = store.readPermissionUpdates(PermissionGrantScope.SESSION);
        assertEquals(List.of(update), updates);
        assertTrue(store.readPermissionUpdates(PermissionGrantScope.TURN).isEmpty());
    }

    @Test
    void sessionPermissionAmendmentsAreFilteredBySessionId() {
        FilePermissionAmendmentStore store = new FilePermissionAmendmentStore(tempDir);
        PermissionUpdate sessionOneUpdate = prefixUpdate(PermissionRuleSource.USER, "go test");
        PermissionUpdate sessionTwoUpdate = prefixUpdate(PermissionRuleSource.USER, "mvn test");

        store.appendPermissionUpdate(sessionOneUpdate, PermissionGrantScope.SESSION, "ses_1");
        store.appendPermissionUpdate(sessionTwoUpdate, PermissionGrantScope.SESSION, "ses_2");

        assertEquals(List.of(sessionOneUpdate), store.readPermissionUpdates(PermissionGrantScope.SESSION, "ses_1"));
        assertEquals(List.of(sessionTwoUpdate), store.readPermissionUpdates(PermissionGrantScope.SESSION, "ses_2"));
    }

    @Test
    void runtimeSessionReadsIgnoreLegacyGlobalSessionAmendments() {
        FilePermissionAmendmentStore store = new FilePermissionAmendmentStore(tempDir);
        PermissionUpdate legacyGlobalUpdate = prefixUpdate(PermissionRuleSource.USER, "go test");

        store.appendPermissionUpdate(legacyGlobalUpdate, PermissionGrantScope.SESSION);

        assertEquals(List.of(legacyGlobalUpdate), store.readPermissionUpdates(PermissionGrantScope.SESSION));
        assertTrue(store.readPermissionUpdates(PermissionGrantScope.SESSION, "ses_1").isEmpty());
    }

    @Test
    void turnPermissionAmendmentsAreFilteredByTurnId() {
        FilePermissionAmendmentStore store = new FilePermissionAmendmentStore(tempDir);
        PermissionUpdate turnOneUpdate = prefixUpdate(PermissionRuleSource.USER, "go test");
        PermissionUpdate turnTwoUpdate = prefixUpdate(PermissionRuleSource.USER, "mvn test");

        store.appendPermissionUpdate(turnOneUpdate, PermissionGrantScope.TURN, "ses_1", "turn_1");
        store.appendPermissionUpdate(turnTwoUpdate, PermissionGrantScope.TURN, "ses_1", "turn_2");

        assertEquals(List.of(turnOneUpdate), store.readPermissionUpdates(PermissionGrantScope.TURN, "ses_1", "turn_1"));
        assertEquals(List.of(turnTwoUpdate), store.readPermissionUpdates(PermissionGrantScope.TURN, "ses_1", "turn_2"));
        assertTrue(store.readPermissionUpdates(PermissionGrantScope.TURN, "ses_2", "turn_1").isEmpty());
    }

    @Test
    void appendsAndReadsNetworkPolicyAmendment() {
        FilePermissionAmendmentStore store = new FilePermissionAmendmentStore(tempDir);
        NetworkPolicyAmendment amendment = new NetworkPolicyAmendment(NetworkPermissionPolicy.enabled());

        store.appendNetworkPolicyAmendment(amendment, PermissionGrantScope.TURN);

        assertEquals(List.of(amendment), store.readNetworkPolicyAmendments(PermissionGrantScope.TURN));
        assertTrue(store.readNetworkPolicyAmendments(PermissionGrantScope.SESSION).isEmpty());
    }

    @Test
    void rejectsInvalidPermissionUpdatesFailClosed() {
        FilePermissionAmendmentStore store = new FilePermissionAmendmentStore(tempDir);
        PermissionUpdate invalid = new PermissionUpdate(
            PermissionRuleSource.PLATFORM,
            new PermissionRule(
                PermissionRuleSource.PLATFORM,
                PermissionBehavior.DENY,
                new PermissionRuleValue("bash", "prefix:rm"),
                "invalid"
            )
        );

        assertThrows(IllegalArgumentException.class, () ->
            store.appendPermissionUpdate(invalid, PermissionGrantScope.SESSION)
        );
        assertFalse(Files.exists(tempDir.resolve(".ly-pi/permissions.jsonl")));
    }

    @Test
    void rejectsUnsupportedBashPrefixesFailClosed() {
        FilePermissionAmendmentStore store = new FilePermissionAmendmentStore(tempDir);

        assertThrows(IllegalArgumentException.class, () ->
            store.appendPermissionUpdate(prefixUpdate(PermissionRuleSource.USER, "git"), PermissionGrantScope.SESSION)
        );
        assertThrows(IllegalArgumentException.class, () ->
            store.appendPermissionUpdate(prefixUpdate(PermissionRuleSource.USER, "bash -lc"), PermissionGrantScope.SESSION)
        );
        assertFalse(Files.exists(tempDir.resolve(".ly-pi/permissions.jsonl")));
    }

    @Test
    void rejectsInvalidPersistedPermissionUpdatesFailClosed() throws Exception {
        FilePermissionAmendmentStore store = new FilePermissionAmendmentStore(tempDir);
        Files.createDirectories(tempDir.resolve(".ly-pi"));
        Files.writeString(
            tempDir.resolve(".ly-pi/permissions.jsonl"),
            """
                {"scope":"SESSION","id":"perm_amend_invalid","permissionUpdate":{"targetSource":"PLATFORM","rule":{"source":"PLATFORM","behavior":"ALLOW","value":{"toolName":"bash","pattern":"prefix:mvn test"},"reason":"invalid"}},"networkPolicyAmendment":null,"timestamp":"1970-01-01T00:00:00Z"}
                """
        );

        assertThrows(IllegalArgumentException.class, () ->
            store.readPermissionUpdates(PermissionGrantScope.SESSION)
        );
    }

    @Test
    void legacyUpdateStoreIsReadOnlyImporterAndDoesNotAppendRules() throws Exception {
        FilePermissionUpdateStore legacyStore = new FilePermissionUpdateStore(tempDir);

        legacyStore.append(prefixUpdate(PermissionRuleSource.USER, "mvn test"));

        assertFalse(Files.exists(tempDir.resolve("rules/default.rules")));

        Files.createDirectories(tempDir.resolve("rules"));
        Files.writeString(
            tempDir.resolve("rules/default.rules"),
            "prefix_rule(pattern=[\"mvn\", \"test\"], decision=\"allow\")\n"
                + "prefix_rule(pattern=[\"bash\", \"-lc\"], decision=\"allow\")\n"
        );

        assertEquals(
            List.of(prefixUpdate(PermissionRuleSource.USER, "mvn test")),
            legacyStore.readPermissionUpdates()
        );
    }

    private PermissionUpdate prefixUpdate(PermissionRuleSource source, String prefix) {
        return new PermissionUpdate(
            source,
            new PermissionRule(
                source,
                PermissionBehavior.ALLOW,
                new PermissionRuleValue("bash", "prefix:" + prefix),
                "允许 Bash prefix: " + prefix
            )
        );
    }
}
