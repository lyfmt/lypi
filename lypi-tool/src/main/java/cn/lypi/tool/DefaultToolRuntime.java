package cn.lypi.tool;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * 默认工具运行时。
 *
 * NOTE: 运行时负责串接注册表、校验、权限、拦截、执行规划和结果预算。
 */
public final class DefaultToolRuntime implements ToolRuntimePort, ToolOrchestrator {
    private static final String METADATA_ABORT_SIGNAL = "abortSignal";
    private static final String METADATA_ORIGINAL_TOOL_NAME = "originalToolName";
    private static final String METADATA_TOOL_USE_ID = "toolUseId";
    private static final ProgressSink NOOP_PROGRESS = message -> {
    };

    private final ToolRegistry registry;
    private final ToolSchemaValidator schemaValidator;
    private final ToolExecutionPlanner executionPlanner;
    private final ToolResultBudgeter resultBudgeter;
    private final ToolRuntimeContextFactory contextFactory;
    private final ToolExecutionInterceptor interceptor;
    private final SecurityRuntimePort securityRuntime;
    private final PermissionGate permissionGate;
    private final int maxConcurrency;

    public DefaultToolRuntime(SecurityRuntimePort securityRuntime) {
        this(ToolRuntimeOptions.defaults(), securityRuntime);
    }

    public DefaultToolRuntime(ToolRuntimeOptions options, SecurityRuntimePort securityRuntime) {
        this(
            new DefaultToolRegistry(),
            new ToolSchemaValidator(),
            new ToolExecutionPlanner(),
            new ToolResultBudgeter(),
            new ToolRuntimeContextFactory(normalizeOptions(options)),
            ToolExecutionInterceptors.noop(),
            securityRuntime,
            PermissionGate.denying(),
            normalizeOptions(options).maxConcurrency()
        );
    }

    public DefaultToolRuntime(
        ToolRegistry registry,
        ToolSchemaValidator schemaValidator,
        ToolExecutionPlanner executionPlanner,
        ToolResultBudgeter resultBudgeter,
        ToolRuntimeContextFactory contextFactory,
        ToolExecutionInterceptor interceptor,
        SecurityRuntimePort securityRuntime
    ) {
        this(
            registry,
            schemaValidator,
            executionPlanner,
            resultBudgeter,
            contextFactory,
            interceptor,
            securityRuntime,
            PermissionGate.denying(),
            ToolRuntimeOptions.defaults().maxConcurrency()
        );
    }

    public DefaultToolRuntime(
        ToolRegistry registry,
        ToolSchemaValidator schemaValidator,
        ToolExecutionPlanner executionPlanner,
        ToolResultBudgeter resultBudgeter,
        ToolRuntimeContextFactory contextFactory,
        ToolExecutionInterceptor interceptor,
        SecurityRuntimePort securityRuntime,
        PermissionGate permissionGate
    ) {
        this(
            registry,
            schemaValidator,
            executionPlanner,
            resultBudgeter,
            contextFactory,
            interceptor,
            securityRuntime,
            permissionGate,
            ToolRuntimeOptions.defaults().maxConcurrency()
        );
    }

    private DefaultToolRuntime(
        ToolRegistry registry,
        ToolSchemaValidator schemaValidator,
        ToolExecutionPlanner executionPlanner,
        ToolResultBudgeter resultBudgeter,
        ToolRuntimeContextFactory contextFactory,
        ToolExecutionInterceptor interceptor,
        SecurityRuntimePort securityRuntime,
        PermissionGate permissionGate,
        int maxConcurrency
    ) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.schemaValidator = Objects.requireNonNull(schemaValidator, "schemaValidator must not be null");
        this.executionPlanner = Objects.requireNonNull(executionPlanner, "executionPlanner must not be null");
        this.resultBudgeter = Objects.requireNonNull(resultBudgeter, "resultBudgeter must not be null");
        this.contextFactory = Objects.requireNonNull(contextFactory, "contextFactory must not be null");
        this.interceptor = interceptor == null ? ToolExecutionInterceptors.noop() : interceptor;
        this.securityRuntime = Objects.requireNonNull(securityRuntime, "securityRuntime must not be null");
        this.permissionGate = permissionGate == null ? PermissionGate.denying() : permissionGate;
        this.maxConcurrency = Math.max(1, maxConcurrency);
    }

    @Override
    public void register(Tool<?, ?> tool) {
        registry.register(tool);
    }

    @Override
    public Optional<Tool<?, ?>> resolve(String nameOrAlias) {
        return registry.resolve(nameOrAlias);
    }

    @Override
    public ToolRegistrySnapshot snapshot() {
        return registry.snapshot();
    }

    /**
     * 编排并执行一组工具调用。
     *
     * 返回顺序始终与请求顺序一致。
     */
    @Override
    public List<ToolResult<?>> execute(List<ToolUseRequest> requests, ContextSnapshot context) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        List<ToolResult<?>> results = new ArrayList<>(requests.size());
        for (int index = 0; index < requests.size(); index++) {
            results.add(null);
        }

        List<IndexedCall> resolvedCalls = new ArrayList<>();
        for (int index = 0; index < requests.size(); index++) {
            ToolUseRequest request = requests.get(index);
            Optional<Tool<?, ?>> tool = registry.resolve(request.toolName());
            if (tool.isEmpty()) {
                results.set(index, errorResult(request.toolUseId(), "未知工具: " + request.toolName()));
                continue;
            }
            Tool<Map<String, Object>, ?> resolvedTool = castTool(tool.get());
            resolvedCalls.add(new IndexedCall(index, canonicalRequest(request, resolvedTool), request.toolName(), resolvedTool));
        }

        List<ToolExecutionPlanner.ResolvedToolCall> calls = resolvedCalls.stream()
            .map(call -> new ToolExecutionPlanner.ResolvedToolCall(call.request(), call.tool()))
            .toList();
        List<ToolExecutionPlanner.Batch> batches = executionPlanner.plan(calls);
        int cursor = 0;
        for (ToolExecutionPlanner.Batch batch : batches) {
            List<IndexedCall> indexedBatch = resolvedCalls.subList(cursor, cursor + batch.calls().size());
            executeBatch(batch, indexedBatch, context, results);
            cursor += batch.calls().size();
        }
        return List.copyOf(results);
    }

    private void executeBatch(
        ToolExecutionPlanner.Batch batch,
        List<IndexedCall> indexedBatch,
        ContextSnapshot context,
        List<ToolResult<?>> results
    ) {
        if (!batch.parallel()) {
            IndexedCall indexedCall = indexedBatch.getFirst();
            results.set(indexedCall.index(), executeCall(indexedCall.request(), indexedCall.originalToolName(), indexedCall.tool(), context));
            return;
        }

        try (BoundedVirtualExecutor executor = new BoundedVirtualExecutor(maxConcurrency)) {
            List<CompletableFuture<IndexedResult>> futures = indexedBatch.stream()
                .map(call -> CompletableFuture.supplyAsync(
                    () -> new IndexedResult(call.index(), executeCall(call.request(), call.originalToolName(), call.tool(), context)),
                    executor
                ))
                .toList();
            for (CompletableFuture<IndexedResult> future : futures) {
                IndexedResult result = future.join();
                results.set(result.index(), result.result());
            }
        }
    }

    private ToolResult<?> executeCall(
        ToolUseRequest request,
        String originalToolName,
        Tool<Map<String, Object>, ?> tool,
        ContextSnapshot context
    ) {
        Map<String, Object> input = request.input() == null ? Map.of() : request.input();
        ToolUseContext toolContext = contextWithCallMetadata(
            contextFactory.create(request, context),
            request.toolUseId(),
            originalToolName,
            request.toolName()
        );
        try {
            ValidationResult schemaResult = schemaValidator.validate(tool.inputSchema(), input);
            if (!schemaResult.valid()) {
                return errorResult(request.toolUseId(), "工具输入 schema 校验失败: " + joinMessages(schemaResult));
            }

            ValidationResult inputResult = tool.validateInput(input, toolContext);
            if (inputResult != null && !inputResult.valid()) {
                return errorResult(request.toolUseId(), "工具输入校验失败: " + joinMessages(inputResult));
            }

            PermissionDecision toolDecision = tool.checkPermissions(input, toolContext);
            PermissionGateResult toolGateResult = resolvePermission(request, tool, toolContext, toolDecision);
            if (toolGateResult.status() != PermissionGateResult.Status.ALLOW) {
                return permissionGateError(request.toolUseId(), toolGateResult);
            }

            PermissionDecision securityDecision = securityRuntime.decide(request, toolContext);
            PermissionGateResult securityGateResult = resolvePermission(request, tool, toolContext, securityDecision);
            if (securityGateResult.status() != PermissionGateResult.Status.ALLOW) {
                return permissionGateError(request.toolUseId(), securityGateResult);
            }

            ToolExecutionInterceptor.BeforeResult beforeResult = interceptor.beforeExecute(request, tool, toolContext);
            if (beforeResult != null && beforeResult.blocked()) {
                return errorResult(request.toolUseId(), beforeResult.message());
            }

            if (aborted(toolContext)) {
                return errorResult(request.toolUseId(), "工具调用已中止。");
            }

            ToolResult<?> result = executeTool(request, tool, input, toolContext);
            return applyAfterInterceptorAndBudget(request, tool, toolContext, result);
        } catch (RuntimeException exception) {
            return errorResult(request.toolUseId(), "工具执行失败: " + exception.getMessage());
        }
    }

    private ToolResult<?> executeTool(
        ToolUseRequest request,
        Tool<Map<String, Object>, ?> tool,
        Map<String, Object> input,
        ToolUseContext toolContext
    ) {
        try {
            return tool.execute(input, toolContext, NOOP_PROGRESS);
        } catch (RuntimeException exception) {
            return errorResult(request.toolUseId(), "工具执行失败: " + exception.getMessage());
        }
    }

    private ToolResult<?> applyAfterInterceptorAndBudget(
        ToolUseRequest request,
        Tool<Map<String, Object>, ?> tool,
        ToolUseContext toolContext,
        ToolResult<?> result
    ) {
        ToolResult<?> interceptedResult = interceptor.afterExecute(request, tool, toolContext, result);
        ToolResult<?> finalResult = interceptedResult == null ? result : interceptedResult;
        return resultBudgeter.apply(request.toolUseId(), tool.name(), finalResult, tool.maxResultSize());
    }

    private ToolUseRequest canonicalRequest(ToolUseRequest request, Tool<Map<String, Object>, ?> tool) {
        if (Objects.equals(request.toolName(), tool.name())) {
            return request;
        }
        return new ToolUseRequest(
            request.toolUseId(),
            tool.name(),
            request.input(),
            request.parentMessageId()
        );
    }

    private ToolUseContext contextWithCallMetadata(
        ToolUseContext context,
        String toolUseId,
        String originalToolName,
        String canonicalToolName
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>(context.metadata());
        metadata.put(METADATA_TOOL_USE_ID, toolUseId);
        if (!Objects.equals(originalToolName, canonicalToolName)) {
            metadata.put(METADATA_ORIGINAL_TOOL_NAME, originalToolName);
        }
        return new ToolUseContext(
            context.sessionId(),
            context.messageId(),
            context.cwd(),
            Map.copyOf(metadata)
        );
    }

    private static ToolRuntimeOptions normalizeOptions(ToolRuntimeOptions options) {
        return options == null ? ToolRuntimeOptions.defaults() : options;
    }

    private boolean aborted(ToolUseContext context) {
        Object signal = context.metadata().get(METADATA_ABORT_SIGNAL);
        return signal instanceof AbortSignal abortSignal && abortSignal.aborted();
    }

    private PermissionGateResult resolvePermission(
        ToolUseRequest request,
        Tool<Map<String, Object>, ?> tool,
        ToolUseContext context,
        PermissionDecision decision
    ) {
        if (decision == null || decision.behavior() == PermissionBehavior.DENY) {
            return PermissionGateResult.deny(decisionMessage(decision));
        }
        if (decision.behavior() == PermissionBehavior.ALLOW) {
            return PermissionGateResult.allow();
        }
        PermissionGateResult result = permissionGate.request(request, tool, context, decision);
        return result == null ? PermissionGateResult.deny("权限请求未获允许。") : result;
    }

    private ToolResult<?> permissionGateError(String toolUseId, PermissionGateResult result) {
        String message = result.message().orElse("权限请求未获允许。");
        if (result.status() == PermissionGateResult.Status.ABORT) {
            return errorResult(toolUseId, "工具权限请求已中断: " + message);
        }
        return errorResult(toolUseId, "权限请求未获允许: " + message);
    }

    private String decisionMessage(PermissionDecision decision) {
        if (decision == null || decision.message() == null || decision.message().isBlank()) {
            return "未提供原因。";
        }
        return decision.message();
    }

    private String joinMessages(ValidationResult result) {
        if (result == null || result.messages() == null || result.messages().isEmpty()) {
            return "未提供原因。";
        }
        return String.join("; ", result.messages());
    }

    private ToolResult<String> errorResult(String toolUseId, String message) {
        String safeMessage = message == null || message.isBlank() ? "工具调用失败。" : message;
        AgentMessage agentMessage = new AgentMessage(
            "msg_" + toolUseId,
            MessageRole.TOOL_RESULT,
            MessageKind.TOOL_RESULT,
            List.of(new ToolResultContentBlock(toolUseId, safeMessage, true)),
            Instant.now(),
            Optional.empty(),
            Optional.empty()
        );
        return new ToolResult<>(safeMessage, true, List.of(agentMessage), Optional.empty());
    }

    @SuppressWarnings("unchecked")
    private Tool<Map<String, Object>, ?> castTool(Tool<?, ?> tool) {
        return (Tool<Map<String, Object>, ?>) tool;
    }

    private record IndexedCall(
        int index,
        ToolUseRequest request,
        String originalToolName,
        Tool<Map<String, Object>, ?> tool
    ) {}

    private record IndexedResult(
        int index,
        ToolResult<?> result
    ) {}

    private static final class BoundedVirtualExecutor implements ExecutorService, AutoCloseable {
        private final ExecutorService delegate;
        private final Semaphore semaphore;

        private BoundedVirtualExecutor(int maxConcurrency) {
            this.delegate = Executors.newVirtualThreadPerTaskExecutor();
            this.semaphore = new Semaphore(Math.max(1, maxConcurrency));
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(() -> {
                boolean acquired = false;
                try {
                    semaphore.acquire();
                    acquired = true;
                    command.run();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("工具并发执行被中断。", exception);
                } finally {
                    if (acquired) {
                        semaphore.release();
                    }
                }
            });
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
            return delegate.submit(task);
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(Runnable task, T result) {
            return delegate.submit(task, result);
        }

        @Override
        public java.util.concurrent.Future<?> submit(Runnable task) {
            return delegate.submit(task);
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(
            java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks
        ) throws InterruptedException {
            return delegate.invokeAll(tasks);
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(
            java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks,
            long timeout,
            java.util.concurrent.TimeUnit unit
        ) throws InterruptedException {
            return delegate.invokeAll(tasks, timeout, unit);
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks)
            throws InterruptedException, java.util.concurrent.ExecutionException {
            return delegate.invokeAny(tasks);
        }

        @Override
        public <T> T invokeAny(
            java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks,
            long timeout,
            java.util.concurrent.TimeUnit unit
        ) throws InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException {
            return delegate.invokeAny(tasks, timeout, unit);
        }
    }
}
