package cn.lypi.ai.provider.anthropic;

import cn.lypi.ai.provider.ProviderEventStream;
import cn.lypi.ai.provider.ProviderRawEvent;
import cn.lypi.ai.provider.ProviderRequest;
import cn.lypi.ai.provider.ProviderRetryCoordinator;
import cn.lypi.ai.provider.ProviderRetryPolicy;
import cn.lypi.ai.provider.ProviderTransport;
import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantError;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.AssistantStart;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.AssistantStreamResult;
import cn.lypi.contracts.model.ProviderRetryNotice;
import cn.lypi.contracts.model.TokenUsage;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

public final class AnthropicAssistantEventStream implements AssistantEventStream {
    private final ProviderTransport transport;
    private final ProviderRequest request;
    private final AbortSignal signal;
    private final ProviderRetryCoordinator retryCoordinator;
    private final AnthropicMessagesStreamNormalizer normalizer;
    private final Deque<AssistantStreamEvent> pendingEvents = new ArrayDeque<>();
    private final List<AssistantStreamEvent> emittedEvents = new ArrayList<>();
    private ProviderEventStream currentProviderStream;
    private Iterator<ProviderRawEvent> currentRawIterator;
    private int retryIndex;
    private boolean iteratorCreated;
    private boolean closed;
    private boolean completed;
    private boolean aborted;
    private boolean outputStarted;
    private boolean exhausted;
    private boolean retryNoticeAwaitingConsumption;
    private AssistantError error;
    private RuntimeException failure;
    private Duration pendingRetryDelay = Duration.ZERO;
    private TokenUsage usage;
    private String stopReason;
    private String messageId = "";

    public AnthropicAssistantEventStream(
        ProviderTransport transport,
        ProviderRequest request,
        AbortSignal signal,
        int maxRetries
    ) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.request = Objects.requireNonNull(request, "request");
        this.signal = Objects.requireNonNull(signal, "signal");
        this.retryCoordinator = ProviderRetryCoordinator.defaultSleep(
            "anthropic",
            ProviderRetryPolicy.defaults(maxRetries)
        );
        this.normalizer = new AnthropicMessagesStreamNormalizer();
    }

    @Override
    public Iterator<AssistantStreamEvent> iterator() {
        if (iteratorCreated) {
            throw new IllegalStateException("Assistant event stream is single-use.");
        }
        iteratorCreated = true;
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return AnthropicAssistantEventStream.this.hasNext();
            }

            @Override
            public AssistantStreamEvent next() {
                return AnthropicAssistantEventStream.this.next();
            }
        };
    }

    @Override
    public AssistantStreamResult result() {
        return new AssistantStreamResult(
            messageId,
            emittedEvents,
            Optional.ofNullable(usage),
            Optional.ofNullable(stopReason),
            completed,
            aborted,
            Optional.ofNullable(error),
            Optional.empty()
        );
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeCurrentProviderStream();
        if (signal.aborted()) {
            aborted = true;
            return;
        }
        if (!completed && failure == null && error == null) {
            aborted = true;
        }
    }

    private boolean hasNext() {
        if (!pendingEvents.isEmpty()) {
            return true;
        }
        if (retryNoticeAwaitingConsumption) {
            return false;
        }
        if (failure != null) {
            throw failure;
        }
        if (completed || closed || exhausted) {
            return false;
        }
        if (signal.aborted()) {
            aborted = true;
            closeCurrentProviderStream();
            return false;
        }
        sleepBeforeRetry();
        while (true) {
            if (!pendingEvents.isEmpty()) {
                return true;
            }
            if (currentRawIterator == null && !openAttempt()) {
                return !pendingEvents.isEmpty();
            }
            if (!pendingEvents.isEmpty()) {
                return true;
            }
            if (currentRawIterator == null) {
                return false;
            }
            try {
                if (!currentRawIterator.hasNext()) {
                    closeCurrentProviderStream();
                    handleAttemptFailure(new IllegalStateException("Provider stream completed without AssistantDone."));
                    if (failure != null) {
                        throw failure;
                    }
                    continue;
                }
                ProviderRawEvent rawEvent = currentRawIterator.next();
                normalizer.normalize(rawEvent.data()).forEach(this::enqueue);
                if (!pendingEvents.isEmpty()) {
                    return true;
                }
            } catch (RuntimeException exception) {
                handleAttemptFailure(exception);
                if (failure != null) {
                    throw failure;
                }
            }
        }
    }

    private AssistantStreamEvent next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        AssistantStreamEvent event = pendingEvents.removeFirst();
        emittedEvents.add(event);
        if (event instanceof ProviderRetryNotice) {
            retryNoticeAwaitingConsumption = false;
        } else {
            outputStarted = true;
        }
        applyResult(event);
        return event;
    }

    private boolean openAttempt() {
        if (closed || signal.aborted()) {
            aborted = signal.aborted() || aborted;
            return false;
        }
        try {
            currentProviderStream = transport.stream(request, signal);
            currentRawIterator = currentProviderStream.iterator();
            return true;
        } catch (RuntimeException exception) {
            handleAttemptFailure(exception);
            if (failure != null) {
                throw failure;
            }
            return !pendingEvents.isEmpty();
        }
    }

    private void handleAttemptFailure(RuntimeException exception) {
        closeCurrentProviderStream();
        if (signal.aborted()) {
            aborted = true;
            return;
        }
        int nextRetryIndex = retryIndex + 1;
        Optional<ProviderRetryNotice> retryNotice = retryCoordinator.planRetry(
            exception,
            signal,
            outputStarted,
            nextRetryIndex
        );
        if (retryNotice.isPresent()) {
            retryIndex = nextRetryIndex;
            pendingRetryDelay = retryNotice.get().delay();
            pendingEvents.add(retryNotice.get());
            retryNoticeAwaitingConsumption = true;
            return;
        }
        exhausted = true;
        error = new AssistantError("provider.request_failed", exception.getMessage());
        if (outputStarted) {
            failure = exception;
            return;
        }
        pendingEvents.add(error);
    }

    private void sleepBeforeRetry() {
        if (pendingRetryDelay.isZero() || pendingRetryDelay.isNegative()) {
            return;
        }
        retryCoordinator.sleep(pendingRetryDelay, signal);
        pendingRetryDelay = Duration.ZERO;
    }

    private void enqueue(AssistantStreamEvent event) {
        pendingEvents.add(event);
    }

    private void applyResult(AssistantStreamEvent event) {
        switch (event) {
            case AssistantStart start -> messageId = start.messageId();
            case AssistantDone done -> {
                usage = done.usage().orElse(null);
                stopReason = done.stopReason().orElse(null);
                completed = true;
                closeCurrentProviderStream();
            }
            case AssistantError assistantError -> {
                error = assistantError;
                closeCurrentProviderStream();
            }
            default -> {
            }
        }
    }

    private void closeCurrentProviderStream() {
        if (currentProviderStream == null) {
            return;
        }
        currentProviderStream.close();
        currentProviderStream = null;
        currentRawIterator = null;
    }
}
