package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.agent.TurnState;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventConsumer;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.event.InterruptEvent;
import cn.lypi.contracts.event.MessageDeltaEvent;
import cn.lypi.contracts.event.MessageEndEvent;
import cn.lypi.contracts.event.MessageStartEvent;
import cn.lypi.contracts.event.PermissionResponseEvent;
import cn.lypi.contracts.runtime.AgentCorePort;
import cn.lypi.contracts.tui.SlashCommand;
import cn.lypi.contracts.tui.SlashCommandHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RuntimeTuiSubmitHandlerTest {
    @Test
    void submitCreatesIndependentAbortSignalPerTurn() {
        RecordingCore core = new RecordingCore();
        RecordingEventBus events = new RecordingEventBus();
        RuntimeTuiSubmitHandler handler = new RuntimeTuiSubmitHandler("ses_1", core, events, Runnable::run);

        handler.submitUserInput("first");
        TurnRequest first = core.requests.getFirst();
        handler.submitUserInput("second");
        TurnRequest second = core.requests.get(1);

        assertEquals("ses_1", first.sessionId());
        assertEquals("first", first.userInput());
        assertEquals("second", second.userInput());
        assertNotSame(first.abortSignal(), second.abortSignal());
        assertFalse(first.abortSignal().aborted());
        assertFalse(second.abortSignal().aborted());
    }

    @Test
    void interruptOnlyAbortsCurrentActiveTurnAndPublishesEvent() {
        RecordingCore core = new RecordingCore();
        RecordingEventBus events = new RecordingEventBus();
        RuntimeTuiSubmitHandler handler = new RuntimeTuiSubmitHandler("ses_1", core, events, Runnable::run);

        handler.submitUserInput("first");
        TurnRequest first = core.requests.getFirst();
        handler.submitUserInput("second");
        TurnRequest second = core.requests.get(1);

        handler.requestInterrupt("ctrl-c");

        assertFalse(first.abortSignal().aborted());
        assertTrue(second.abortSignal().aborted());
        InterruptEvent event = assertInstanceOf(InterruptEvent.class, events.published.getFirst());
        assertEquals("ses_1", event.sessionId());
        assertEquals("ctrl-c", event.reason());
    }

    @Test
    void defaultSubmitDoesNotBlockOnCoreExecution() throws Exception {
        BlockingCore core = new BlockingCore();
        RecordingEventBus events = new RecordingEventBus();
        RuntimeTuiSubmitHandler handler = new RuntimeTuiSubmitHandler("ses_1", core, events);

        handler.submitUserInput("hello");

        assertTrue(core.started.await(2, TimeUnit.SECONDS));
        assertEquals(1, core.requests.size());
        core.release.countDown();
    }

    @Test
    void submitPermissionOptionPublishesResponseEvent() {
        RecordingCore core = new RecordingCore();
        RecordingEventBus events = new RecordingEventBus();
        RuntimeTuiSubmitHandler handler = new RuntimeTuiSubmitHandler("ses_1", core, events, Runnable::run);

        handler.submitPermissionOption("perm_toolu_1", "toolu_1", "allow_once");

        PermissionResponseEvent event = assertInstanceOf(PermissionResponseEvent.class, events.published.getFirst());
        assertEquals("ses_1", event.sessionId());
        assertEquals("perm_toolu_1", event.requestId());
        assertEquals("allow_once", event.selectedOptionId());
        assertEquals(false, event.fromKeyboardCancel());
    }

    @Test
    void slashCommandInputRunsHandlerAndPublishesLocalOutputWithoutStartingTurn() {
        RecordingCore core = new RecordingCore();
        RecordingEventBus events = new RecordingEventBus();
        RecordingSlashCommandHandler slash = new RecordingSlashCommandHandler("mailId: mail_1");
        RuntimeTuiSubmitHandler handler = new RuntimeTuiSubmitHandler(
            "ses_1",
            core,
            events,
            Runnable::run,
            List.of(new SlashCommand("mailbox", "读取 mailbox", List.of(), slash))
        );

        handler.submitUserInput("/mailbox accept mail_1");

        assertTrue(core.requests.isEmpty());
        assertEquals(Map.of("action", "accept", "mailId", "mail_1"), slash.arguments);
        MessageStartEvent start = assertInstanceOf(MessageStartEvent.class, events.published.getFirst());
        assertEquals("ses_1", start.sessionId());
        MessageDeltaEvent delta = assertInstanceOf(MessageDeltaEvent.class, events.published.get(1));
        assertEquals("ses_1", delta.sessionId());
        assertEquals("mailId: mail_1", delta.delta());
        assertInstanceOf(MessageEndEvent.class, events.published.get(2));
    }

    @Test
    void agentInterruptSlashCommandShorthandMapsAgentIdArgument() {
        RecordingCore core = new RecordingCore();
        RecordingEventBus events = new RecordingEventBus();
        RecordingSlashCommandHandler slash = new RecordingSlashCommandHandler("中断请求已发送: agent_1");
        RuntimeTuiSubmitHandler handler = new RuntimeTuiSubmitHandler(
            "ses_1",
            core,
            events,
            Runnable::run,
            List.of(new SlashCommand("agent", "管理 agent", List.of(), slash))
        );

        handler.submitUserInput("/agent interrupt agent_1");

        assertTrue(core.requests.isEmpty());
        assertEquals(Map.of("action", "interrupt", "agentId", "agent_1"), slash.arguments);
        MessageDeltaEvent delta = assertInstanceOf(MessageDeltaEvent.class, events.published.get(1));
        assertEquals("中断请求已发送: agent_1", delta.delta());
    }

    @Test
    void regularInputStillStartsTurnWhenSlashCommandsAreRegistered() {
        RecordingCore core = new RecordingCore();
        RecordingEventBus events = new RecordingEventBus();
        RuntimeTuiSubmitHandler handler = new RuntimeTuiSubmitHandler(
            "ses_1",
            core,
            events,
            Runnable::run,
            List.of(new SlashCommand("mailbox", "读取 mailbox", List.of(), new RecordingSlashCommandHandler("")))
        );

        handler.submitUserInput("hello");

        assertEquals("hello", core.requests.getFirst().userInput());
    }

    private static final class RecordingCore implements AgentCorePort {
        private final List<TurnRequest> requests = new ArrayList<>();

        @Override
        public TurnState execute(TurnRequest request) {
            requests.add(request);
            return null;
        }
    }

    private static final class BlockingCore implements AgentCorePort {
        private final List<TurnRequest> requests = new ArrayList<>();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public TurnState execute(TurnRequest request) {
            requests.add(request);
            started.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private static final class RecordingSlashCommandHandler implements SlashCommandHandler {
        private final String output;
        private Map<String, String> arguments;

        private RecordingSlashCommandHandler(String output) {
            this.output = output;
        }

        @Override
        public void handle(Map<String, String> arguments) {
            this.arguments = arguments;
        }

        public String lastOutput() {
            return output;
        }
    }

    private static final class RecordingEventBus implements EventBus {
        private final List<AgentEvent> published = new ArrayList<>();

        @Override
        public void publish(AgentEvent event) {
            published.add(event);
        }

        @Override
        public EventSubscription subscribe(EventFilter filter, EventConsumer consumer) {
            return () -> {
            };
        }
    }
}
