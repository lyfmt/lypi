package cn.lypi.ai.provider.openai;

import cn.lypi.ai.provider.ProviderEventStream;
import cn.lypi.ai.provider.ProviderFallbackDecider;
import cn.lypi.ai.provider.ProviderRawEvent;
import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantError;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.AssistantStart;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.AssistantStreamResult;
import cn.lypi.contracts.model.TokenUsage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

public final class OpenAiAssistantEventStream implements AssistantEventStream {
    private final List<OpenAiStreamAttempt> attempts;
    private final AbortSignal signal;
    private final ProviderFallbackDecider fallbackDecider;
    private final int maxRetries;
    private final Deque<AssistantStreamEvent> pendingEvents = new ArrayDeque<>();
    private final List<AssistantStreamEvent> emittedEvents = new ArrayList<>();
    private ProviderEventStream currentProviderStream;
    private Iterator<ProviderRawEvent> currentRawIterator;
    private int attemptIndex;
    private int retryIndex;
    private boolean iteratorCreated;
    private boolean closed;
    private boolean completed;
    private boolean aborted;
    private boolean outputStarted;
    private AssistantError error;
    private RuntimeException failure;
    private TokenUsage usage;
    private String stopReason;
    private String messageId = "";

    public OpenAiAssistantEventStream(
        List<OpenAiStreamAttempt> attempts,
        AbortSignal signal,
        ProviderFallbackDecider fallbackDecider,
        int maxRetries
    ) {
        this.attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts"));
        this.signal = Objects.requireNonNull(signal, "signal");
        this.fallbackDecider = Objects.requireNonNull(fallbackDecider, "fallbackDecider");
        this.maxRetries = Math.max(0, maxRetries);
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
                return OpenAiAssistantEventStream.this.hasNext();
            }

            @Override
            public AssistantStreamEvent next() {
                return OpenAiAssistantEventStream.this.next();
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
            Optional.ofNullable(error)
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
            closed = true;
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
        if (failure != null) {
            throw failure;
        }
        if (completed || closed) {
            return false;
        }
        if (signal.aborted()) {
            aborted = true;
            closeCurrentProviderStream();
            return false;
        }
        while (true) {
            if (currentRawIterator == null && !openNextAttempt()) {
                return false;
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
                List<AssistantStreamEvent> normalized = currentAttempt().normalize(rawEvent.data());
                normalized.forEach(this::enqueue);
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
        outputStarted = true;
        applyResult(event);
        return event;
    }

    private boolean openNextAttempt() {
        while (attemptIndex < attempts.size()) {
            if (signal.aborted() || closed) {
                aborted = signal.aborted() || aborted;
                return false;
            }
            OpenAiStreamAttempt attempt = attempts.get(attemptIndex);
            try {
                currentProviderStream = attempt.transport().stream(attempt.request(), signal);
                currentRawIterator = currentProviderStream.iterator();
                return true;
            } catch (RuntimeException exception) {
                handleAttemptFailure(exception);
                if (failure != null) {
                    throw failure;
                }
                if (signal.aborted() || closed) {
                    return false;
                }
            }
        }
        if (error == null) {
            error = new AssistantError("provider.request_failed", "Provider request failed.");
            pendingEvents.add(error);
        }
        return !pendingEvents.isEmpty();
    }

    private OpenAiStreamAttempt currentAttempt() {
        return attempts.get(attemptIndex);
    }

    private void handleAttemptFailure(RuntimeException exception) {
        closeCurrentProviderStream();
        if (signal.aborted()) {
            aborted = true;
            return;
        }
        if (retryIndex < maxRetries && fallbackDecider.shouldFallback(exception, outputStarted)) {
            retryIndex++;
            return;
        }
        retryIndex = 0;
        attemptIndex++;
        if (fallbackDecider.shouldFallback(exception, outputStarted) && !outputStarted && attemptIndex < attempts.size()) {
            return;
        }
        error = new AssistantError("provider.request_failed", exception.getMessage());
        if (outputStarted) {
            failure = exception;
            return;
        }
        pendingEvents.add(error);
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
