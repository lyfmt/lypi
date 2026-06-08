package cn.lypi.boot.headless;

import cn.lypi.contracts.runtime.AgentCoreFactoryPort;
import cn.lypi.contracts.runtime.SessionManagerFactoryPort;
import cn.lypi.transport.headless.HeadlessSubagentJsonCodec;
import cn.lypi.transport.headless.HeadlessSubagentRunner;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.function.Supplier;

public final class HeadlessSubagentCommand {
    private final Supplier<HeadlessSubagentRunner> runner;

    public HeadlessSubagentCommand(
        AgentCoreFactoryPort agentCoreFactory,
        SessionManagerFactoryPort sessionManagerFactory,
        HeadlessSubagentJsonCodec codec
    ) {
        this(() -> new HeadlessSubagentRunner(agentCoreFactory, sessionManagerFactory, codec));
    }

    public HeadlessSubagentCommand(HeadlessSubagentRunner runner) {
        this(() -> Objects.requireNonNull(runner, "runner must not be null"));
    }

    HeadlessSubagentCommand(Supplier<HeadlessSubagentRunner> runner) {
        this.runner = Objects.requireNonNull(runner, "runner must not be null");
    }

    /**
     * 执行 headless subagent JSON 协议。
     */
    public int run(InputStream in, OutputStream out) {
        runner.get().run(in, out);
        return 0;
    }
}
