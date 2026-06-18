package cn.lypi.tool;

import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.runtime.ToolRuntimeInvocation;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.FileSystemPermissionEntry;
import cn.lypi.contracts.security.FileSystemPermissionPolicy;
import cn.lypi.contracts.security.FileSystemPolicyKind;
import cn.lypi.contracts.security.NetworkPermissionPolicy;
import cn.lypi.contracts.security.NetworkPolicyMode;
import cn.lypi.contracts.security.PermissionGrantScope;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.RequestPermissionsResponse;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认工具运行时。
 *
 * NOTE: 运行时负责串接注册表、校验、权限、拦截、执行规划和结果预算。
 */
public final class DefaultToolRuntime implements ToolRuntimePort, ToolOrchestrator {
    private static final String METADATA_ORIGINAL_TOOL_NAME = "originalToolName";
    private static final String METADATA_TOOL_USE_ID = "toolUseId";
    private static final String METADATA_ADDITIONAL_PERMISSIONS = "additionalPermissions";
    private static final String METADATA_APPROVED_ADDITIONAL_PERMISSIONS = "approvedAdditionalPermissions";
    private static final String REQUEST_PERMISSIONS_TOOL = "request_permissions";

    private final ToolRegistry registry;
    private final ToolCallResolver callResolver;
    private final ToolSchemaValidator schemaValidator;
    private final ToolExecutionPlanner executionPlanner;
    private final ToolResultBudgeter resultBudgeter;
    private final ToolRuntimeContextFactory contextFactory;
    private final ToolExecutionInterceptor interceptor;
    private final SecurityRuntimePort securityRuntime;
    private final PermissionGate permissionGate;
    private final PermissionUpdateStore permissionUpdateStore;
    private final RuntimePermissionRuleStore runtimePermissionRules = new RuntimePermissionRuleStore();
    private final ToolExecutionEventPublisher eventPublisher;
    private final ToolLifecycleReporter lifecycleReporter;
    private final ToolResultFactory resultFactory;
    private final ToolPermissionCoordinator permissionCoordinator;
    private final ToolBatchExecutor batchExecutor;
    private final Map<String, TurnPermissionState> turnPermissionStates = new ConcurrentHashMap<>();
    private final Map<String, AdditionalPermissionProfile> sessionAdditionalPermissions = new ConcurrentHashMap<>();
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
        this.callResolver = new ToolCallResolver(this.registry);
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
        this.permissionCoordinator = new ToolPermissionCoordinator(
            this.securityRuntime,
            this.permissionGate,
            this.permissionUpdateStore,
            this.runtimePermissionRules,
            new SandboxEscalationPolicy(),
            new BashSandboxRiskPolicy()
        );
        this.maxConcurrency = normalizedOptions.maxConcurrency();
        this.batchExecutor = new ToolBatchExecutor(this.maxConcurrency);
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

        TurnPermissionState turnState = turnState(invocation);
        List<ToolCallResolver.ResolvedCall> resolvedSegment = new ArrayList<>();
        for (ToolCallResolver.ResolvedCall call : callResolver.resolve(requests)) {
            if (!call.known()) {
                executeResolvedSegment(resolvedSegment, context, invocation, results, turnState);
                resolvedSegment.clear();
                results.set(call.index(), executeUnknownCall(call.request(), context, invocation, turnState));
                continue;
            }
            resolvedSegment.add(call);
        }
        executeResolvedSegment(resolvedSegment, context, invocation, results, turnState);
        return List.copyOf(results);
    }

    private void executeResolvedSegment(
        List<ToolCallResolver.ResolvedCall> resolvedCalls,
        ContextSnapshot context,
        ToolRuntimeInvocation invocation,
        List<ToolResult<?>> results,
        TurnPermissionState turnState
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
            List<ToolCallResolver.ResolvedCall> indexedBatch = resolvedCalls.subList(cursor, cursor + batch.calls().size());
            executeBatch(batch, indexedBatch, context, invocation, results, turnState);
            cursor += batch.calls().size();
        }
    }

    private void executeBatch(
        ToolExecutionPlanner.Batch batch,
        List<ToolCallResolver.ResolvedCall> indexedBatch,
        ContextSnapshot context,
        ToolRuntimeInvocation invocation,
        List<ToolResult<?>> results,
        TurnPermissionState turnState
    ) {
        List<ToolResult<?>> batchResults = batchExecutor.execute(batch, (index, call) -> {
            ToolCallResolver.ResolvedCall indexedCall = indexedBatch.get(index);
            return executeCall(
                indexedCall.request(),
                indexedCall.originalToolName(),
                indexedCall.tool(),
                context,
                invocation,
                turnState
            );
        });
        for (int index = 0; index < batchResults.size(); index++) {
            results.set(indexedBatch.get(index).index(), batchResults.get(index));
        }
    }

    private ToolResult<?> executeCall(
        ToolUseRequest request,
        String originalToolName,
        Tool<Map<String, Object>, ?> tool,
        ContextSnapshot context,
        ToolRuntimeInvocation invocation,
        TurnPermissionState turnState
    ) {
        Map<String, Object> input = request.input() == null ? Map.of() : request.input();
        ToolUseContext toolContext = contextWithCallMetadata(
            contextFactory.create(request, context, invocation),
            request.toolUseId(),
            originalToolName,
            request.toolName(),
            turnState
        );
        return executeStartedCall(request, originalToolName, tool.name(), tool, input, toolContext, turnState);
    }

    private ToolResult<?> executeUnknownCall(
        ToolUseRequest request,
        ContextSnapshot context,
        ToolRuntimeInvocation invocation,
        TurnPermissionState turnState
    ) {
        Map<String, Object> input = request.input() == null ? Map.of() : request.input();
        String toolName = request.toolName();
        ToolUseContext toolContext = contextWithCallMetadata(
            contextFactory.create(request, context, invocation),
            request.toolUseId(),
            toolName,
            toolName,
            turnState
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
        ToolUseContext toolContext,
        TurnPermissionState turnState
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

            ToolPermissionCoordinator.Result permissionResult = permissionCoordinator.authorize(
                request,
                tool,
                input,
                toolContext
            );
            if (!permissionResult.allowed()) {
                status = statusForGateResult(permissionResult.gateResult());
                finalResult = permissionGateError(request.toolUseId(), permissionResult.gateResult());
                return finalResult;
            }
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
            recordTurnState(tool.name(), finalResult, turnState, toolContext);
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

    private ToolUseContext contextWithCallMetadata(
        ToolUseContext context,
        String toolUseId,
        String originalToolName,
        String canonicalToolName,
        TurnPermissionState turnState
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>(context.metadata());
        metadata.put(METADATA_TOOL_USE_ID, toolUseId);
        metadata.remove(METADATA_ADDITIONAL_PERMISSIONS);
        metadata.remove(METADATA_APPROVED_ADDITIONAL_PERMISSIONS);
        if (turnState != null && turnState.strictAutoReview()) {
            metadata.put("strictAutoReview", true);
        }
        AdditionalPermissionProfile additionalPermissions = effectiveAdditionalPermissions(context.sessionId(), turnState, metadata);
        if (!isEmptyAdditionalPermissions(additionalPermissions)) {
            metadata.put(METADATA_ADDITIONAL_PERMISSIONS, additionalPermissions);
            metadata.put(METADATA_APPROVED_ADDITIONAL_PERMISSIONS, true);
        }
        List<PermissionRule> sessionRules = runtimePermissionRules.rulesFor(context.sessionId());
        if (!sessionRules.isEmpty()) {
            List<PermissionRule> effectiveRules = new ArrayList<>();
            Object metadataRules = metadata.get("permissionRules");
            if (metadataRules instanceof Iterable<?> iterable) {
                for (Object candidate : iterable) {
                    if (candidate instanceof PermissionRule rule) {
                        effectiveRules.add(rule);
                    }
                }
            }
            effectiveRules.addAll(sessionRules);
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

    private void recordTurnState(
        String toolName,
        ToolResult<?> result,
        TurnPermissionState turnState,
        ToolUseContext context
    ) {
        if (turnState == null || result == null || result.isError()) {
            return;
        }
        if (REQUEST_PERMISSIONS_TOOL.equals(toolName) && result.output() instanceof RequestPermissionsResponse response) {
            if (response.strictAutoReview()) {
                turnState.enableStrictAutoReview();
            }
            AdditionalPermissionProfile additionalPermissions = response.permissions().additionalPermissions();
            if (isEmptyAdditionalPermissions(additionalPermissions)) {
                return;
            }
            if (response.scope() == PermissionGrantScope.SESSION) {
                sessionAdditionalPermissions.merge(
                    context.sessionId(),
                    additionalPermissions,
                    this::mergeAdditionalPermissions
                );
            } else {
                turnState.addAdditionalPermissions(additionalPermissions);
            }
        }
    }

    private TurnPermissionState turnState(ToolRuntimeInvocation invocation) {
        if (invocation == null || isBlank(invocation.sessionId()) || isBlank(invocation.turnId())) {
            return new TurnPermissionState();
        }
        return turnPermissionStates.computeIfAbsent(
            invocation.sessionId() + "\n" + invocation.turnId(),
            ignored -> new TurnPermissionState()
        );
    }

    private AdditionalPermissionProfile effectiveAdditionalPermissions(
        String sessionId,
        TurnPermissionState turnState,
        Map<String, Object> metadata
    ) {
        AdditionalPermissionProfile session = isBlank(sessionId)
            ? AdditionalPermissionProfile.empty()
            : sessionAdditionalPermissions.getOrDefault(sessionId, AdditionalPermissionProfile.empty());
        AdditionalPermissionProfile turn = turnState == null
            ? AdditionalPermissionProfile.empty()
            : turnState.additionalPermissions();
        return mergeAdditionalPermissions(session, turn);
    }

    private AdditionalPermissionProfile mergeAdditionalPermissions(
        AdditionalPermissionProfile first,
        AdditionalPermissionProfile second
    ) {
        AdditionalPermissionProfile left = first == null ? AdditionalPermissionProfile.empty() : first;
        AdditionalPermissionProfile right = second == null ? AdditionalPermissionProfile.empty() : second;
        return new AdditionalPermissionProfile(
            mergeFileSystem(left.fileSystem(), right.fileSystem()),
            mergeNetwork(left.network(), right.network())
        );
    }

    private Optional<FileSystemPermissionPolicy> mergeFileSystem(
        Optional<FileSystemPermissionPolicy> first,
        Optional<FileSystemPermissionPolicy> second
    ) {
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }
        FileSystemPermissionPolicy left = first.orElseThrow();
        FileSystemPermissionPolicy right = second.orElseThrow();
        if (left.kind() != right.kind()) {
            return second;
        }
        if (left.kind() != FileSystemPolicyKind.RESTRICTED) {
            return first;
        }
        List<FileSystemPermissionEntry> entries = new ArrayList<>(left.entries());
        entries.addAll(right.entries());
        return Optional.of(FileSystemPermissionPolicy.restricted(entries));
    }

    private Optional<NetworkPermissionPolicy> mergeNetwork(
        Optional<NetworkPermissionPolicy> first,
        Optional<NetworkPermissionPolicy> second
    ) {
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }
        if (first.orElseThrow().mode() == NetworkPolicyMode.ENABLED || second.orElseThrow().mode() == NetworkPolicyMode.ENABLED) {
            return Optional.of(NetworkPermissionPolicy.enabled());
        }
        return first;
    }

    private boolean isEmptyAdditionalPermissions(AdditionalPermissionProfile additionalPermissions) {
        AdditionalPermissionProfile safe = additionalPermissions == null
            ? AdditionalPermissionProfile.empty()
            : additionalPermissions;
        return safe.fileSystem().isEmpty() && safe.network().isEmpty();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ToolRuntimeOptions normalizeOptions(ToolRuntimeOptions options) {
        return options == null ? ToolRuntimeOptions.defaults() : options;
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

    private ToolResult<?> permissionGateError(String toolUseId, PermissionGateResult result) {
        String message = result.message().orElse("权限请求未获允许。");
        if (result.status() == PermissionGateResult.Status.ABORT) {
            return errorResult(toolUseId, "工具权限请求已中断: " + message, ToolExecutionStatus.CANCELLED);
        }
        return errorResult(toolUseId, "权限请求未获允许: " + message);
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

    private record ExecutedToolResult(ToolResult<?> result, boolean threw) {}

    private final class TurnPermissionState {
        private boolean strictAutoReview;
        private AdditionalPermissionProfile additionalPermissions = AdditionalPermissionProfile.empty();

        private boolean strictAutoReview() {
            return strictAutoReview;
        }

        private void enableStrictAutoReview() {
            strictAutoReview = true;
        }

        private AdditionalPermissionProfile additionalPermissions() {
            return additionalPermissions;
        }

        private void addAdditionalPermissions(AdditionalPermissionProfile additionalPermissions) {
            this.additionalPermissions = mergeAdditionalPermissions(this.additionalPermissions, additionalPermissions);
        }
    }

}
