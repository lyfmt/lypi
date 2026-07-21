package cn.lypi.transport.tui;

import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventConsumer;
import cn.lypi.contracts.event.EventEnvelope;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.event.MessageDeltaEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

public final class TuiInteractionPtyProbe {
    private static final int INITIAL_HISTORY_BLOCKS = 42;

    private TuiInteractionPtyProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("expected control directory path");
        }
        Path controlDirectory = Path.of(args[0]);
        ProbeEventBus events = new ProbeEventBus();
        Terminal terminal = TerminalBuilder.builder().system(true).build();
        TerminalIo io = new JLineTerminalIo(terminal);
        try (JLineTuiTransport transport = JLineTuiTransport.open(
            TestRuntimeStates.basic("ses_1"),
            events,
            io,
            new JLineTerminalInputSource(terminal),
            new NoopSubmitHandler(),
            terminal.getWidth(),
            terminal.getHeight()
        )) {
            for (int index = 1; index <= INITIAL_HISTORY_BLOCKS; index++) {
                String suffix = "%03d".formatted(index);
                events.emit(message(
                    "msg_" + suffix,
                    "block_" + suffix,
                    "history-sentinel-" + suffix,
                    true
                ));
            }
            transport.flushPendingFrameForTest();
            Files.writeString(controlDirectory.resolve("ready"), "ready");

            AtomicReference<Throwable> controllerFailure = new AtomicReference<>();
            Thread controller = Thread.ofVirtual().start(() -> {
                try {
                    await(controlDirectory.resolve("emit-intermediate"));
                    events.emit(message("msg_stream", "block_stream", "stream-intermediate", false));
                    transport.flushPendingFrameForTest();
                    signal(controlDirectory.resolve("intermediate-emitted"));

                    await(controlDirectory.resolve("resize-small"));
                    awaitSize(terminal, 60, 9);
                    transport.flushPendingFrameForTest();
                    signal(controlDirectory.resolve("resize-small-processed"));

                    await(controlDirectory.resolve("resize-large"));
                    awaitSize(terminal, 80, 12);
                    transport.flushPendingFrameForTest();
                    signal(controlDirectory.resolve("resize-large-processed"));

                    await(controlDirectory.resolve("emit-final"));
                    events.emit(message("msg_stream", "block_stream", "-final", true));
                    transport.flushPendingFrameForTest();
                    signal(controlDirectory.resolve("final-emitted"));
                } catch (Throwable failure) {
                    controllerFailure.set(failure);
                }
            });
            transport.runUntilExit();
            controller.join();
            if (controllerFailure.get() != null) {
                throw new IllegalStateException("PTY controller failed", controllerFailure.get());
            }
        }
    }

    private static MessageDeltaEvent message(String messageId, String blockId, String delta, boolean isFinal) {
        return new MessageDeltaEvent(
            "ses_1",
            messageId,
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            blockId,
            ContentBlockKind.TEXT,
            delta,
            isFinal,
            Map.of(),
            Instant.now()
        );
    }

    private static void await(Path signal) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(signal)) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("timed out waiting for " + signal.getFileName());
            }
            Thread.sleep(10L);
        }
    }

    private static void awaitSize(Terminal terminal, int width, int height) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (terminal.getWidth() != width || terminal.getHeight() != height) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException(
                    "timed out waiting for terminal size " + width + "x" + height
                        + ", current=" + terminal.getWidth() + "x" + terminal.getHeight()
                );
            }
            Thread.sleep(10L);
        }
    }

    private static void signal(Path path) throws Exception {
        Files.writeString(path, "done");
    }

    private static final class ProbeEventBus implements EventBus {
        private final AtomicLong sequence = new AtomicLong();
        private volatile EventConsumer consumer;

        @Override
        public void publish(AgentEvent event) {
            emit(event);
        }

        @Override
        public EventSubscription subscribe(EventFilter filter, EventConsumer consumer) {
            this.consumer = consumer;
            return () -> this.consumer = null;
        }

        private void emit(AgentEvent event) {
            EventConsumer current = consumer;
            if (current != null) {
                long next = sequence.incrementAndGet();
                current.accept(new EventEnvelope("evt_" + next, "ses_1", next, event));
            }
        }
    }

    private static final class NoopSubmitHandler implements TuiSubmitHandler {
        @Override
        public void submitUserInput(String input) {
        }

        @Override
        public void requestInterrupt(String reason) {
        }
    }
}
