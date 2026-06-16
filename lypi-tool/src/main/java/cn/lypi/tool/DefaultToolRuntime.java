package cn.lypi.tool;

import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.runtime.SandboxPermissions;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.runtime.ToolRuntimeInvocation;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.BashRiskAnalysis;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.InterruptBehavior;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolExecutionStatus;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private static final String METADATA_ORIGINAL_TOOL_NAME = "originalToolName";
    private static final String METADATA_TOOL_USE_ID = "toolUseId";

    private final ToolRegistry registry;
    private final ToolSchemaValidator schemaValidator;
    private final ToolExecutionPlanner executionPlanner;
    private final ToolResultBudgeter resultBudgeter;
    private final ToolRuntimeContextFactory contextFactory;
    private final ToolExecutionInterceptor interceptor;
    private final SecurityRuntimePort securityRuntime;
    private final PermissionGate permissionGate;
    private final PermissionUpdateStore permissionUpdateStore;
    private final List<PermissionRule> runtimePermissionRules = new CopyOnWriteArrayList<>();
    private final ToolExecutionEventPublisher eventPublisher;
    private final ToolLifecycleReporter lifecycleReporter;
    private final ToolResultFactory resultFactory;
    private final SandboxEscalationPolicy sandboxEscalationPolicy;
    private final BashSandboxRiskPolicy bashSandboxRiskPolicy;
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
            ToolExecutionEventPublisher.noop(),
            options
        );
    }

    public DefaultToolRuntime(
        ToolRuntimeOptions options,
        SecurityRuntimePort securityRuntime,
        PermissionGate permissionGate,
        EventBus eventBus
    ) {
        this(
            new DefaultToolRegistry(),
            new ToolSchemaValidator(),
            new ToolExecutionPlanner(),
            new ToolResultBudgeter(),
            new ToolRuntimeContextFactory(normalizeOptions(options)),
            ToolExecutionInterceptors.noop(),
            securityRuntime,
            eventPublishingPermissionGate(eventBus, permissionGate),
            lifecyclePublisher(eventBus),
            normalizeOptions(options)
        );
    }

    public DefaultToolRuntime(
        ToolRuntimeOptions options,
        SecurityRuntimePort securityRuntime,
        PermissionResponseGate permissionResponseGate,
        EventBus eventBus
    ) {
        this(
            new DefaultToolRegistry(),
            new ToolSchemaValidator(),
            new ToolExecutionPlanner(),
            new ToolResultBudgeter(),
            new ToolRuntimeContextFactory(normalizeOptions(options)),
            ToolExecutionInterceptors.noop(),
            securityRuntime,
            eventPublishingPermissionGate(eventBus, permissionResponseGate),
            lifecyclePublisher(eventBus),
            normalizeOptions(options)
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
            ToolExecutionEventPublisher.noop(),
            ToolRuntimeOptions.defaults()
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
        PermissionResponseGate permissionResponseGate,
        EventBus eventBus,
        PermissionUpdateStore permissionUpdateStore
    ) {
        this(
            registry,
            schemaValidator,
            executionPlanner,
            resultBudgeter,
            contextFactory,
            interceptor,
            securityRuntime,
            eventPublishingPermissionGate(eventBus, permissionResponseGate),
            lifecyclePublisher(eventBus),
            ToolRuntimeOptions.defaults(),
            permissionUpdateStore
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
        PermissionResponseGate permissionResponseGate,
        EventBus eventBus
    ) {
        this(
            registry,
            schemaValidator,
            executionPlanner,
            resultBudgeter,
            contextFactory,
            interceptor,
            securityRuntime,
            eventPublishingPermissionGate(eventBus, permissionResponseGate),
            lifecyclePublisher(eventBus),
            ToolRuntimeOptions.defaults()
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
        PermissionGate permissionGate,
        EventBus eventBus
    ) {
        this(
            registry,
            schemaValidator,
            executionPlanner,
            resultBudgeter,
            contextFactory,
            interceptor,
            securityRuntime,
            eventPublishingPermissionGate(eventBus, permissionGate),
            lifecyclePublisher(eventBus),
            ToolRuntimeOptions.defaults()
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
            ToolExecutionEventPublisher.noop(),
            ToolRuntimeOptions.defaults()
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
        PermissionGate permissionGate,
        EventBus eventBus,
        PermissionUpdateStore permissionUpdateStore
    ) {
        this(
            registry,
            schemaValidator,
            executionPlanner,
            resultBudgeter,
            contextFactory,
            interceptor,
            securityRuntime,
            eventPublishingPermissionGate(eventBus, permissionGate),
            lifecyclePublisher(eventBus),
            ToolRuntimeOptions.defaults(),
            permissionUpdateStore
        );
    }

    DefaultToolRuntime(
        ToolRegistry registry,
        ToolSchemaValidator schemaValidator,
        ToolExecutionPlanner executionPlanner,
        ToolResultBudgeter resultBudgeter,
        ToolRuntimeContextFactory contextFactory,
        ToolExecutionInterceptor interceptor,
        SecurityRuntimePort securityRuntime,
        PermissionGate permissionGate,
        ToolExecutionEventPublisher eventPublisher
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
            eventPublisher,
            ToolRuntimeOptions.defaults(),
            PermissionUpdateStore.noop()
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
        ToolExecutionEventPublisher eventPublisher,
        ToolRuntimeOptions options
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
            eventPublisher,
            options,
            PermissionUpdateStore.noop()
        );
    }

    DefaultToolRuntime(
        ToolRegistry registry,
        ToolSchemaValidator schemaValidator,
        ToolExecutionPlanner executionPlanner,
        ToolResultBudgeter resultBudgeter,
        ToolRuntimeContextFactory contextFactory,
        ToolExecutionInterceptor interceptor,
        SecurityRuntimePort securityRuntime,
        PermissionGate permissionGate,
        ToolExecutionEventPublisher eventPublisher,
        PermissionUpdateStore permissionUpdateStore
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
            eventPublisher,
            ToolRuntimeOptions.defaults(),
            permissionUpdateStore
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
        ToolExecutionEventPublisher eventPublisher,
        ToolRuntimeOptions options,
        PermissionUpdateStore permissionUpdateStore
    ) {
        ToolRuntimeOptions normalizedOptions = normalizeOptions(options);
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.schemaValidator = Objects.requireNonNull(schemaValidator, "schemaValidator must not be null");
        this.executionPlanner = Objects.requireNonNull(executionPlanner, "executionPlanner must not be null");
        this.resultBudgeter = Objects.requireNonNull(resultBudgeter, "resultBudgeter must not be null");
        this.contextFactory = Objects.requireNonNull(contextFactory, "contextFactory must not be null");
        this.interceptor = interceptor == null ? ToolExecutionInterceptors.noop() : interceptor;
        this.securityRuntime = Objects.requireNonNull(securityRuntime, "securityRuntime must not be null");
        this.permissionGate = permissionGate == null ? PermissionGate.denying() : permissionGate;
        this.permissionUpdateStore = permissionUpdateStore == null ? PermissionUpdateStore.noop() : permissionUpdateStore;
        this.eventPublisher = eventPublisher == null ? ToolExecutionEventPublisher.noop() : eventPublisher;
        this.lifecycleReporter = new ToolLifecycleReporter(this.eventPublisher);
        this.resultFactory = new ToolResultFactory();
        this.sandboxEscalationPolicy = new SandboxEscalationPolicy();
        this.bashSandboxRiskPolicy = new BashSandboxRiskPolicy();
        this.maxConcurrency = normalizedOptions.maxConcurrency();
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

    @Override
    public Path cwd() {
        return contextFactory.cwd();
    }

    /**
     * 编排并执行一组工具调用。
     *
     * 返回顺序始终与请求顺序一致。
     */
    @Override
    public List<ToolResult<?>> execute(List<ToolUseRequest> requests, ContextSnapshot context) {
        return execute(requests, context, null);
    }

    @Override
    public List<ToolResult<?>> execute(
        List<ToolUseRequest> requests,
        ContextSnapshot context,
        ToolRuntimeInvocation invocation
    ) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        List<ToolResult<?>> results = new ArrayList<>(requests.size());
        for (int index = 0; index < requests.size(); index++) {
            results.add(null);
        }

        List<IndexedCall> resolvedSegment = new ArrayList<>();
        for (int index = 0; index < requests.size(); index++) {
            ToolUseRequest request = requests.get(index);
            Optional<Tool<?, ?>> tool = registry.resolve(request.toolName());
            if (tool.isEmpty()) {
                executeResolvedSegment(resolvedSegment, context, invocation, results);
                resolvedSegment.clear();
                results.set(index, executeUnknownCall(request, context, invocation));
                continue;
            }
            Tool<Map<String, Object>, ?> resolvedTool = castTool(tool.get());
            resolvedSegment.add(new IndexedCall(index, canonicalRequest(request, resolvedTool), request.toolName(), resolvedTool));
        }
        executeResolvedSegment(resolvedSegment, context, invocation, results);
        return List.copyOf(results);
    }

    private void executeResolvedSegment(
        List<IndexedCall> resolvedCalls,
        ContextSnapshot context,
        ToolRuntimeInvocation invocation,
        List<ToolResult<?>> results
    ) {
        if (resolvedCalls.isEmpty()) {
            return;
        }
        List<ToolExecutionPlanner.ResolvedToolCall> calls = resolvedCalls.stream()
            .map(call -> new ToolExecutionPlanner.ResolvedToolCall(call.request(), call.tool()))
            .toList();
        List<ToolExecutionPlanner.Batch> batches = executionPlanner.plan(calls);
        int cursor = 0;
        for (ToolExecutionPlanner.Batch batch : batches) {
            List<IndexedCall> indexedBatch = resolvedCalls.subList(cursor, cursor + batch.calls().size());
            executeBatch(batch, indexedBatch, context, invocation, results);
            cursor += batch.calls().size();
        }
    }

    private void executeBatch(
        ToolExecutionPlanner.Batch batch,
        List<IndexedCall> indexedBatch,
        ContextSnapshot context,
        ToolRuntimeInvocation invocation,
        List<ToolResult<?>> results
    ) {
        if (!batch.parallel()) {
            IndexedCall indexedCall = indexedBatch.getFirst();
            results.set(indexedCall.index(), executeCall(
                indexedCall.request(),
                indexedCall.originalToolName(),
                indexedCall.tool(),
                context,
                invocation
            ));
            return;
        }

        try (BoundedVirtualExecutor executor = new BoundedVirtualExecutor(maxConcurrency)) {
            List<CompletableFuture<IndexedResult>> futures = indexedBatch.stream()
                .map(call -> CompletableFuture.supplyAsync(
                    () -> new IndexedResult(call.index(), executeCall(
                        call.request(),
                        call.originalToolName(),
                        call.tool(),
                        context,
                        invocation
                    )),
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
        ContextSnapshot context,
        ToolRuntimeInvocation invocation
    ) {
        Map<String, Object> input = request.input() == null ? Map.of() : request.input();
        ToolUseContext toolContext = contextWithCallMetadata(
            contextFactory.create(request, context, invocation),
            request.toolUseId(),
            originalToolName,
            request.toolName()
        );
        return executeStartedCall(request, originalToolName, tool.name(), tool, input, toolContext);
    }

    private ToolResult<?> executeUnknownCall(
        ToolUseRequest request,
        ContextSnapshot context,
        ToolRuntimeInvocation invocation
    ) {
        Map<String, Object> input = request.input() == null ? Map.of() : request.input();
        String toolName = request.toolName();
        ToolUseContext toolContext = contextWithCallMetadata(
            contextFactory.create(request, context, invocation),
            request.toolUseId(),
            toolName,
            toolName
        );
        ToolExecutionEventPublisher.StartedToolExecution started = lifecycleReporter.start(
            request,
            toolContext,
            toolName,
            toolName,
            input
        );
        ToolResult<?> finalResult = null;
        try {
            finalResult = errorResult(request.toolUseId(), "未知工具: " + toolName);
            return finalResult;
        } catch (RuntimeException exception) {
            finalResult = errorResult(request.toolUseId(), "工具执行失败: " + exception.getMessage());
            return finalResult;
        } finally {
            lifecycleReporter.end(
                request,
                toolContext,
                toolName,
                toolName,
                finalResult,
                finalResult,
                ToolExecutionStatus.FAILED,
                started.startedAt()
            );
        }
    }

    private ToolResult<?> executeStartedCall(
        ToolUseRequest request,
        String originalToolName,
        String toolName,
        Tool<Map<String, Object>, ?> tool,
        Map<String, Object> input,
        ToolUseContext toolContext
    ) {
        ToolExecutionEventPublisher.StartedToolExecution started = lifecycleReporter.start(
            request,
            toolContext,
            toolName,
            originalToolName,
            input
        );
        ToolResult<?> rawResult = null;
        ToolResult<?> finalResult = null;
        ToolExecutionStatus status = ToolExecutionStatus.FAILED;
        try {
            ValidationResult schemaResult = schemaValidator.validate(tool.inputSchema(), input);
            if (!schemaResult.valid()) {
                finalResult = errorResult(request.toolUseId(), "工具输入 schema 校验失败: " + joinMessages(schemaResult));
                return finalResult;
            }

            ValidationResult inputResult = tool.validateInput(input, toolContext);
            if (inputResult != null && !inputResult.valid()) {
                finalResult = errorResult(request.toolUseId(), "工具输入校验失败: " + joinMessages(inputResult));
                return finalResult;
            }
            if (isPlanMode(toolContext) && !tool.isReadOnly(input)) {
                finalResult = errorResult(
                    request.toolUseId(),
                    "AgentMode.PLAN 禁止执行非只读工具: " + tool.name()
                );
                return finalResult;
            }

            PermissionDecision securityDecision = securityRuntime.decide(request, toolContext);
            PermissionDecision effectiveDecision;
            if (isDefaultSandboxBashRequest(request) && canEnterDefaultSandbox(securityDecision)) {
                effectiveDecision = isDeny(securityDecision)
                    ? securityDecision
                    : allowDecision("默认 Bash 请求先进入沙箱执行。");
            } else {
                PermissionDecision toolDecision = tool.checkPermissions(input, toolContext);
                effectiveDecision = effectiveDecision(toolDecision, securityDecision);
            }
            Optional<PermissionDecision> sandboxEscalationDecision = sandboxEscalationPolicy.decide(request, toolContext);
            if (sandboxEscalationDecision.isPresent()) {
                PermissionDecision sandboxDecision = withSuggestedUpdate(
                    sandboxEscalationDecision.get(),
                    securityDecision.suggestedUpdate()
                );
                effectiveDecision = effectiveDecision(
                    isDeny(effectiveDecision) ? effectiveDecision : allowDecision("允许进入沙箱提权审批。"),
                    sandboxDecision
                );
            } else if (!isDeny(effectiveDecision)) {
                Optional<PermissionDecision> bashSandboxRiskDecision = bashSandboxRiskPolicy.decide(request, toolContext, securityDecision);
                if (bashSandboxRiskDecision.isPresent()) {
                    effectiveDecision = bashSandboxRiskDecision.get();
                }
            }
            if (isDeny(effectiveDecision)) {
                finalResult = permissionGateError(request.toolUseId(), PermissionGateResult.deny(decisionMessage(effectiveDecision)));
                return finalResult;
            }
            PermissionGateResult permissionResult = resolvePermission(request, tool, toolContext, effectiveDecision);
            if (permissionResult.status() != PermissionGateResult.Status.ALLOW) {
                status = statusForGateResult(permissionResult);
                finalResult = permissionGateError(request.toolUseId(), permissionResult);
                return finalResult;
            }
            permissionResult.permissionUpdate().ifPresent(this::applyPermissionUpdate);
            ToolExecutionInterceptor.BeforeResult beforeResult = interceptor.beforeExecute(request, tool, toolContext);
            if (beforeResult != null && beforeResult.blocked()) {
                finalResult = errorResult(request.toolUseId(), beforeResult.message());
                return finalResult;
            }
            if (shouldSkipForAbort(tool, toolContext)) {
                status = ToolExecutionStatus.CANCELLED;
                finalResult = errorResult(request.toolUseId(), "工具调用已中止。", status);
                return finalResult;
            }
            ExecutedToolResult execution = executeTool(request, tool, input, toolContext, started.progressSink());
            rawResult = applyAfterInterceptor(request, tool, toolContext, execution.result());
            finalResult = resultBudgeter.apply(request.toolUseId(), tool.name(), rawResult, tool.maxResultSize());
            status = execution.threw() || finalResult.isError()
                ? ToolExecutionStatus.FAILED
                : ToolExecutionStatus.SUCCEEDED;
            return finalResult;
        } catch (RuntimeException exception) {
            finalResult = errorResult(request.toolUseId(), "工具执行失败: " + exception.getMessage());
            rawResult = finalResult;
            status = ToolExecutionStatus.FAILED;
            return finalResult;
        } finally {
            lifecycleReporter.end(request, toolContext, toolName, originalToolName, rawResult, finalResult, status, started.startedAt());
        }
    }

    private ToolExecutionStatus statusForGateResult(PermissionGateResult result) {
        return result.status() == PermissionGateResult.Status.ABORT
            ? ToolExecutionStatus.CANCELLED
            : ToolExecutionStatus.FAILED;
    }

    private boolean isPlanMode(ToolUseContext context) {
        Object value = context.metadata().get(ToolRuntimeContextFactory.METADATA_AGENT_MODE);
        if (value instanceof AgentMode agentMode) {
            return agentMode == AgentMode.PLAN;
        }
        if (value instanceof String agentMode) {
            return AgentMode.valueOf(agentMode) == AgentMode.PLAN;
        }
        return false;
    }

    private ExecutedToolResult executeTool(
        ToolUseRequest request,
        Tool<Map<String, Object>, ?> tool,
        Map<String, Object> input,
        ToolUseContext toolContext,
        ProgressSink progress
    ) {
        try {
            return new ExecutedToolResult(tool.execute(input, toolContext, progress), false);
        } catch (RuntimeException exception) {
            return new ExecutedToolResult(errorResult(request.toolUseId(), "工具执行失败: " + exception.getMessage()), true);
        }
    }

    private ToolResult<?> applyAfterInterceptor(
        ToolUseRequest request,
        Tool<Map<String, Object>, ?> tool,
        ToolUseContext toolContext,
        ToolResult<?> result
    ) {
        ToolResult<?> interceptedResult = interceptor.afterExecute(request, tool, toolContext, result);
        return interceptedResult == null ? result : interceptedResult;
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
        if (!runtimePermissionRules.isEmpty()) {
            List<PermissionRule> effectiveRules = new ArrayList<>();
            Object metadataRules = metadata.get("permissionRules");
            if (metadataRules instanceof Iterable<?> iterable) {
                for (Object candidate : iterable) {
                    if (candidate instanceof PermissionRule rule) {
                        effectiveRules.add(rule);
                    }
                }
            }
            effectiveRules.addAll(runtimePermissionRules);
            metadata.put("permissionRules", List.copyOf(effectiveRules));
        }
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

    private void applyPermissionUpdate(PermissionUpdate update) {
        permissionUpdateStore.append(update);
        if (update != null && update.rule() != null) {
            runtimePermissionRules.add(update.rule());
        }
    }

    private static PermissionGate eventPublishingPermissionGate(EventBus eventBus, PermissionGate permissionGate) {
        PermissionGate safeGate = permissionGate == null ? PermissionGate.denying() : permissionGate;
        return eventBus == null || safeGate instanceof EventPublishingPermissionGate
            ? safeGate
            : new EventPublishingPermissionGate(eventBus, safeGate);
    }

    private static PermissionGate eventPublishingPermissionGate(EventBus eventBus, PermissionResponseGate permissionResponseGate) {
        return eventBus == null
            ? PermissionGate.denying()
            : new EventPublishingPermissionGate(eventBus, permissionResponseGate);
    }

    private static ToolExecutionEventPublisher lifecyclePublisher(EventBus eventBus) {
        return eventBus == null ? ToolExecutionEventPublisher.noop() : ToolExecutionEventPublisher.eventBus(eventBus);
    }

    private boolean shouldSkipForAbort(Tool<Map<String, Object>, ?> tool, ToolUseContext context) {
        return ToolAbortSupport.aborted(context) && tool.interruptBehavior() == InterruptBehavior.CANCEL;
    }

    private boolean isDefaultSandboxBashRequest(ToolUseRequest request) {
        return request != null
            && "bash".equals(request.toolName())
            && !hasPrefixRule(request.input())
            && SandboxPermissions.fromToolValue(stringInput(request.input(), "sandboxPermissions")) == SandboxPermissions.USE_DEFAULT;
    }

    private boolean canEnterDefaultSandbox(PermissionDecision securityDecision) {
        if (securityDecision == null || securityDecision.behavior() != PermissionBehavior.ASK) {
            return true;
        }
        Object bashRisk = securityDecision.metadata().get("bashRisk");
        return !(bashRisk instanceof BashRiskAnalysis risk) || risk.redirectTargets().isEmpty();
    }

    private boolean hasPrefixRule(Map<String, Object> input) {
        return input != null && input.containsKey("prefix_rule");
    }

    private String stringInput(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        return value == null ? "" : value.toString();
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
        if (decision.behavior() == PermissionBehavior.ASK && isBypassPermissionMode(context)) {
            return PermissionGateResult.allow();
        }
        PermissionGateResult result = permissionGate.request(request, tool, context, decision);
        return result == null ? PermissionGateResult.deny("权限请求未获允许。") : result;
    }

    private boolean isBypassPermissionMode(ToolUseContext context) {
        Object value = context.metadata().get(ToolRuntimeContextFactory.METADATA_PERMISSION_MODE);
        if (value instanceof PermissionMode permissionMode) {
            return permissionMode == PermissionMode.BYPASS;
        }
        if (value instanceof String permissionMode) {
            return PermissionMode.valueOf(permissionMode) == PermissionMode.BYPASS;
        }
        return false;
    }

    private PermissionDecision effectiveDecision(PermissionDecision toolDecision, PermissionDecision securityDecision) {
        if (isDeny(securityDecision)) {
            return securityDecision;
        }
        if (isDeny(toolDecision)) {
            return toolDecision;
        }
        if (isAsk(securityDecision)) {
            return securityDecision;
        }
        if (isExplicitRuleAllow(securityDecision)) {
            return securityDecision;
        }
        if (isAsk(toolDecision)) {
            return toolDecision;
        }
        return securityDecision == null ? toolDecision : securityDecision;
    }

    private boolean isDeny(PermissionDecision decision) {
        return decision == null || decision.behavior() == PermissionBehavior.DENY;
    }

    private boolean isAsk(PermissionDecision decision) {
        return decision != null && decision.behavior() == PermissionBehavior.ASK;
    }

    private boolean isExplicitRuleAllow(PermissionDecision decision) {
        return decision != null
            && decision.behavior() == PermissionBehavior.ALLOW
            && decision.reason() == PermissionDecisionReason.EXPLICIT_RULE;
    }

    private PermissionDecision allowDecision(String message) {
        return new PermissionDecision(
            PermissionBehavior.ALLOW,
            PermissionDecisionReason.MODE_DEFAULT,
            message,
            Optional.empty(),
            Map.of()
        );
    }

    private PermissionDecision withSuggestedUpdate(
        PermissionDecision decision,
        Optional<PermissionUpdate> suggestedUpdate
    ) {
        if (decision == null || suggestedUpdate == null || suggestedUpdate.isEmpty() || decision.suggestedUpdate().isPresent()) {
            return decision;
        }
        return new PermissionDecision(
            decision.behavior(),
            decision.reason(),
            decision.message(),
            suggestedUpdate,
            decision.metadata()
        );
    }

    private ToolResult<?> permissionGateError(String toolUseId, PermissionGateResult result) {
        String message = result.message().orElse("权限请求未获允许。");
        if (result.status() == PermissionGateResult.Status.ABORT) {
            return errorResult(toolUseId, "工具权限请求已中断: " + message, ToolExecutionStatus.CANCELLED);
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
        return resultFactory.error(toolUseId, message);
    }

    private ToolResult<String> errorResult(String toolUseId, String message, ToolExecutionStatus status) {
        return resultFactory.error(toolUseId, message, status);
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

    private record ExecutedToolResult(ToolResult<?> result, boolean threw) {}

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
