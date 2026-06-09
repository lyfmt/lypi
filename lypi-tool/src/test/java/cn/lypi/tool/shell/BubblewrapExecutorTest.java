package cn.lypi.tool.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ToolProgressKind;
import cn.lypi.contracts.runtime.ExecutionMetadata;
import cn.lypi.contracts.runtime.ExecutionRequest;
import cn.lypi.contracts.runtime.ExecutionResult;
import cn.lypi.contracts.runtime.Executor;
import cn.lypi.contracts.runtime.NetworkMode;
import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BubblewrapExecutorTest {
    @TempDir
    Path tempDir;

    @Test
    void executesCommandThroughAvailableBwrap() throws Exception {
        Path fakeBwrap = fakeBwrap();
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            () -> Optional.of(fakeBwrap)
        );

        ExecutionResult result = executor.execute(request("printf sandbox", false), progress -> {
        }, () -> false);

        assertEquals(0, result.exitCode());
        assertEquals("sandbox", result.stdout());
        assertTrue(result.metadata().sandboxed());
        assertEquals("bubblewrap", result.metadata().executorName());
        assertTrue(result.metadata().diagnostic().isEmpty());
    }

    @Test
    void keepsSandboxMetadataWhenSandboxedCommandWritesBwrapLikeStderr() throws Exception {
        Path fakeBwrap = fakeBwrap();
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            () -> Optional.of(fakeBwrap)
        );

        ExecutionResult result = executor.execute(request("echo 'bwrap: user command failed' >&2; exit 1", false), progress -> {
        }, () -> false);

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("bwrap: user command failed"));
        assertTrue(result.metadata().sandboxed());
        assertEquals("bubblewrap", result.metadata().executorName());
    }

    @Test
    void keepsSandboxStartedSentinelOutOfProgress() throws Exception {
        Path fakeBwrap = fakeBwrap();
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            () -> Optional.of(fakeBwrap)
        );
        List<ToolProgress> progresses = new java.util.ArrayList<>();

        ExecutionResult result = executor.execute(request("printf user-progress >&2", false), progresses::add, () -> false);

        assertEquals(0, result.exitCode());
        assertEquals("user-progress", result.stderr());
        assertTrue(progresses.stream()
            .filter(progress -> progress.kind() == ToolProgressKind.OUTPUT)
            .noneMatch(progress -> progress.delta() != null && progress.delta().contains("__LYPI_BWRAP_STARTED__")));
    }

    @Test
    void fallsBackToHostExecutorWhenBwrapUnavailableByDefault() {
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            Optional::empty
        );

        ExecutionResult result = executor.execute(request("printf host", false), progress -> {
        }, () -> false);

        assertEquals(0, result.exitCode());
        assertEquals("host", result.stdout());
        assertFalse(result.metadata().sandboxed());
        assertEquals("host", result.metadata().executorName());
        assertTrue(result.metadata().diagnostic().orElseThrow().contains("bubblewrap unavailable"));
    }

    @Test
    void fallsBackToHostWhenBwrapUnavailableAndPolicyContainsDenyRead() throws Exception {
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            Optional::empty
        );

        ExecutionResult result = executor.execute(requestWithDenyRead("printf host-with-deny-read"), progress -> {
        }, () -> false);

        assertEquals(0, result.exitCode());
        assertEquals("host-with-deny-read", result.stdout());
        assertFalse(result.metadata().sandboxed());
        assertEquals("host", result.metadata().executorName());
        assertTrue(result.metadata().diagnostic().orElseThrow().contains("bubblewrap unavailable"));
    }

    @Test
    void failsWhenBwrapUnavailableAndPolicyRequiresSandbox() {
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            Optional::empty
        );

        ExecutionResult result = executor.execute(request("printf should-not-run", true), progress -> {
        }, () -> false);

        assertSandboxUnavailableFailure(result, "bubblewrap unavailable");
    }

    @Test
    void fallsBackToHostExecutorWhenBwrapPreflightFailsByDefault() throws Exception {
        Path failingBwrap = failingBwrap();
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            () -> Optional.of(failingBwrap)
        );

        ExecutionResult result = executor.execute(request("printf host-after-preflight", false), progress -> {
        }, () -> false);

        assertEquals(0, result.exitCode());
        assertEquals("host-after-preflight", result.stdout());
        assertFalse(result.metadata().sandboxed());
        assertEquals("host", result.metadata().executorName());
        assertTrue(result.metadata().diagnostic().orElseThrow().contains("bubblewrap unavailable"));
    }

    @Test
    void retriesWithoutProcMountWhenProcPreflightFails() throws Exception {
        Path procFailingBwrap = procFailingBwrap();
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            () -> Optional.of(procFailingBwrap)
        );

        ExecutionResult result = executor.execute(request("printf sandbox-after-proc-fallback", false), progress -> {
        }, () -> false);

        assertEquals(0, result.exitCode());
        assertEquals("sandbox-after-proc-fallback", result.stdout());
        assertTrue(result.metadata().sandboxed());
        assertEquals("bubblewrap", result.metadata().executorName());
    }

    @Test
    void doesNotRetryWithoutProcMountForUnrelatedPreflightPermissionFailure() throws Exception {
        Path unrelatedFailingBwrap = unrelatedPreflightFailingBwrap();
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            () -> Optional.of(unrelatedFailingBwrap)
        );

        ExecutionResult result = executor.execute(request("printf host-after-unrelated-failure", false), progress -> {
        }, () -> false);

        assertEquals(0, result.exitCode());
        assertEquals("host-after-unrelated-failure", result.stdout());
        assertFalse(result.metadata().sandboxed());
        assertEquals("host", result.metadata().executorName());
        assertTrue(result.metadata().diagnostic().orElseThrow().contains("bubblewrap unavailable"));
    }

    @Test
    void failsWhenProcFallbackPreflightFailsAndPolicyRequiresSandbox() throws Exception {
        Path procAndNoProcFailingBwrap = procAndNoProcFailingBwrap();
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            () -> Optional.of(procAndNoProcFailingBwrap)
        );

        ExecutionResult result = executor.execute(request("printf should-not-run", true), progress -> {
        }, () -> false);

        assertSandboxUnavailableFailure(result, "preflight failed exit");
    }

    @Test
    void fallsBackToHostExecutorWhenBwrapSetupFailsAfterPreflightByDefault() throws Exception {
        Path setupFailingBwrap = setupFailingBwrap();
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            () -> Optional.of(setupFailingBwrap)
        );

        ExecutionResult result = executor.execute(request("printf host-after-setup-failure", false), progress -> {
        }, () -> false);

        assertEquals(0, result.exitCode());
        assertEquals("host-after-setup-failure", result.stdout());
        assertFalse(result.metadata().sandboxed());
        assertEquals("host", result.metadata().executorName());
        assertTrue(result.metadata().diagnostic().orElseThrow().contains("bubblewrap execution failed"));
        assertTrue(result.metadata().diagnostic().orElseThrow().contains("bwrap: setup failed"));
    }

    @Test
    void doesNotFallBackToHostWhenBwrapExecutionTimesOut() throws Exception {
        Path fakeBwrap = fakeBwrap();
        ControlledBwrapHostExecutor host = ControlledBwrapHostExecutor.timeout();
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            host,
            () -> Optional.of(fakeBwrap)
        );

        ExecutionResult result = executor.execute(request("printf should-not-run", false), progress -> {
        }, () -> false);

        assertEquals(1, host.calls());
        assertNotEquals(0, result.exitCode());
        assertTrue(result.timedOut());
        assertFalse(result.metadata().sandboxed());
        assertEquals("bubblewrap", result.metadata().executorName());
        assertTrue(result.metadata().diagnostic().orElseThrow().contains("timed out"));
        assertTrue(!result.stdout().contains("should-not-run"));
    }

    @Test
    void doesNotFallBackToHostWhenBwrapExecutionIsAborted() throws Exception {
        Path fakeBwrap = fakeBwrap();
        ControlledBwrapHostExecutor host = ControlledBwrapHostExecutor.aborted();
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            host,
            () -> Optional.of(fakeBwrap)
        );

        ExecutionResult result = executor.execute(request("printf should-not-run", false), progress -> {
        }, () -> true);

        assertEquals(1, host.calls());
        assertNotEquals(0, result.exitCode());
        assertFalse(result.metadata().sandboxed());
        assertEquals("bubblewrap", result.metadata().executorName());
        assertTrue(result.metadata().diagnostic().orElseThrow().contains("aborted"));
        assertTrue(!result.stdout().contains("should-not-run"));
    }

    @Test
    void fallsBackToHostWhenBwrapSetupFailsAndPolicyContainsDenyRead() throws Exception {
        Path setupFailingBwrap = setupFailingBwrap();
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            () -> Optional.of(setupFailingBwrap)
        );

        ExecutionResult result = executor.execute(requestWithDenyRead("printf host-after-setup-deny-read"), progress -> {
        }, () -> false);

        assertEquals(0, result.exitCode());
        assertEquals("host-after-setup-deny-read", result.stdout());
        assertFalse(result.metadata().sandboxed());
        assertEquals("host", result.metadata().executorName());
        assertTrue(result.metadata().diagnostic().orElseThrow().contains("bubblewrap execution failed"));
        assertTrue(result.metadata().diagnostic().orElseThrow().contains("bwrap: setup failed"));
    }

    @Test
    void failsWhenBwrapSetupFailsAfterPreflightAndPolicyRequiresSandbox() throws Exception {
        Path setupFailingBwrap = setupFailingBwrap();
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            () -> Optional.of(setupFailingBwrap)
        );

        ExecutionResult result = executor.execute(request("printf should-not-run", true), progress -> {
        }, () -> false);

        assertSandboxUnavailableFailure(result, "bubblewrap execution failed");
        assertTrue(result.metadata().diagnostic().orElseThrow().contains("bwrap: setup failed"));
    }

    @Test
    void failsWhenBwrapPreflightFailsAndPolicyRequiresSandbox() throws Exception {
        Path failingBwrap = failingBwrap();
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            () -> Optional.of(failingBwrap)
        );

        ExecutionResult result = executor.execute(request("printf should-not-run", true), progress -> {
        }, () -> false);

        assertSandboxUnavailableFailure(result, "bubblewrap unavailable");
    }

    @Test
    void returnsFailureWhenPolicyBuilderRejectsRequest() throws Exception {
        Path fakeBwrap = fakeBwrap();
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            () -> Optional.of(fakeBwrap)
        );

        ExecutionResult result = executor.execute(requestWithDenyWrite("printf should-not-run"), progress -> {
        }, () -> false);

        assertEquals(126, result.exitCode());
        assertEquals("", result.stdout());
        assertTrue(result.stderr().contains("bubblewrap policy rejected"));
        assertTrue(result.stderr().contains("denyWrite"));
        assertTrue(result.stderr().contains("unsupported"));
        assertFalse(result.metadata().sandboxed());
        assertEquals("bubblewrap", result.metadata().executorName());
        assertTrue(result.metadata().diagnostic().orElseThrow().contains("policy rejected"));
    }

    @Test
    void keepsSyntheticFileWhenItWasFilledBeforeCleanup() throws Exception {
        Path fillingBwrap = fillingRoBindDataTargetBwrap("host-content");
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            () -> Optional.of(fillingBwrap)
        );
        Path missingSecret = tempDir.resolve("missing-secret").resolve("token");

        ExecutionResult result = executor.execute(requestWithMissingDenyRead("printf sandbox", missingSecret), progress -> {
        }, () -> false);

        assertEquals(0, result.exitCode());
        assertTrue(result.metadata().sandboxed());
        assertEquals("sandbox", result.stdout());
        assertEquals("host-content", Files.readString(tempDir.resolve("missing-secret")));
    }

    @Test
    void preservesExistingEmptyProtectedMetadataDirectoryAfterSyntheticMaskCleanup() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path codex = Files.createDirectory(workspace.resolve(".codex"));
        Path checkingBwrap = requiringTmpfsTargetBwrap(codex);
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            () -> Optional.of(checkingBwrap)
        );

        ExecutionResult result = executor.execute(requestInWorkspace("printf sandbox", workspace), progress -> {
        }, () -> false);

        assertEquals(0, result.exitCode());
        assertTrue(result.metadata().sandboxed());
        assertEquals("sandbox", result.stdout());
        assertTrue(Files.isDirectory(codex));
        try (java.util.stream.Stream<Path> entries = Files.list(codex)) {
            assertTrue(entries.findAny().isEmpty());
        }
    }

    @Test
    void removesReplacedExistingEmptyProtectedMetadataFileAfterSyntheticMaskCleanup() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path git = Files.writeString(workspace.resolve(".git"), "");
        Path replacingBwrap = replacingRoBindDataTargetBwrap(git);
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            () -> Optional.of(replacingBwrap)
        );

        ExecutionResult result = executor.execute(requestInWorkspace("printf sandbox", workspace), progress -> {
        }, () -> false);

        assertEquals(0, result.exitCode());
        assertTrue(result.metadata().sandboxed());
        assertEquals("sandbox", result.stdout());
        assertTrue(!Files.exists(git, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void cleansProtectedCreateTargetAndFailsSuccessfulCommand() throws Exception {
        Path fakeBwrap = fakeBwrap();
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            () -> Optional.of(fakeBwrap)
        );
        Path repo = Files.createDirectory(tempDir.resolve("repo"));
        Path parentGit = Files.createDirectory(repo.resolve(".git"));
        Files.writeString(parentGit.resolve("HEAD"), "ref: refs/heads/main\n");
        Path workspace = Files.createDirectory(repo.resolve("workspace"));
        Path childGit = workspace.resolve(".git");

        ExecutionResult result = executor.execute(requestInWorkspace(
            "mkdir -p .git; printf created > .git/HEAD; printf user-success",
            workspace
        ), progress -> {
        }, () -> false);

        assertEquals(1, result.exitCode());
        assertEquals("user-success", result.stdout());
        assertTrue(result.stderr().contains("protected workspace metadata"));
        assertTrue(result.metadata().sandboxed());
        assertEquals("bubblewrap", result.metadata().executorName());
        assertTrue(!Files.exists(childGit));
    }

    @Test
    void flagsTransientProtectedCreateTargetEvenWhenCommandRemovesIt() throws Exception {
        Path fakeBwrap = fakeBwrap();
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            () -> Optional.of(fakeBwrap)
        );
        Path repo = Files.createDirectory(tempDir.resolve("repo"));
        Path parentGit = Files.createDirectory(repo.resolve(".git"));
        Files.writeString(parentGit.resolve("HEAD"), "ref: refs/heads/main\n");
        Path workspace = Files.createDirectory(repo.resolve("workspace"));
        Path childGit = workspace.resolve(".git");

        ExecutionResult result = executor.execute(requestInWorkspace(
            "mkdir .git; rmdir .git; printf user-success",
            workspace
        ), progress -> {
        }, () -> false);

        assertEquals(1, result.exitCode());
        assertEquals("user-success", result.stdout());
        assertTrue(result.stderr().contains("protected workspace metadata"));
        assertTrue(result.metadata().sandboxed());
        assertEquals("bubblewrap", result.metadata().executorName());
        assertTrue(!Files.exists(childGit));
    }

    @Test
    void reportsProtectedCreateCleanupFailureWithoutThrowing() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path fakeBwrap = fakeBwrap();
        Path repo = Files.createDirectory(tempDir.resolve("repo"));
        Path parentGit = Files.createDirectory(repo.resolve(".git"));
        Files.writeString(parentGit.resolve("HEAD"), "ref: refs/heads/main\n");
        Path workspace = Files.createDirectory(repo.resolve("workspace"));
        Path childGit = workspace.resolve(".git");
        Path locked = childGit.resolve("locked");
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new LockedProtectedCreateExecutor(childGit),
            () -> Optional.of(fakeBwrap)
        );

        try {
            ExecutionResult result = executor.execute(requestInWorkspace(
                "printf ignored",
                workspace
            ), progress -> {
            }, () -> false);

            assertEquals(1, result.exitCode());
            assertEquals("user-success", result.stdout());
            assertTrue(result.stderr().contains("protected workspace metadata"));
            assertTrue(result.stderr().contains("failed to remove"));
            assertTrue(result.metadata().sandboxed());
            assertEquals("bubblewrap", result.metadata().executorName());
        } finally {
            restoreDirectoryPermissions(locked);
            restoreDirectoryPermissions(childGit);
        }
    }

    @Test
    void serializesExecutionsForSameProtectedCreateTarget() throws Exception {
        Path fakeBwrap = fakeBwrap();
        Path repo = Files.createDirectory(tempDir.resolve("repo"));
        Path parentGit = Files.createDirectory(repo.resolve(".git"));
        Files.writeString(parentGit.resolve("HEAD"), "ref: refs/heads/main\n");
        Path workspace = Files.createDirectory(repo.resolve("workspace"));
        BlockingProtectedCreateExecutor host = new BlockingProtectedCreateExecutor();
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            host,
            () -> Optional.of(fakeBwrap)
        );
        AtomicReference<ExecutionResult> firstResult = new AtomicReference<>();
        AtomicReference<ExecutionResult> secondResult = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread first = Thread.ofVirtual().start(() -> runAndCapture(executor, workspace, firstResult, failure));
        assertTrue(host.awaitFirstEntry(), "first execution must enter host executor");
        Thread second = Thread.ofVirtual().start(() -> runAndCapture(executor, workspace, secondResult, failure));
        Thread.sleep(50);

        assertEquals(1, host.maxActive());
        assertTrue(secondResult.get() == null, "second execution must wait for same protected path lease");

        host.release();
        first.join(Duration.ofSeconds(2));
        second.join(Duration.ofSeconds(2));

        assertTrue(failure.get() == null, () -> failure.get().getMessage());
        assertEquals(0, firstResult.get().exitCode());
        assertEquals(0, secondResult.get().exitCode());
        assertEquals(1, host.maxActive());
    }

    private void assertSandboxUnavailableFailure(ExecutionResult result, String diagnosticFragment) {
        assertEquals(126, result.exitCode());
        assertEquals("", result.stdout());
        assertFalse(result.metadata().sandboxed());
        assertEquals("bubblewrap", result.metadata().executorName());
        assertTrue(result.metadata().diagnostic().orElseThrow().contains(diagnosticFragment));
        assertTrue(!result.metadata().diagnostic().orElseThrow().contains("fell back to host"));
    }

    private ExecutionRequest request(String command, boolean failIfUnavailable) {
        return new ExecutionRequest(
            List.of("bash", "-lc", command),
            tempDir,
            Map.of(),
            Duration.ofSeconds(5),
            new SandboxRuntimePolicy(
                List.of(Path.of("/usr"), Path.of("/bin"), Path.of("/lib"), Path.of("/lib64"), Path.of("/etc")),
                List.of(),
                List.of(tempDir),
                List.of(),
                NetworkMode.DISABLED,
                failIfUnavailable,
                false
            )
        );
    }

    private ExecutionRequest requestInWorkspace(String command, Path workspace) {
        return new ExecutionRequest(
            List.of("bash", "-lc", command),
            workspace,
            Map.of(),
            Duration.ofSeconds(5),
            new SandboxRuntimePolicy(
                List.of(Path.of("/usr"), Path.of("/bin"), Path.of("/lib"), Path.of("/lib64"), Path.of("/etc")),
                List.of(),
                List.of(workspace),
                List.of(),
                NetworkMode.DISABLED,
                false,
                false
            )
        );
    }

    private ExecutionRequest requestWithDenyRead(String command) throws Exception {
        Path secret = Files.createDirectories(tempDir.resolve("secret"));
        return new ExecutionRequest(
            List.of("bash", "-lc", command),
            tempDir,
            Map.of(),
            Duration.ofSeconds(5),
            new SandboxRuntimePolicy(
                List.of(Path.of("/usr"), Path.of("/bin"), Path.of("/lib"), Path.of("/lib64"), Path.of("/etc")),
                List.of(secret),
                List.of(tempDir),
                List.of(),
                NetworkMode.DISABLED,
                false,
                false
            )
        );
    }

    private ExecutionRequest requestWithDenyWrite(String command) {
        return new ExecutionRequest(
            List.of("bash", "-lc", command),
            tempDir,
            Map.of(),
            Duration.ofSeconds(5),
            new SandboxRuntimePolicy(
                List.of(Path.of("/usr"), Path.of("/bin"), Path.of("/lib"), Path.of("/lib64"), Path.of("/etc")),
                List.of(),
                List.of(tempDir),
                List.of(tempDir.resolve("blocked")),
                NetworkMode.DISABLED,
                false,
                false
            )
        );
    }

    private ExecutionRequest requestWithMissingDenyRead(String command, Path missingDenyReadPath) {
        return new ExecutionRequest(
            List.of("bash", "-lc", command),
            tempDir,
            Map.of(),
            Duration.ofSeconds(5),
            new SandboxRuntimePolicy(
                List.of(Path.of("/usr"), Path.of("/bin"), Path.of("/lib"), Path.of("/lib64"), Path.of("/etc")),
                List.of(missingDenyReadPath),
                List.of(tempDir),
                List.of(),
                NetworkMode.DISABLED,
                false,
                false
            )
        );
    }

    private void restoreDirectoryPermissions(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rwx------");
        Files.setPosixFilePermissions(path, permissions);
    }

    private void runAndCapture(
        BubblewrapExecutor executor,
        Path workspace,
        AtomicReference<ExecutionResult> result,
        AtomicReference<Throwable> failure
    ) {
        try {
            result.set(executor.execute(requestInWorkspace("printf ignored", workspace), progress -> {
            }, () -> false));
        } catch (Throwable throwable) {
            failure.compareAndSet(null, throwable);
        }
    }

    private static final class LockedProtectedCreateExecutor implements Executor {
        private final Path target;

        private LockedProtectedCreateExecutor(Path target) {
            this.target = target;
        }

        @Override
        public String name() {
            return "host";
        }

        @Override
        public ExecutionResult execute(ExecutionRequest request, ProgressSink progress, AbortSignal signal) {
            String sentinel = request.command().stream()
                .filter(argument -> argument.startsWith("__LYPI_BWRAP_STARTED__"))
                .findFirst()
                .orElseThrow();
            createLockedTarget();
            return new ExecutionResult(
                0,
                "user-success",
                sentinel + "\n",
                false,
                Optional.empty(),
                ExecutionMetadata.unsandboxed(name())
            );
        }

        private void createLockedTarget() {
            try {
                Path staging = target.resolveSibling(".git-staging-" + java.util.UUID.randomUUID());
                Path locked = staging.resolve("locked");
                Files.createDirectory(staging);
                Files.createDirectory(locked);
                Files.writeString(locked.resolve("file"), "secret");
                Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    private static final class BlockingProtectedCreateExecutor implements Executor {
        private final CountDownLatch firstEntry = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();

        @Override
        public String name() {
            return "host";
        }

        @Override
        public ExecutionResult execute(ExecutionRequest request, ProgressSink progress, AbortSignal signal) {
            String sentinel = request.command().stream()
                .filter(argument -> argument.startsWith("__LYPI_BWRAP_STARTED__"))
                .findFirst()
                .orElseThrow();
            int running = active.incrementAndGet();
            maxActive.accumulateAndGet(running, Math::max);
            firstEntry.countDown();
            try {
                release.await(1, TimeUnit.SECONDS);
                return new ExecutionResult(
                    0,
                    "user-success",
                    sentinel + "\n",
                    false,
                    Optional.empty(),
                    ExecutionMetadata.unsandboxed(name())
                );
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            } finally {
                active.decrementAndGet();
            }
        }

        private boolean awaitFirstEntry() throws InterruptedException {
            return firstEntry.await(1, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }

        private int maxActive() {
            return maxActive.get();
        }
    }

    private static final class ControlledBwrapHostExecutor implements Executor {
        private final ExecutionResult firstResult;
        private final AtomicInteger calls = new AtomicInteger();

        private ControlledBwrapHostExecutor(ExecutionResult firstResult) {
            this.firstResult = firstResult;
        }

        private static ControlledBwrapHostExecutor timeout() {
            return new ControlledBwrapHostExecutor(new ExecutionResult(
                124,
                "",
                "__LYPI_BWRAP_STARTED__test\n",
                true,
                Optional.empty(),
                ExecutionMetadata.unsandboxed("host")
            ));
        }

        private static ControlledBwrapHostExecutor aborted() {
            return new ControlledBwrapHostExecutor(new ExecutionResult(
                130,
                "",
                "__LYPI_BWRAP_STARTED__test\n",
                false,
                Optional.empty(),
                ExecutionMetadata.unsandboxed("host")
            ));
        }

        @Override
        public String name() {
            return "host";
        }

        @Override
        public ExecutionResult execute(ExecutionRequest request, ProgressSink progress, AbortSignal signal) {
            int call = calls.incrementAndGet();
            if (call == 1) {
                return firstResult;
            }
            return new ExecutionResult(
                0,
                "fallback-ran",
                "",
                false,
                Optional.empty(),
                ExecutionMetadata.unsandboxed(name())
            );
        }

        private int calls() {
            return calls.get();
        }
    }

    private Path fakeBwrap() throws Exception {
        Path script = tempDir.resolve("fake-bwrap.sh");
        Files.writeString(script, """
            #!/bin/sh
            while [ "$#" -gt 0 ] && [ "$1" != "--" ]; do
              shift
            done
            if [ "$1" = "--" ]; then
              shift
            fi
            exec "$@"
            """);
        script.toFile().setExecutable(true);
        return script;
    }

    private Path fillingRoBindDataTargetBwrap(String content) throws Exception {
        Path contentFile = tempDir.resolve("ro-bind-data-content.txt");
        Files.writeString(contentFile, content);
        Path script = tempDir.resolve("filling-ro-bind-data-target-bwrap.sh");
        Files.writeString(script, """
            #!/bin/sh
            content_file="%s"
            while [ "$#" -gt 0 ] && [ "$1" != "--" ]; do
              if [ "$1" = "--ro-bind-data" ]; then
                shift
                fd="${1:-}"
                shift
                target="${1:-}"
                if [ "$fd" = "0" ]; then
                  mkdir -p "$(dirname "$target")"
                  cat "$content_file" > "$target"
                fi
              fi
              shift
            done
            if [ "$1" = "--" ]; then
              shift
            fi
            exec "$@"
            """.formatted(contentFile));
        script.toFile().setExecutable(true);
        return script;
    }

    private Path replacingRoBindDataTargetBwrap(Path replacedTarget) throws Exception {
        Path script = tempDir.resolve("replacing-ro-bind-data-target-bwrap.sh");
        Files.writeString(script, """
            #!/bin/sh
            replaced_target="%s"
            while [ "$#" -gt 0 ] && [ "$1" != "--" ]; do
              if [ "$1" = "--ro-bind-data" ]; then
                shift
                fd="${1:-}"
                shift
                target="${1:-}"
                if [ "$fd" = "0" ] && [ "$target" = "$replaced_target" ]; then
                  replacement="${target}.replacement.$$"
                  : > "$replacement"
                  mv -f "$replacement" "$target"
                fi
              fi
              shift
            done
            if [ "$1" = "--" ]; then
              shift
            fi
            exec "$@"
            """.formatted(replacedTarget));
        script.toFile().setExecutable(true);
        return script;
    }

    private Path requiringTmpfsTargetBwrap(Path requiredTarget) throws Exception {
        Path script = tempDir.resolve("requiring-tmpfs-target-bwrap.sh");
        Files.writeString(script, """
            #!/bin/sh
            required_target="%s"
            saw_required_target=0
            previous=""
            for arg in "$@"; do
              if [ "$previous" = "--tmpfs" ] && [ "$arg" = "$required_target" ]; then
                saw_required_target=1
              fi
              previous="$arg"
            done
            while [ "$#" -gt 0 ] && [ "$1" != "--" ]; do
              shift
            done
            if [ "$1" = "--" ]; then
              shift
            fi
            if [ "${1:-}" = "/usr/bin/true" ] || [ "${1:-}" = "/bin/true" ] || [ "${1:-}" = "true" ]; then
              exec "$@"
            fi
            if [ "$saw_required_target" != "1" ]; then
              echo "missing required tmpfs target: $required_target" >&2
              exit 88
            fi
            exec "$@"
            """.formatted(requiredTarget));
        script.toFile().setExecutable(true);
        return script;
    }

    private Path procFailingBwrap() throws Exception {
        Path script = tempDir.resolve("proc-failing-bwrap.sh");
        Files.writeString(script, """
            #!/bin/sh
            for arg in "$@"; do
              if [ "$arg" = "--proc" ]; then
                echo "bwrap: Can't mount proc on /proc: Operation not permitted" >&2
                exit 1
              fi
            done
            while [ "$#" -gt 0 ] && [ "$1" != "--" ]; do
              shift
            done
            if [ "$1" = "--" ]; then
              shift
            fi
            exec "$@"
            """);
        script.toFile().setExecutable(true);
        return script;
    }

    private Path unrelatedPreflightFailingBwrap() throws Exception {
        Path script = tempDir.resolve("unrelated-preflight-failing-bwrap.sh");
        Files.writeString(script, """
            #!/bin/sh
            echo "bwrap: Can't bind mount /dev/null: Operation not permitted" >&2
            exit 1
            """);
        script.toFile().setExecutable(true);
        return script;
    }

    private Path procAndNoProcFailingBwrap() throws Exception {
        Path script = tempDir.resolve("proc-and-no-proc-failing-bwrap.sh");
        Files.writeString(script, """
            #!/bin/sh
            for arg in "$@"; do
              if [ "$arg" = "--proc" ]; then
                echo "bwrap: Can't mount proc on /proc: Operation not permitted" >&2
                exit 1
              fi
            done
            echo "bwrap: still unavailable without proc" >&2
            exit 42
            """);
        script.toFile().setExecutable(true);
        return script;
    }

    private Path setupFailingBwrap() throws Exception {
        Path script = tempDir.resolve("setup-failing-bwrap.sh");
        Files.writeString(script, """
            #!/bin/sh
            if [ "${1:-}" = "--new-session" ]; then
              while [ "$#" -gt 0 ] && [ "$1" != "--" ]; do
                shift
              done
              if [ "$1" = "--" ]; then
                shift
              fi
              if [ "${1:-}" = "/usr/bin/true" ] || [ "${1:-}" = "/bin/true" ] || [ "${1:-}" = "true" ]; then
                exec "$@"
              fi
            fi
            echo 'bwrap: setup failed' >&2
            exit 1
            """);
        script.toFile().setExecutable(true);
        return script;
    }

    private Path failingBwrap() throws Exception {
        Path script = tempDir.resolve("failing-bwrap.sh");
        Files.writeString(script, """
            #!/bin/sh
            echo namespace failed >&2
            exit 42
            """);
        script.toFile().setExecutable(true);
        return script;
    }
}
