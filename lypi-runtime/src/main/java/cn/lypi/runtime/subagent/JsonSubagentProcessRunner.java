package cn.lypi.runtime.subagent;

import cn.lypi.contracts.subagent.HeadlessSubagentInput;
import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class JsonSubagentProcessRunner implements SubagentProcessRunner {
    private final List<String> command;
    private final ObjectMapper objectMapper;

    public JsonSubagentProcessRunner(List<String> command) {
        this.command = command == null ? List.of() : List.copyOf(command);
        objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        objectMapper.registerModule(new Jdk8Module());
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Override
    public SubagentProcessHandle start(HeadlessSubagentInput input) {
        if (command.isEmpty()) {
            throw new IllegalStateException("Subagent command is not configured");
        }
        try {
            Process process = new ProcessBuilder(command).start();
            objectMapper.writeValue(process.getOutputStream(), input);
            process.getOutputStream().close();
            CompletableFuture<HeadlessSubagentOutput> completion = CompletableFuture.supplyAsync(() -> readOutput(process, input));
            return new ProcessHandle(process, completion);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start subagent process", e);
        }
    }

    private HeadlessSubagentOutput readOutput(Process process, HeadlessSubagentInput input) {
        try {
            HeadlessSubagentOutput output = objectMapper.readValue(process.getInputStream(), HeadlessSubagentOutput.class);
            process.waitFor();
            return output;
        } catch (IOException e) {
            return failure(input.childSessionId(), "Failed to read subagent output: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new HeadlessSubagentOutput(
                input.childSessionId(),
                SubagentRunStatus.INTERRUPTED,
                "已中断",
                Optional.empty(),
                Optional.of("Interrupted while waiting for subagent process")
            );
        }
    }

    private HeadlessSubagentOutput failure(String childSessionId, String message) {
        return new HeadlessSubagentOutput(
            childSessionId,
            SubagentRunStatus.FAILED,
            "",
            Optional.empty(),
            Optional.ofNullable(message)
        );
    }

    private record ProcessHandle(Process process, CompletableFuture<HeadlessSubagentOutput> completion)
        implements SubagentProcessHandle {
        @Override
        public void interrupt() {
            process.destroy();
        }
    }
}
