package cn.lypi.boot.headless;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;

public final class HeadlessSubagentApplicationRunner implements ApplicationRunner, ExitCodeGenerator {
    private static final int MISSING_COMMAND_EXIT_CODE = 2;

    private final Supplier<HeadlessSubagentCommand> command;
    private final Supplier<InputStream> input;
    private final Supplier<OutputStream> output;
    private int exitCode;

    public HeadlessSubagentApplicationRunner(Supplier<HeadlessSubagentCommand> command) {
        this(command, () -> System.in, () -> System.out);
    }

    HeadlessSubagentApplicationRunner(
        Supplier<HeadlessSubagentCommand> command,
        Supplier<InputStream> input,
        Supplier<OutputStream> output
    ) {
        this.command = Objects.requireNonNull(command, "command must not be null");
        this.input = Objects.requireNonNull(input, "input must not be null");
        this.output = Objects.requireNonNull(output, "output must not be null");
    }

    /**
     * 按 headless subagent 启动参数执行 stdin/stdout JSON 协议。
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!isHeadlessSubagent(args)) {
            return;
        }
        HeadlessSubagentCommand resolvedCommand = command.get();
        if (resolvedCommand == null) {
            exitCode = MISSING_COMMAND_EXIT_CODE;
            throw new IllegalStateException("Headless subagent command is not available");
        }
        exitCode = resolvedCommand.run(input.get(), output.get());
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    private boolean isHeadlessSubagent(ApplicationArguments args) {
        if (args == null) {
            return false;
        }
        if (args.containsOption("lypi.headless.subagent") || args.containsOption("lypi-headless-subagent")) {
            return true;
        }
        return List.of(args.getSourceArgs()).contains("headless-subagent");
    }
}
