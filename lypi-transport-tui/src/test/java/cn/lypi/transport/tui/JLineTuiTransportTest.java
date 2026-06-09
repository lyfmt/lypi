package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventConsumer;
import cn.lypi.contracts.event.EventEnvelope;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.event.ErrorEvent;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.tui.SessionRuntimeState;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
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

        @Override
        public void publish(AgentEvent event) {
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
}
