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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class JsonSubagentProcessRunner implements SubagentProcessRunner {
    private final List<String> command;
    private final ObjectMapper objectMapper;

    public JsonSubagentProcessRunner(List<String> command) {
        this.command = command == null ? List.of() : List.copyOf(command);
        objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
        objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        objectMapper.registerModule(new Jdk8Module());
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Override
    public SubagentProcessHandle start(HeadlessSubagentInput input) {
        if (command.isEmpty()) {
            throw new IllegalStateException(subagentCommandMissingMessage());
        }
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(input.cwd().toAbsolutePath().normalize().toFile());
            Process process = processBuilder.start();
            objectMapper.writeValue(process.getOutputStream(), input);
            process.getOutputStream().close();
            AtomicBoolean interrupted = new AtomicBoolean(false);
            CompletableFuture<HeadlessSubagentOutput> completion = CompletableFuture.supplyAsync(() ->
                readOutput(process, input, interrupted));
            return new ProcessHandle(process, input, completion, interrupted);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start subagent process", e);
        }
    }

    private HeadlessSubagentOutput readOutput(Process process, HeadlessSubagentInput input, AtomicBoolean interrupted) {
        CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
        CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> readAll(process.getErrorStream()));
        try {
            boolean exited = process.waitFor(Math.max(1, input.timeoutSeconds()), TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                return new HeadlessSubagentOutput(
                    input.taskName(),
                    input.agentId(),
                    input.childSessionId(),
                    input.runId(),
                    SubagentRunStatus.TIMED_OUT,
                    "",
                    Optional.empty(),
                    Optional.of("Subagent process timed out after " + input.timeoutSeconds() + " seconds")
                );
            }
            if (interrupted.get()) {
                return interrupted(input);
            }
            byte[] output = stdout.get(1, TimeUnit.SECONDS);
            String error = new String(stderr.get(1, TimeUnit.SECONDS), StandardCharsets.UTF_8).trim();
            if (output.length == 0) {
                return failure(input, nonZeroMessage(process.exitValue(), error));
            }
            HeadlessSubagentOutput parsed = objectMapper.readValue(output, HeadlessSubagentOutput.class);
            if (process.exitValue() != 0 && parsed.status() == SubagentRunStatus.SUCCEEDED) {
                return failure(input, nonZeroMessage(process.exitValue(), error));
            }
            return parsed;
        } catch (IOException e) {
            if (interrupted.get()) {
                return interrupted(input);
            }
            return failure(input, "Failed to read subagent output: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return interrupted(input);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            return failure(input, "Failed to collect subagent process output: " + e.getMessage());
        }
    }

    private byte[] readAll(InputStream in) {
        try {
            return in.readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private HeadlessSubagentOutput failure(HeadlessSubagentInput input, String message) {
        return new HeadlessSubagentOutput(
            input.taskName(),
            input.agentId(),
            input.childSessionId(),
            input.runId(),
            SubagentRunStatus.FAILED,
            "",
            Optional.empty(),
            Optional.ofNullable(message)
        );
    }

    private HeadlessSubagentOutput interrupted(HeadlessSubagentInput input) {
        return new HeadlessSubagentOutput(
            input.taskName(),
            input.agentId(),
            input.childSessionId(),
            input.runId(),
            SubagentRunStatus.INTERRUPTED,
            "已中断",
            Optional.empty(),
            Optional.of("Subagent process was interrupted")
        );
    }

    private String nonZeroMessage(int exitCode, String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "Subagent process exited with code " + exitCode;
        }
        return "Subagent process exited with code " + exitCode + ": " + stderr;
    }

    private String subagentCommandMissingMessage() {
        return "Subagent command is not configured. Configure lypi.subagent.command or run from a packaged lypi-boot jar "
            + "so the default command can be inferred as: java -jar <current-jar> headless-subagent";
    }

    private record ProcessHandle(
        Process process,
        HeadlessSubagentInput input,
        CompletableFuture<HeadlessSubagentOutput> completion,
        AtomicBoolean interrupted
    )
        implements SubagentProcessHandle {
        @Override
        public void interrupt() {
            interrupted.set(true);
            process.destroy();
            try {
                if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            completion.complete(new HeadlessSubagentOutput(
                input.taskName(),
                input.agentId(),
                input.childSessionId(),
                input.runId(),
                SubagentRunStatus.INTERRUPTED,
                "已中断",
                Optional.empty(),
                Optional.of("Subagent process was interrupted")
            ));
        }
    }
}
