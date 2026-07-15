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
import java.util.concurrent.atomic.AtomicLong;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

public final class TuiInteractionPtyProbe {
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
            for (int index = 1; index <= 30; index++) {
                events.emit(message("msg_" + index, "block_" + index, "history-" + index, true));
            }
            transport.flushPendingFrameForTest();
            Files.writeString(controlDirectory.resolve("ready"), "ready");

            Thread emitter = Thread.ofVirtual().start(() -> {
                try {
                    await(controlDirectory.resolve("emit-first"));
                    events.emit(message("msg_stream", "block_stream", "stream-first", false));
                    await(controlDirectory.resolve("emit-final"));
                    events.emit(message("msg_stream", "block_stream", "-final", true));
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            transport.runUntilExit();
            emitter.join();
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
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(signal)) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("timed out waiting for " + signal.getFileName());
            }
            Thread.sleep(10L);
        }
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
