package cn.lypi.tool;

import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockingPermissionGateTest {
    @Test
    void returnsAllowFromPromptPort() {
        RecordingPromptPort prompt = new RecordingPromptPort(PermissionGateResult.allow());
        BlockingPermissionGate gate = new BlockingPermissionGate(prompt);

        PermissionGateResult result = gate.request(
            request(),
            TestTools.echo("write", List.of(), false, false, true),
            TestTools.toolContext(cn.lypi.contracts.security.PermissionMode.DEFAULT_EXECUTE),
            TestTools.decision(PermissionBehavior.ASK, "需要确认")
        );

        assertEquals(PermissionGateResult.Status.ALLOW, result.status());
        assertEquals("toolu_1", prompt.handle.request().toolUseId());
        assertEquals("write", prompt.handle.tool().name());
        assertEquals(PermissionBehavior.ASK, prompt.handle.decision().behavior());
    }

    @Test
    void returnsDenyFromPromptPort() {
        BlockingPermissionGate gate = new BlockingPermissionGate(new RecordingPromptPort(PermissionGateResult.deny("用户拒绝")));

        PermissionGateResult result = gate.request(
            request(),
            TestTools.echo("write", List.of(), false, false, true),
            TestTools.toolContext(cn.lypi.contracts.security.PermissionMode.DEFAULT_EXECUTE),
            TestTools.decision(PermissionBehavior.ASK, "需要确认")
        );

        assertEquals(PermissionGateResult.Status.DENY, result.status());
        assertEquals("用户拒绝", result.message().orElseThrow());
    }

    @Test
    void returnsAbortFromPromptPort() {
        BlockingPermissionGate gate = new BlockingPermissionGate(new RecordingPromptPort(PermissionGateResult.abort("用户取消")));

        PermissionGateResult result = gate.request(
            request(),
            TestTools.echo("write", List.of(), false, false, true),
            TestTools.toolContext(cn.lypi.contracts.security.PermissionMode.DEFAULT_EXECUTE),
            TestTools.decision(PermissionBehavior.ASK, "需要确认")
        );

        assertEquals(PermissionGateResult.Status.ABORT, result.status());
        assertEquals("用户取消", result.message().orElseThrow());
    }

    @Test
    void deniesWhenPromptPortReturnsNull() {
        BlockingPermissionGate gate = new BlockingPermissionGate(new RecordingPromptPort(null));

        PermissionGateResult result = gate.request(
            request(),
            TestTools.echo("write", List.of(), false, false, true),
            TestTools.toolContext(cn.lypi.contracts.security.PermissionMode.DEFAULT_EXECUTE),
            TestTools.decision(PermissionBehavior.ASK, "需要确认")
        );

        assertEquals(PermissionGateResult.Status.DENY, result.status());
        assertTrue(result.message().orElseThrow().contains("未获允许"));
    }

    @Test
    void abortsAndRestoresInterruptWhenPromptPortIsInterrupted() {
        AtomicBoolean interruptedInsidePrompt = new AtomicBoolean();
        BlockingPermissionGate gate = new BlockingPermissionGate(handle -> {
            interruptedInsidePrompt.set(true);
            throw new InterruptedException("interrupted");
        });

        PermissionGateResult result = gate.request(
            request(),
            TestTools.echo("write", List.of(), false, false, true),
            TestTools.toolContext(cn.lypi.contracts.security.PermissionMode.DEFAULT_EXECUTE),
            TestTools.decision(PermissionBehavior.ASK, "需要确认")
        );

        assertTrue(interruptedInsidePrompt.get());
        assertEquals(PermissionGateResult.Status.ABORT, result.status());
        assertTrue(Thread.currentThread().isInterrupted());
        assertTrue(Thread.interrupted());
    }

    @Test
    void denyingFallbackIsUsedWhenPromptPortIsNull() {
        BlockingPermissionGate gate = new BlockingPermissionGate(null);

        PermissionGateResult result = gate.request(
            request(),
            TestTools.echo("write", List.of(), false, false, true),
            TestTools.toolContext(cn.lypi.contracts.security.PermissionMode.DEFAULT_EXECUTE),
            TestTools.decision(PermissionBehavior.ASK, "需要确认")
        );

        assertEquals(PermissionGateResult.Status.DENY, result.status());
        assertFalse(result.message().orElseThrow().isBlank());
    }

    private ToolUseRequest request() {
        return new ToolUseRequest("toolu_1", "write", Map.of("path", "a.txt"), "msg_1");
    }

    private static final class RecordingPromptPort implements PermissionPromptPort {
        private final PermissionGateResult result;
        private PermissionPromptPort.Handle handle;

        private RecordingPromptPort(PermissionGateResult result) {
            this.result = result;
        }

        @Override
        public PermissionGateResult ask(Handle handle) {
            this.handle = handle;
            return result;
        }
    }
}
