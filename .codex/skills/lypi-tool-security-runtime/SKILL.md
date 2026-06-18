---
name: lypi-tool-security-runtime
description: Use when changing ly-pi tool execution, built-in tools, MCP tool adapters, permission gates, Bash risk analysis, path safety, sandbox executors, or tool result budgeting.
---

# ly-pi Tool And Security Runtime

## Core Rule

All tool execution goes through the unified runtime. Do not bypass registry resolution, schema validation, policy checks, permission gates, events, or result budgeting for convenience.

## Boundaries

- `lypi-tool` owns tool registry, execution planning, built-in tools, MCP adapters, permission gate integration, result budgeting and shell executors.
- `lypi-tool` owns Codex-style approval coordination after a `PermissionDecision` is produced: approval policy checks, prompt gate calls, permission amendments and turn-scoped additional permissions.
- `lypi-security` owns policy decisions, path safety, Bash normalization, risk analysis, rule matching, filesystem/network profile checks and hard-safety gates.
- Contract records and enums live in `lypi-contracts`.

## Key Code

- `lypi-tool/src/main/java/cn/lypi/tool/DefaultToolRuntime.java`
- `lypi-tool/src/main/java/cn/lypi/tool/ToolExecutionPlanner.java`
- `lypi-tool/src/main/java/cn/lypi/tool/ToolSchemaValidator.java`
- `lypi-tool/src/main/java/cn/lypi/tool/ToolResultBudgeter.java`
- `lypi-tool/src/main/java/cn/lypi/tool/ToolExecutionEventPublisher.java`
- `lypi-tool/src/main/java/cn/lypi/tool/ApprovalCoordinator.java`
- `lypi-tool/src/main/java/cn/lypi/tool/ApprovalRequestFactory.java`
- `lypi-tool/src/main/java/cn/lypi/tool/PermissionAmendmentStore.java`
- `lypi-tool/src/main/java/cn/lypi/tool/builtin/BuiltInTools.java`
- `lypi-tool/src/main/java/cn/lypi/tool/builtin/RequestPermissionsTool.java`
- `lypi-tool/src/main/java/cn/lypi/tool/builtin/BashTool.java`
- `lypi-tool/src/main/java/cn/lypi/tool/mcp/McpToolAdapter.java`
- `lypi-tool/src/main/java/cn/lypi/tool/shell/PermissionProfileSandboxPolicyResolver.java`
- `lypi-tool/src/main/java/cn/lypi/tool/shell/BubblewrapExecutor.java`
- `lypi-security/src/main/java/cn/lypi/security/PermissionDecisionPipeline.java`
- `lypi-security/src/main/java/cn/lypi/security/PermissionProfileConfigCompiler.java`
- `lypi-security/src/main/java/cn/lypi/security/DefaultPolicyEngine.java`
- `lypi-security/src/main/java/cn/lypi/security/DefaultBashRiskAnalyzer.java`
- `lypi-security/src/main/java/cn/lypi/security/PathSafetyChecker.java`

## Permission Runtime Model

- `PermissionRuntimeState` is the canonical permission state passed through context metadata, sessions, headless JSON and boot config.
- `PermissionMode` is compatibility data only. New permission decisions should read `permissionRuntimeState` and use legacy mode only as a fallback alias.
- Built-in profiles are `:read-only`, `:workspace`, `:danger-full-access` and external profiles. `:danger-full-access` projects to disabled sandbox with host network, while hard safety still lives in security decisions.
- `PermissionProfileSandboxPolicyResolver` projects managed filesystem/network profiles plus approved `AdditionalPermissionProfile` into `SandboxRuntimePolicy`.
- Additional permissions are request payloads, not separate approval decisions. Approval still returns ordinary `APPROVED` / allow gate results.

## Execution Flow

1. Resolve tool name or alias in `DefaultToolRegistry`.
2. Canonicalize request to the resolved tool name.
3. `ToolExecutionPlanner` groups consecutive read-only and concurrency-safe calls into parallel batches; other calls are serial.
4. Publish tool start event.
5. Validate JSON schema, then tool-specific input.
6. `DefaultToolRuntime` rejects non-read-only calls in `AgentMode.PLAN` before security policy evaluation.
7. Build `ToolUseContext` metadata with canonical `permissionRuntimeState`, legacy `permissionMode`, invocation ids, strict auto review and approved additional permissions.
8. Ask `SecurityRuntimePort` and tool-specific permission checks for decisions.
9. Apply sandbox escalation, Bash sandbox risk decisions and additional permission sandbox projection.
10. Use `ApprovalCoordinator` and `PermissionGate` for ASK decisions.
11. Run interceptors, abort handling and tool execution.
12. Record successful `request_permissions` responses into turn/session permission state.
13. Apply `ToolResultBudgeter`.
14. Publish tool end event with summary and optional output ref.

## Current Policy Order

`PermissionDecisionPipeline.decide()` currently evaluates:

1. Explicit `DENY` rules.
2. Agent `PLAN` mode restrictions for sandbox escalation, inline additional permissions and `request_permissions`.
3. Path safety.
4. Legacy workspace boundary compatibility.
5. Bash redirect target safety.
6. Bash prefix `ALLOW`.
7. Explicit `ALLOW`, with extra limits for unsafe Bash.
8. Bash risk decision.
9. Explicit `ASK`.
10. Mode default, with `strictAutoReview` able to turn later commands back into `ASK`.

`BYPASS` and permissive modes still do not bypass path safety, redirect checks, destructive or unknown Bash review.

## Request Permissions

`request_permissions` asks for turn or session scoped additional filesystem/network permissions. The first implementation supports restricted filesystem entries with exact paths and network enablement. When approved, `DefaultToolRuntime` records the additional permissions in runtime state for the current turn or session. When `strictAutoReview` is approved, later commands in the same turn receive `strictAutoReview` metadata and should be reviewed before execution. `PermissionAmendmentStore` is for durable `PermissionUpdate` rule amendments, not for `request_permissions` additional-permission payloads.

## Invariants

- Tool result order must match request order even when batches run in parallel.
- Unknown tools return tool errors; they should not crash the turn.
- Permission decisions must include reasons suitable for TUI and audit.
- Bash commands require static risk analysis; unknown risk should ask, not silently allow.
- Path safety applies to file tools and Bash redirection targets.
- `AgentMode.PLAN` rejects non-read-only tool calls and explicit sandbox/additional-permission escalation.
- `request_permissions` may grant additional permissions for later tool calls, but direct inline `sandboxPermissions=withAdditionalPermissions` without approval should ask or fail.
- MCP tools share registry, permissions, result budgets and TUI events.

## Tests To Check

- `lypi-tool/src/test/java/cn/lypi/tool/DefaultToolRuntimeTest.java`
- `lypi-tool/src/test/java/cn/lypi/tool/ToolExecutionPlannerTest.java`
- `lypi-tool/src/test/java/cn/lypi/tool/ToolResultBudgeterTest.java`
- `lypi-tool/src/test/java/cn/lypi/tool/builtin/BashToolTest.java`
- `lypi-tool/src/test/java/cn/lypi/tool/builtin/RequestPermissionsToolTest.java`
- `lypi-tool/src/test/java/cn/lypi/tool/mcp/McpToolAdapterTest.java`
- `lypi-tool/src/test/java/cn/lypi/tool/ApprovalCoordinatorTest.java`
- `lypi-tool/src/test/java/cn/lypi/tool/PermissionAmendmentStoreTest.java`
- `lypi-security/src/test/java/cn/lypi/security/DefaultPolicyEngineTest.java`
- `lypi-security/src/test/java/cn/lypi/security/PermissionDecisionPipelineTest.java`
- `lypi-security/src/test/java/cn/lypi/security/PermissionProfileConfigCompilerTest.java`
- `lypi-security/src/test/java/cn/lypi/security/DefaultBashRiskAnalyzerTest.java`
- `lypi-security/src/test/java/cn/lypi/security/PathSafetyCheckerTest.java`
- `lypi-tool/src/test/java/cn/lypi/tool/shell/PermissionProfileSandboxPolicyResolverTest.java`
- `lypi-tool/src/test/java/cn/lypi/tool/shell/BubblewrapExecutorTest.java`
- `lypi-tool/src/test/java/cn/lypi/tool/shell/BubblewrapCommandBuilderTest.java`
- `lypi-tool/src/test/java/cn/lypi/tool/shell/SandboxPolicyResolverTest.java`

## Before Editing

- Determine whether the change belongs in a tool, runtime orchestration, policy engine, or shell executor.
- Add tests for allow, ask and deny paths when permission behavior changes.
- For Codex-style permission changes, test canonical `PermissionRuntimeState` plus legacy `PermissionMode` compatibility.
- For Bash changes, test normalization, redirect parsing and risk level.
- For path changes, include symlink, outside-workspace and protected metadata cases where relevant.
- For parallelism changes, prove result ordering remains stable.
- For `request_permissions`, test approval policy, strict auto review, turn/session scope and sandbox projection.

After using this Skill, reverse-check the current `PermissionDecisionPipeline` and `DefaultPolicyEngine` order because code is more authoritative than prose design docs.
