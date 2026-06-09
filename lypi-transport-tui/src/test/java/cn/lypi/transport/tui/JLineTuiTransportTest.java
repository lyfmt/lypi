package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.agent.TurnState;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventConsumer;
import cn.lypi.contracts.event.EventEnvelope;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.event.ErrorEvent;
import cn.lypi.contracts.event.MessageDeltaEvent;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.runtime.AgentCorePort;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.contracts.tui.SlashCommand;
import cn.lypi.contracts.tui.SlashCommandHandler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JLineTuiTransportTest {
    @Test
    void attachSubscribesToSessionEventsAndRendersUnderUiLock() {
        RecordingScreen screen = new RecordingScreen();
        RecordingEventBus events = new RecordingEventBus();
        JLineTuiTransport transport = new JLineTuiTransport(screen::render);

        transport.attach(events, runtimeState());
        events.emit(new ErrorEvent("ses_1", "err_1", "boom", Instant.parse("2026-06-09T00:00:00Z")));

        assertTrue(events.subscribed);
        assertEquals(1, screen.renderCount);
        assertTrue(transport.isUiLockedForTest());
    }

    @Test
    void attachIgnoresEventsFromOtherSessions() {
        RecordingScreen screen = new RecordingScreen();
        RecordingEventBus events = new RecordingEventBus();
        JLineTuiTransport transport = new JLineTuiTransport(screen::render);

        transport.attach(events, runtimeState());
        events.emit(new ErrorEvent("other_session", "err_1", "boom", Instant.parse("2026-06-09T00:00:00Z")));

        assertEquals(0, screen.renderCount);
    }

    @Test
    void openAssemblesTerminalSessionRendererInputAndEventSubscription() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        RecordingEventBus events = new RecordingEventBus();

        JLineTuiTransport transport = JLineTuiTransport.open(
            runtimeState(),
            events,
            io,
            () -> Optional.empty(),
            new RecordingSubmitHandler(),
            40,
            4
        );

        events.emit(new ErrorEvent("ses_1", "err_1", "boom", Instant.parse("2026-06-09T00:00:00Z")));

        assertTrue(io.rawModeEntered);
        assertTrue(io.output.toString().contains(TerminalSession.ENTER_ALTERNATE_SCREEN));
        assertTrue(io.output.toString().contains("\033[?2026h\033[H\033[Jerror: boom"));

        transport.close();

        assertTrue(io.rawModeRestored);
        assertTrue(io.output.toString().contains(TerminalSession.EXIT_ALTERNATE_SCREEN));
    }

    @Test
    void openRendersInitialFrameFromRuntimeState() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        RecordingEventBus events = new RecordingEventBus();

        JLineTuiTransport transport = JLineTuiTransport.open(
            runtimeState(),
            events,
            io,
            () -> Optional.empty(),
            new RecordingSubmitHandler(),
            120,
            4
        );

        String frame = io.output.toString();
        assertTrue(frame.contains("\033[?2026h\033[H\033[J"));
        assertTrue(frame.contains("ses_1 gpt-5.4:thinking=high execute default_execute cwd:. leaf:leaf_1 ctx:0/200000tok"));
        assertTrue(frame.contains("> "));

        transport.close();
    }

    @Test
    void openClosesTerminalSessionWhenInitialFrameRenderFails() {
        FailingInitialFrameTerminalIo io = new FailingInitialFrameTerminalIo();
        RecordingEventBus events = new RecordingEventBus();

        assertThrows(UncheckedIOException.class, () -> JLineTuiTransport.open(
            runtimeState(),
            events,
            io,
            () -> Optional.empty(),
            new RecordingSubmitHandler(),
            40,
            4
        ));

        assertTrue(io.rawModeRestored);
        assertTrue(io.output.toString().contains(TerminalSession.EXIT_ALTERNATE_SCREEN));
    }

    @Test
    void openResizeCallbackReadsCurrentTerminalDimensions() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        RecordingEventBus events = new RecordingEventBus();

        JLineTuiTransport transport = JLineTuiTransport.open(
            runtimeState(),
            events,
            io,
            () -> Optional.empty(),
            new RecordingSubmitHandler(),
            40,
            4
        );
        io.output.setLength(0);

        io.width = 12;
        io.height = 6;
        io.resizeCallback.run();

        String frame = io.output.toString();
        String rendered = frame.substring(frame.indexOf("\033[H\033[J") + "\033[H\033[J".length(), frame.indexOf("\033[?2026l"));
        assertEquals(6, rendered.split("\n", -1).length);

        transport.close();
    }

    @Test
    void openWithSlashCommandsExecutesSlashInputWithoutStartingTurn() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        RecordingEventBus events = new RecordingEventBus();
        RecordingCore core = new RecordingCore();
        RecordingSlashCommandHandler slash = new RecordingSlashCommandHandler("mailId: mail_1");

        JLineTuiTransport transport = JLineTuiTransport.open(
            runtimeState(),
            core,
            events,
            io,
            new QueueInputSource("/mailbox accept mail_1", "\r"),
            Runnable::run,
            List.of(new SlashCommand("mailbox", "读取 mailbox", List.of(), slash)),
            40,
            4
        );

        transport.drainInputForTest();

        assertTrue(core.requests.isEmpty());
        assertEquals(Map.of("action", "accept", "mailId", "mail_1"), slash.arguments);
        MessageDeltaEvent delta = assertInstanceOf(MessageDeltaEvent.class, events.published.get(1));
        assertEquals("mailId: mail_1", delta.delta());

        transport.close();
    }

    @Test
    void openFallsBackWhenTerminalSizeUnavailable() {
        RecordingTerminalIo io = new RecordingTerminalIo();
        RecordingEventBus events = new RecordingEventBus();

        assertDoesNotThrow(() -> {
            JLineTuiTransport transport = JLineTuiTransport.open(
                runtimeState(),
                events,
                io,
                () -> Optional.empty(),
                new RecordingSubmitHandler(),
                0,
                0
            );
            transport.close();
        });
    }

    @Test
    void resizeFallsBackWhenTerminalSizeUnavailable() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        RecordingEventBus events = new RecordingEventBus();
        JLineTuiTransport transport = JLineTuiTransport.open(
            runtimeState(),
            events,
            io,
            () -> Optional.empty(),
            new RecordingSubmitHandler(),
            40,
            4
        );

        io.width = 0;
        io.height = 0;

        assertDoesNotThrow(() -> io.resizeCallback.run());
        transport.close();
    }

    @Test
    void withInputFallsBackWhenTerminalSizeUnavailable() {
        assertDoesNotThrow(() -> JLineTuiTransport.withInput(
            ignored -> {
            },
            0,
            0,
            () -> Optional.of("draft"),
            new RecordingSubmitHandler()
        ).drainInputForTest());
    }

    @Test
    void layoutStillRejectsInvalidDimensions() {
        assertThrows(IllegalArgumentException.class, () -> new TuiLayout(0, 0));
    }

    private SessionRuntimeState runtimeState() {
        return new SessionRuntimeState(
            "ses_1",
            Path.of("."),
            "leaf_1",
            new ModelSelection("openai", "gpt-5.4", ThinkingLevel.HIGH),
            ThinkingLevel.HIGH,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE,
            new ContextBudget(0, 200000, 180000, 12000, 6000, 0, 0, BigDecimal.ZERO),
            false,
            false,
            false,
            false
        );
    }

    @Test
    void nameIdentifiesTuiAdapter() {
        assertEquals("tui", new JLineTuiTransport(() -> {
        }).name());
    }

    private static final class RecordingScreen {
        private int renderCount;

        void render() {
            renderCount++;
        }
    }

    private static final class RecordingEventBus implements EventBus {
        private EventConsumer consumer;
        private boolean subscribed;
        private final List<AgentEvent> published = new ArrayList<>();

        @Override
        public void publish(AgentEvent event) {
            published.add(event);
        }

        @Override
        public EventSubscription subscribe(EventFilter filter, EventConsumer consumer) {
            assertEquals(Optional.of("ses_1"), filter.sessionId());
            assertTrue(filter.eventType().isEmpty());
            this.consumer = consumer;
            this.filter = filter;
            subscribed = true;
            return () -> {
            };
        }

        void emit(AgentEvent event) {
            if (filter.sessionId().isPresent() && !filter.sessionId().orElseThrow().equals(event.sessionId())) {
                return;
            }
            consumer.accept(new EventEnvelope("evt_1", "ses_1", 1, event));
        }

        private EventFilter filter;
    }

    private static final class RecordingSubmitHandler implements TuiSubmitHandler {
        @Override
        public void submitUserInput(String input) {
        }

        @Override
        public void requestInterrupt(String reason) {
        }
    }

    private static final class RecordingCore implements AgentCorePort {
        private final List<TurnRequest> requests = new ArrayList<>();

        @Override
        public TurnState execute(TurnRequest request) {
            requests.add(request);
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

        @Override
        public String lastOutput() {
            return output;
        }
    }

    private static final class QueueInputSource implements TerminalInputSource {
        private final ArrayDeque<String> chunks;

        private QueueInputSource(String... chunks) {
            this.chunks = new ArrayDeque<>(List.of(chunks));
        }

        @Override
        public Optional<String> read() {
            return Optional.ofNullable(chunks.pollFirst());
        }
    }

    private static final class RecordingTerminalIo implements TerminalIo {
        private final StringBuilder output = new StringBuilder();
        private boolean rawModeEntered;
        private boolean rawModeRestored;
        private int width = 40;
        private int height = 4;
        private Runnable resizeCallback = () -> {
        };

        @Override
        public AutoCloseable enterRawMode() {
            rawModeEntered = true;
            return () -> rawModeRestored = true;
        }

        @Override
        public void write(String value) {
            output.append(value);
        }

        @Override
        public void flush() {
        }

        @Override
        public AutoCloseable onResize(Runnable callback) throws IOException {
            resizeCallback = callback;
            return () -> {
            };
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }
    }

    private static final class FailingInitialFrameTerminalIo implements TerminalIo {
        private final StringBuilder output = new StringBuilder();
        private boolean rawModeRestored;
        private int writesUntilFailure = 5;

        @Override
        public AutoCloseable enterRawMode() {
            return () -> rawModeRestored = true;
        }

        @Override
        public void write(String value) throws IOException {
            if (writesUntilFailure == 0) {
                writesUntilFailure--;
                throw new IOException("initial frame failed");
            }
            writesUntilFailure--;
            output.append(value);
        }

        @Override
        public void flush() {
        }

        @Override
        public AutoCloseable onResize(Runnable callback) {
            return () -> {
            };
        }

        @Override
        public int width() {
            return 40;
        }

        @Override
        public int height() {
            return 4;
        }
    }
}
