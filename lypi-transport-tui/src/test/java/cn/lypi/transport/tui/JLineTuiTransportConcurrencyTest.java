package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.ErrorEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventConsumer;
import cn.lypi.contracts.event.EventEnvelope;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.event.ToolProgressEvent;
import cn.lypi.contracts.event.ToolStartEvent;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class JLineTuiTransportConcurrencyTest {
    @Test
    void publishingProgressDoesNotEnterBlockingFrameSink() throws Exception {
        CountDownLatch renderEntered = new CountDownLatch(1);
        CountDownLatch releaseRender = new CountDownLatch(1);
        RecordingEventBus events = new RecordingEventBus();
        JLineTuiTransport transport = JLineTuiTransport.withRenderer(lines -> {
            renderEntered.countDown();
            try {
                releaseRender.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }, 40, 5);
        transport.attach(events, TestRuntimeStates.basic("ses_1"));
        events.emit(new ToolStartEvent("ses_1", "toolu_1", "bash", Instant.parse("2026-06-10T00:00:00Z")));
        Thread publisher = new Thread(() -> events.emit(new ToolProgressEvent(
            "ses_1",
            "toolu_1",
            ToolProgress.output("stdout", "progress\n"),
            Instant.parse("2026-06-10T00:00:00Z")
        )));

        publisher.start();
        publisher.join(100L);

        assertFalse(publisher.isAlive());
        assertEquals(1L, renderEntered.getCount());

        Thread uiFlush = new Thread(transport::flushPendingFrameForTest);
        uiFlush.start();
        assertTrue(renderEntered.await(1, TimeUnit.SECONDS));
        releaseRender.countDown();
        uiFlush.join(1_000L);
        assertFalse(uiFlush.isAlive());
    }

    @Test
    void eventInputAndResizeRenderPathsShareUiMonitor() {
        StringBuilder order = new StringBuilder();
        JLineTuiTransport transport = new JLineTuiTransport(() -> order.append("render;"));

        transport.renderUnderUiLock();
        transport.runInputMutationForTest(() -> order.append("input;"));
        transport.runResizeMutationForTest(() -> order.append("resize;"));

        assertEquals("render;input;resize;", order.toString());
        assertEquals(3, transport.uiLockEntryCountForTest());
    }

    @Test
    void inputLoopCanRunThroughTransportUiMonitor() {
        StringBuilder order = new StringBuilder();
        JLineTuiTransport transport = new JLineTuiTransport(() -> {
        });

        transport.runInputMutationForTest(() -> order.append("key;"));

        assertEquals("key;", order.toString());
        assertEquals(1, transport.uiLockEntryCountForTest());
    }

    @Test
    void drainsTerminalInputThroughTransportUiMonitor() throws Exception {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        JLineTuiTransport transport = JLineTuiTransport.withInput(
            ignored -> {
            },
            20,
            4,
            new QueueInputSource("hello", "\r"),
            submit
        );

        transport.drainInputForTest();

        assertEquals(List.of("hello"), submit.submitted);
        assertEquals(2, transport.uiLockEntryCountForTest());
    }

    @Test
    void submitDoesNotHoldUiLockWhileWaitingForMoreInput() throws Exception {
        CountDownLatch inputWaitStarted = new CountDownLatch(1);
        CountDownLatch releaseInputRead = new CountDownLatch(1);
        CountDownLatch eventProcessed = new CountDownLatch(1);
        BlockingAfterChunksInputSource input = new BlockingAfterChunksInputSource(
            inputWaitStarted,
            releaseInputRead,
            "p",
            "i",
            "n",
            "g",
            "\r"
        );
        JLineTuiTransport[] holder = new JLineTuiTransport[1];
        JLineTuiTransport transport = JLineTuiTransport.withInput(
            ignored -> {
            },
            20,
            4,
            input,
            new TuiSubmitHandler() {
                @Override
                public void submitUserInput(String input) {
                    new Thread(() -> {
                        holder[0].reduceAndRequestRenderUnderUiLock(new ErrorEvent(
                            "ses_1",
                            "err_1",
                            "boom",
                            Instant.parse("2026-06-10T00:00:00Z")
                        ));
                        eventProcessed.countDown();
                    }).start();
                }

                @Override
                public void requestInterrupt(String reason) {
                }
            }
        );
        holder[0] = transport;
        Thread drain = new Thread(() -> {
            try {
                transport.drainInputForTest();
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });

        drain.start();
        assertTrue(inputWaitStarted.await(1, TimeUnit.SECONDS));
        try {
            assertTrue(eventProcessed.await(1, TimeUnit.SECONDS));
        } finally {
            releaseInputRead.countDown();
            drain.join(1_000L);
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

    private static final class RecordingEventBus implements EventBus {
        private EventConsumer consumer;

        @Override
        public void publish(AgentEvent event) {
        }

        @Override
        public EventSubscription subscribe(EventFilter filter, EventConsumer consumer) {
            this.consumer = consumer;
            return () -> {
            };
        }

        void emit(AgentEvent event) {
            consumer.accept(new EventEnvelope("evt_1", "ses_1", 1, event));
        }
    }

    private static final class BlockingAfterChunksInputSource implements TerminalInputSource {
        private final CountDownLatch waitStarted;
        private final CountDownLatch release;
        private final ArrayDeque<String> chunks;

        private BlockingAfterChunksInputSource(CountDownLatch waitStarted, CountDownLatch release, String... chunks) {
            this.waitStarted = waitStarted;
            this.release = release;
            this.chunks = new ArrayDeque<>(List.of(chunks));
        }

        @Override
        public Optional<String> read() throws IOException {
            String next = chunks.pollFirst();
            if (next != null) {
                return Optional.of(next);
            }
            waitStarted.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting", exception);
            }
            return Optional.empty();
        }
    }

    private static final class RecordingSubmitHandler implements TuiSubmitHandler {
        private final List<String> submitted = new ArrayList<>();

        @Override
        public void submitUserInput(String input) {
            submitted.add(input);
        }

        @Override
        public void requestInterrupt(String reason) {
        }
    }
}
