package cn.lypi.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventConsumer;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.event.PermissionRequestEvent;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.runtime.ExecutionRequest;
import cn.lypi.contracts.runtime.ExecutionResult;
import cn.lypi.contracts.runtime.Executor;
import cn.lypi.contracts.security.ActivePermissionProfile;
import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.ApprovalKind;
import cn.lypi.contracts.security.ApprovalMode;
import cn.lypi.contracts.security.ApprovalPolicy;
import cn.lypi.contracts.security.FileSystemAccessMode;
import cn.lypi.contracts.security.FileSystemPath;
import cn.lypi.contracts.security.FileSystemPermissionEntry;
import cn.lypi.contracts.security.FileSystemPermissionPolicy;
import cn.lypi.contracts.security.LegacyPermissionBehavior;
import cn.lypi.contracts.security.NetworkPermissionPolicy;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionGrantScope;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionResponse;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.security.RequestPermissionProfile;
import cn.lypi.contracts.security.RequestPermissionsResponse;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseRequest;
import cn.lypi.contracts.web.WebSearchResponse;
import cn.lypi.contracts.web.WebSearchResult;
import cn.lypi.tool.DefaultToolRuntime;
import cn.lypi.tool.PermissionResponseGate;
import cn.lypi.tool.ToolRuntimeOptions;
import cn.lypi.tool.web.WebProviderRegistry;
import cn.lypi.tool.web.WebSearchProvider;
import cn.lypi.tool.web.WebSearchRequest;
import cn.lypi.tool.web.WebSearchTool;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RequestPermissionsToolTest {
    @TempDir
    Path tempDir;

    @Test
    void inputSchemaDescribesApprovalPolicyAndAdditionalPermissionsFlow() {
        RequestPermissionsTool tool = new RequestPermissionsTool();

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().value().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> permissions = (Map<String, Object>) properties.get("permissions");
        @SuppressWarnings("unchecked")
        Map<String, Object> permissionProperties = (Map<String, Object>) permissions.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> fileSystem = (Map<String, Object>) permissionProperties.get("fileSystem");
        @SuppressWarnings("unchecked")
        Map<String, Object> filesystemAlias = (Map<String, Object>) permissionProperties.get("filesystem");
        @SuppressWarnings("unchecked")
        Map<String, Object> fileSystemProperties = (Map<String, Object>) fileSystem.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> entries = (Map<String, Object>) fileSystemProperties.get("entries");
        @SuppressWarnings("unchecked")
        Map<String, Object> entryItems = (Map<String, Object>) entries.get("items");
        @SuppressWarnings("unchecked")
        Map<String, Object> entryProperties = (Map<String, Object>) entryItems.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> network = (Map<String, Object>) permissionProperties.get("network");
        @SuppressWarnings("unchecked")
        Map<String, Object> networkProperties = (Map<String, Object>) network.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> kind = (Map<String, Object>) fileSystemProperties.get("kind");
        @SuppressWarnings("unchecked")
        Map<String, Object> entryAccess = (Map<String, Object>) entryProperties.get("access");
        @SuppressWarnings("unchecked")
        Map<String, Object> shorthandAccess = (Map<String, Object>) fileSystemProperties.get("access");
        @SuppressWarnings("unchecked")
        Map<String, Object> networkMode = (Map<String, Object>) networkProperties.get("mode");

        assertTrue(tool.description().contains("ASK"));
        assertTrue(tool.description().contains("AUTO"));
        assertTrue(tool.description().contains("BYPASS"));
        assertTrue(properties.get("permissions").toString().contains("additional filesystem or network permissions"));
        assertTrue(permissionProperties.containsKey("fileSystem"));
        assertTrue(permissionProperties.containsKey("filesystem"));
        assertEquals(fileSystem, filesystemAlias);
        assertEquals(List.of("RESTRICTED"), kind.get("enum"));
        assertTrue(entryProperties.containsKey("path"));
        assertEquals(List.of("READ", "WRITE"), entryAccess.get("enum"));
        assertTrue(fileSystemProperties.containsKey("paths"));
        assertEquals(List.of("READ", "WRITE"), shorthandAccess.get("enum"));
        assertTrue(permissionProperties.containsKey("network"));
        assertEquals(List.of("ENABLED", "RESTRICTED"), networkMode.get("enum"));
        assertTrue(properties.get("scope").toString().contains("turn"));
        assertTrue(properties.get("scope").toString().contains("session"));
        assertTrue(properties.get("strictAutoReview").toString().contains("following command should still be reviewed"));
    }

    @Test
    void emptyPermissionsRequestReturnsToolErrorWithoutPrompt() {
        AtomicInteger prompts = new AtomicInteger();
        DefaultToolRuntime runtime = runtime(
            context -> allow(),
            requestEvent -> {
                prompts.incrementAndGet();
                return approve(requestEvent);
            }
        );
        runtime.register(new RequestPermissionsTool());

        ToolResult<?> result = executeOne(runtime, input(Map.of("permissions", Map.of())));

        assertTrue(result.isError());
        assertTextContains(result, "permissions 不能为空");
        assertEquals(0, prompts.get());
    }

    @Test
    void planModeRejectsRequestPermissionsBeforePrompt() {
        AtomicInteger prompts = new AtomicInteger();
        DefaultToolRuntime runtime = runtime(
            context -> allow(),
            requestEvent -> {
                prompts.incrementAndGet();
                return approve(requestEvent);
            }
        );
        runtime.register(new RequestPermissionsTool());

        ToolResult<?> result = executeOne(runtime, AgentMode.PLAN, runtimeState(ApprovalMode.ON_REQUEST), input(fileSystemRequest()));

        assertTrue(result.isError());
        assertTextContains(result, "AgentMode.PLAN 禁止执行非只读工具: request_permissions");
        assertEquals(0, prompts.get());
    }

    @Test
    void askModePromptsRegardlessOfLegacyApprovalPolicy() {
        AtomicInteger prompts = new AtomicInteger();
        DefaultToolRuntime runtime = runtime(
            context -> allow(),
            requestEvent -> {
                prompts.incrementAndGet();
                return approve(requestEvent);
            }
        );
        runtime.register(new RequestPermissionsTool());

        ToolResult<?> result = executeOne(runtime, AgentMode.EXECUTE, runtimeState(ApprovalMode.NEVER), input(fileSystemRequest()));

        assertFalse(result.isError());
        assertEquals(1, prompts.get());
    }

    @Test
    void abortedApprovalReturnsReadablePermissionError() {
        AtomicReference<PermissionRequestEvent> prompt = new AtomicReference<>();
        DefaultToolRuntime runtime = runtime(
            context -> allow(),
            requestEvent -> {
                prompt.set(requestEvent);
                return new PermissionResponse(
                    requestEvent.sessionId(),
                    requestEvent.requestId(),
                    requestEvent.cancelOptionId(),
                    false,
                    Instant.now()
                );
            }
        );
        runtime.register(new RequestPermissionsTool());

        ToolResult<?> result = executeOne(runtime, input(fileSystemRequest()));

        assertTrue(result.isError());
        assertTextContains(result, "工具权限请求已中断");
        assertTextContains(result, "need workspace write");
        assertEquals(ApprovalKind.REQUEST_PERMISSIONS, prompt.get().approvalKind());
        assertTrue(prompt.get().additionalPermissions().isPresent());
    }

    @Test
    void approvedRequestReturnsResponseAndPublishesRequestedDelta() {
        AtomicReference<PermissionRequestEvent> prompt = new AtomicReference<>();
        DefaultToolRuntime runtime = runtime(
            context -> allow(),
            requestEvent -> {
                prompt.set(requestEvent);
                return approve(requestEvent);
            }
        );
        runtime.register(new RequestPermissionsTool());

        ToolResult<?> result = executeOne(runtime, input(fileSystemAndNetworkRequest()));

        assertFalse(result.isError());
        RequestPermissionsResponse response = assertInstanceOf(RequestPermissionsResponse.class, result.output());
        assertEquals(PermissionGrantScope.SESSION, response.scope());
        assertTrue(response.strictAutoReview());
        AdditionalPermissionProfile additionalPermissions = response.permissions().additionalPermissions();
        assertTrue(additionalPermissions.fileSystem().isPresent());
        assertTrue(additionalPermissions.network().isPresent());
        assertEquals(NetworkPermissionPolicy.enabled(), additionalPermissions.network().orElseThrow());
        assertTextContains(result, "\"scope\":\"SESSION\"");
        assertTextContains(result, "\"strictAutoReview\":true");

        PermissionRequestEvent event = prompt.get();
        assertEquals(ApprovalKind.REQUEST_PERMISSIONS, event.approvalKind());
        assertEquals(Optional.of(additionalPermissions), event.additionalPermissions());
        assertTrue(event.strictAutoReview());
        assertTrue(event.renderedToolUse().contains("request_permissions"));
    }

    @Test
    void acceptsFilesystemAliasInJsonLikeInput() {
        DefaultToolRuntime runtime = runtime(context -> allow(), this::approve);
        runtime.register(new RequestPermissionsTool());

        ToolResult<?> result = executeOne(runtime, input(Map.of(
            "permissions", Map.of(
                "filesystem", Map.of(
                    "kind", "RESTRICTED",
                    "entries", List.of(Map.of(
                        "path", tempDir.resolve("alias.txt").toString(),
                        "access", "WRITE"
                    ))
                )
            )
        )));

        assertFalse(result.isError());
        RequestPermissionsResponse response = assertInstanceOf(RequestPermissionsResponse.class, result.output());
        assertTrue(response.permissions().additionalPermissions().fileSystem().isPresent());
    }

    @Test
    void acceptsContractShapedAdditionalPermissions() {
        DefaultToolRuntime runtime = runtime(context -> allow(), this::approve);
        runtime.register(new RequestPermissionsTool());

        ToolResult<?> result = executeOne(runtime, input(Map.of(
            "permissions", Map.of(
                "additionalPermissions", Map.of(
                    "fileSystem", Map.of(
                        "kind", "RESTRICTED",
                        "entries", List.of(Map.of(
                            "path", tempDir.resolve("contract.txt").toString(),
                            "access", "READ"
                        ))
                    )
                )
            )
        )));

        assertFalse(result.isError());
        RequestPermissionsResponse response = assertInstanceOf(RequestPermissionsResponse.class, result.output());
        FileSystemPermissionEntry entry = response.permissions()
            .additionalPermissions()
            .fileSystem()
            .orElseThrow()
            .entries()
            .getFirst();
        assertEquals(FileSystemPath.exactPath(tempDir.resolve("contract.txt").toString()), entry.path());
    }

    @Test
    void acceptsPathsShorthandWithDefaultWriteAccess() {
        DefaultToolRuntime runtime = runtime(context -> allow(), this::approve);
        runtime.register(new RequestPermissionsTool());
        Path requested = tempDir.resolve("shorthand.txt");

        ToolResult<?> result = executeOne(runtime, input(Map.of(
            "permissions", Map.of(
                "fileSystem", Map.of(
                    "paths", List.of(requested.toString())
                )
            )
        )));

        assertFalse(result.isError());
        FileSystemPermissionEntry entry = responseEntry(result);
        assertEquals(FileSystemPath.exactPath(requested.toString()), entry.path());
        assertEquals(FileSystemAccessMode.WRITE, entry.access());
    }

    @Test
    void acceptsPathsShorthandWithExplicitReadAccess() {
        DefaultToolRuntime runtime = runtime(context -> allow(), this::approve);
        runtime.register(new RequestPermissionsTool());
        Path requested = tempDir.resolve("readme.txt");

        ToolResult<?> result = executeOne(runtime, input(Map.of(
            "permissions", Map.of(
                "fileSystem", Map.of(
                    "paths", List.of(requested.toString()),
                    "access", "READ"
                )
            )
        )));

        assertFalse(result.isError());
        FileSystemPermissionEntry entry = responseEntry(result);
        assertEquals(FileSystemPath.exactPath(requested.toString()), entry.path());
        assertEquals(FileSystemAccessMode.READ, entry.access());
    }

    @Test
    void rejectsEmptyPathsShorthandAsEmptyPermissions() {
        DefaultToolRuntime runtime = runtime(context -> allow(), this::approve);
        runtime.register(new RequestPermissionsTool());

        ToolResult<?> result = executeOne(runtime, input(Map.of(
            "permissions", Map.of(
                "fileSystem", Map.of(
                    "paths", List.of()
                )
            )
        )));

        assertTrue(result.isError());
        assertTextContains(result, "permissions 不能为空");
    }

    @Test
    void rejectsPathsShorthandWithDenyAccess() {
        DefaultToolRuntime runtime = runtime(context -> allow(), this::approve);
        runtime.register(new RequestPermissionsTool());

        ToolResult<?> result = executeOne(runtime, input(Map.of(
            "permissions", Map.of(
                "fileSystem", Map.of(
                    "paths", List.of(tempDir.resolve("blocked.txt").toString()),
                    "access", "DENY"
                )
            )
        )));

        assertTrue(result.isError());
        assertTextContains(result, "不支持 DENY");
    }

    @Test
    void rejectsPathsShorthandWithBlankAccess() {
        DefaultToolRuntime runtime = runtime(context -> allow(), this::approve);
        runtime.register(new RequestPermissionsTool());

        ToolResult<?> result = executeOne(runtime, input(Map.of(
            "permissions", Map.of(
                "fileSystem", Map.of(
                    "paths", List.of(tempDir.resolve("blank.txt").toString()),
                    "access", "   "
                )
            )
        )));

        assertTrue(result.isError());
        assertTextContains(result, "permissions.fileSystem.access 不能为空");
    }

    @Test
    void rejectsMixedPathsShorthandAndCanonicalFilesystemPolicy() {
        DefaultToolRuntime runtime = runtime(context -> allow(), this::approve);
        runtime.register(new RequestPermissionsTool());

        ToolResult<?> result = executeOne(runtime, input(Map.of(
            "permissions", Map.of(
                "fileSystem", Map.of(
                    "paths", List.of(tempDir.resolve("mixed.txt").toString()),
                    "kind", "UNRESTRICTED",
                    "entries", List.of(Map.of(
                        "path", ":root",
                        "access", "WRITE"
                    ))
                )
            )
        )));

        assertTrue(result.isError());
        assertTextContains(result, "不能与 kind 或 entries 混用");
    }

    @Test
    void rejectsUnsupportedFilesystemPolicyShapesForRequestPermissions() {
        DefaultToolRuntime runtime = runtime(context -> allow(), this::approve);
        runtime.register(new RequestPermissionsTool());

        ToolResult<?> unrestricted = executeOne(runtime, input(Map.of(
            "permissions", Map.of(
                "fileSystem", Map.of("kind", "UNRESTRICTED")
            )
        )));
        ToolResult<?> specialPath = executeOne(runtime, input(Map.of(
            "permissions", Map.of(
                "fileSystem", Map.of(
                    "kind", "RESTRICTED",
                    "entries", List.of(Map.of(
                        "path", ":root",
                        "access", "WRITE"
                    ))
                )
            )
        )));
        ToolResult<?> globPath = executeOne(runtime, input(Map.of(
            "permissions", Map.of(
                "fileSystem", Map.of(
                    "kind", "RESTRICTED",
                    "entries", List.of(Map.of(
                        "path", Map.of("kind", "GLOB_PATTERN", "value", "**/*.txt"),
                        "access", "READ"
                    ))
                )
            )
        )));

        assertTrue(unrestricted.isError());
        assertTextContains(unrestricted, "只支持 RESTRICTED");
        assertTrue(specialPath.isError());
        assertTextContains(specialPath, "只支持 EXACT_PATH");
        assertTrue(globPath.isError());
        assertTextContains(globPath, "只支持 EXACT_PATH");
    }

    @Test
    void strictAutoReviewApprovedForTurnMakesLaterCommandAsk() {
        AtomicInteger prompts = new AtomicInteger();
        List<PermissionRequestEvent> events = new ArrayList<>();
        AtomicInteger executions = new AtomicInteger();
        DefaultToolRuntime runtime = runtime(
            context -> Boolean.TRUE.equals(context.get("strictAutoReview"))
                ? strictAutoReviewAsk()
                : allow(),
            requestEvent -> {
                prompts.incrementAndGet();
                events.add(requestEvent);
                return approve(requestEvent);
            },
            executions
        );
        runtime.register(new RequestPermissionsTool());
        runtime.register(new BashTool(executor(executions)));

        List<ToolResult<?>> results = runtime.execute(
            List.of(
                new ToolUseRequest("toolu_perm", "request_permissions", input(strictAutoReviewRequest()), "msg_1"),
                new ToolUseRequest("toolu_bash", "bash", Map.of("command", "echo ok"), "msg_1")
            ),
            context(AgentMode.EXECUTE, runtimeState(ApprovalMode.ON_REQUEST))
        );

        assertFalse(results.get(0).isError());
        assertFalse(results.get(1).isError());
        assertEquals(2, prompts.get());
        assertEquals(1, executions.get());
        assertEquals(ApprovalKind.REQUEST_PERMISSIONS, events.get(0).approvalKind());
        assertTrue(events.get(1).message().contains("strictAutoReview"));
    }

    @Test
    void approvedNetworkPermissionStillReviewsLaterNonReadOnlyWebSearch() {
        AtomicInteger prompts = new AtomicInteger();
        AtomicInteger searches = new AtomicInteger();
        DefaultToolRuntime runtime = runtime(context -> allow(), requestEvent -> {
            prompts.incrementAndGet();
            return approve(requestEvent);
        });
        runtime.register(new RequestPermissionsTool());
        runtime.register(new WebSearchTool(
            new WebProviderRegistry(
                "tavily",
                Map.of("tavily", new CountingSearchProvider(searches))
            )
        ));

        List<ToolResult<?>> results = runtime.execute(
            List.of(
                new ToolUseRequest("toolu_perm", "request_permissions", input(networkRequest()), "msg_1"),
                new ToolUseRequest("toolu_search", "web_search", Map.of("query", "java"), "msg_1")
            ),
            context(AgentMode.EXECUTE, runtimeState(ApprovalMode.ON_REQUEST)),
            new cn.lypi.contracts.runtime.ToolRuntimeInvocation("ses_1", "turn_1")
        );

        assertFalse(results.get(0).isError());
        assertFalse(results.get(1).isError());
        assertEquals(2, prompts.get());
        assertEquals(1, searches.get());
    }

    private Map<String, Object> input(Map<String, Object> request) {
        return request;
    }

    private Map<String, Object> fileSystemRequest() {
        return Map.of(
            "reason", "need workspace write",
            "permissions", new RequestPermissionProfile(new AdditionalPermissionProfile(
                Optional.of(FileSystemPermissionPolicy.restricted(List.of(
                    new FileSystemPermissionEntry(
                        FileSystemPath.exactPath(tempDir.resolve("out.txt").toString()),
                        FileSystemAccessMode.WRITE
                    )
                ))),
                Optional.empty()
            ))
        );
    }

    private Map<String, Object> fileSystemAndNetworkRequest() {
        return Map.of(
            "reason", "need workspace write and network",
            "scope", "SESSION",
            "strictAutoReview", true,
            "permissions", Map.of(
                "fileSystem", Map.of(
                    "kind", "RESTRICTED",
                    "entries", List.of(Map.of(
                        "path", Map.of("kind", "EXACT_PATH", "value", tempDir.resolve("out.txt").toString()),
                        "access", "WRITE"
                    ))
                ),
                "network", Map.of("mode", "ENABLED")
            )
        );
    }

    private Map<String, Object> strictAutoReviewRequest() {
        return Map.of(
            "reason", "need review",
            "strictAutoReview", true,
            "permissions", new RequestPermissionProfile(new AdditionalPermissionProfile(
                Optional.of(FileSystemPermissionPolicy.restricted(List.of(
                    new FileSystemPermissionEntry(
                        FileSystemPath.exactPath(tempDir.resolve("review.txt").toString()),
                        FileSystemAccessMode.WRITE
                    )
                ))),
                Optional.empty()
            ))
        );
    }

    private Map<String, Object> networkRequest() {
        return Map.of(
            "reason", "need network",
            "permissions", Map.of("network", Map.of("mode", "ENABLED"))
        );
    }

    private FileSystemPermissionEntry responseEntry(ToolResult<?> result) {
        RequestPermissionsResponse response = assertInstanceOf(RequestPermissionsResponse.class, result.output());
        return response.permissions()
            .additionalPermissions()
            .fileSystem()
            .orElseThrow()
            .entries()
            .getFirst();
    }

    private ToolResult<?> executeOne(DefaultToolRuntime runtime, Map<String, Object> input) {
        return executeOne(runtime, AgentMode.EXECUTE, runtimeState(ApprovalMode.ON_REQUEST), input);
    }

    private ToolResult<?> executeOne(
        DefaultToolRuntime runtime,
        AgentMode agentMode,
        PermissionRuntimeState runtimeState,
        Map<String, Object> input
    ) {
        return runtime.execute(
            List.of(new ToolUseRequest("toolu_perm", "request_permissions", input, "msg_1")),
            context(agentMode, runtimeState)
        ).getFirst();
    }

    private DefaultToolRuntime runtime(
        SecurityDecisionProvider securityDecisionProvider,
        PermissionResponseGate responseGate
    ) {
        return runtime(securityDecisionProvider, responseGate, new AtomicInteger());
    }

    private DefaultToolRuntime runtime(
        SecurityDecisionProvider securityDecisionProvider,
        PermissionResponseGate responseGate,
        AtomicInteger executions
    ) {
        return new DefaultToolRuntime(
            ToolRuntimeOptions.builder()
                .sessionId("ses_1")
                .cwd(tempDir)
                .build(),
            (request, context) -> securityDecisionProvider.decide(context.metadata()),
            responseGate,
            new RecordingEventBus()
        );
    }

    private Executor executor(AtomicInteger executions) {
        return new Executor() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public ExecutionResult execute(
                ExecutionRequest request,
                cn.lypi.contracts.common.ProgressSink progress,
                cn.lypi.contracts.common.AbortSignal signal
            ) {
                executions.incrementAndGet();
                return new ExecutionResult(0, "ok\n", "", false, Optional.empty());
            }
        };
    }

    private PermissionResponse approve(PermissionRequestEvent requestEvent) {
        return new PermissionResponse(
            requestEvent.sessionId(),
            requestEvent.requestId(),
            requestEvent.defaultOptionId(),
            false,
            Instant.now()
        );
    }

    private PermissionDecision allow() {
        return new PermissionDecision(
            PermissionBehavior.ALLOW,
            PermissionDecisionReason.MODE_DEFAULT,
            "allowed",
            Optional.<PermissionUpdate>empty(),
            Map.of()
        );
    }

    private PermissionDecision strictAutoReviewAsk() {
        return new PermissionDecision(
            PermissionBehavior.ASK,
            PermissionDecisionReason.SANDBOX_POLICY,
            "strictAutoReview 要求本轮后续命令先进入人工 review。",
            Optional.<PermissionUpdate>empty(),
            Map.of("strictAutoReview", true)
        );
    }

    private PermissionRuntimeState runtimeState(ApprovalMode approvalMode) {
        return new PermissionRuntimeState(
            new ApprovalPolicy(approvalMode),
            new ActivePermissionProfile(":workspace"),
            cn.lypi.contracts.security.PermissionProfiles.workspace(),
            new LegacyPermissionBehavior(false, false, true),
            PermissionMode.ASK
        );
    }

    private cn.lypi.contracts.context.ContextSnapshot context(
        AgentMode agentMode,
        PermissionRuntimeState runtimeState
    ) {
        return new cn.lypi.contracts.context.ContextSnapshot(
            new SystemPrompt("system", List.of(), "hash"),
            List.of(),
            new ModelSelection("provider", "model", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            agentMode,
            runtimeState,
            new ContextBudget(0, 0, 0, 0, 0, 0L, 0L, BigDecimal.ZERO)
        );
    }

    private void assertTextContains(ToolResult<?> result, String expected) {
        ToolResultContentBlock block = assertInstanceOf(
            ToolResultContentBlock.class,
            result.newMessages().getFirst().content().getFirst()
        );
        assertTrue(block.text().contains(expected), () -> "Expected [" + block.text() + "] to contain [" + expected + "]");
    }

    @FunctionalInterface
    private interface SecurityDecisionProvider {
        PermissionDecision decide(Map<String, Object> contextMetadata);
    }

    private static final class RecordingEventBus implements EventBus {
        private final List<AgentEvent> events = new ArrayList<>();

        @Override
        public void publish(AgentEvent event) {
            events.add(event);
        }

        @Override
        public EventSubscription subscribe(EventFilter filter, EventConsumer consumer) {
            return () -> {
            };
        }
    }

    private static final class CountingSearchProvider implements WebSearchProvider {
        private final AtomicInteger searches;

        private CountingSearchProvider(AtomicInteger searches) {
            this.searches = searches;
        }

        @Override
        public String name() {
            return "tavily";
        }

        @Override
        public WebSearchResponse search(WebSearchRequest request) {
            searches.incrementAndGet();
            return new WebSearchResponse(
                "tavily",
                request.query(),
                Optional.empty(),
                List.of(new WebSearchResult(
                    "Example",
                    "https://example.com",
                    Optional.of("snippet"),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()
                )),
                Optional.empty()
            );
        }
    }
}
