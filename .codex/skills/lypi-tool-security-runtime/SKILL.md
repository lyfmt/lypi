---
name: lypi-tool-security-runtime
description: Use when changing ly-pi tool execution, built-in tools, MCP tool adapters, permission gates, Bash risk analysis, path safety, sandbox executors, or tool result budgeting.
---

# ly-pi Tool And Security Runtime

## Core Rule

All tool execution goes through the unified runtime. Do not bypass registry resolution, schema validation, policy checks, permission gates, events, or result budgeting for convenience.

## Boundaries

- `lypi-tool` owns tool registry, execution planning, built-in tools, MCP adapters, permission gate integration, result budgeting and shell executors.
- `lypi-tool` owns Codex-style approval coordination after a `PermissionDecision` is produced: approval policy checks, prompt gate calls, approval provenance, permission amendments, turn-scoped additional permissions and projection to Host or sandbox execution. `ToolPermissionCoordinator` should stay a thin orchestration adapter; detailed approval handling belongs in focused collaborators.
- `lypi-security` owns policy decisions, non-Bash path safety, Bash normalization, risk analysis, sandbox eligibility, rule matching, filesystem/network profile checks and hard-safety gates.
- Contract records and enums live in `lypi-contracts`.

## Key Code

- `lypi-tool/src/main/java/cn/lypi/tool/DefaultToolRuntime.java`
- `lypi-tool/src/main/java/cn/lypi/tool/ToolPermissionCoordinator.java`
- `lypi-tool/src/main/java/cn/lypi/tool/ToolExecutionPlanner.java`
- `lypi-tool/src/main/java/cn/lypi/tool/ToolSchemaValidator.java`
- `lypi-tool/src/main/java/cn/lypi/tool/ToolResultBudgeter.java`
- `lypi-tool/src/main/java/cn/lypi/tool/ToolExecutionEventPublisher.java`
- `lypi-tool/src/main/java/cn/lypi/tool/ApprovalCoordinator.java`
- `lypi-tool/src/main/java/cn/lypi/tool/ApprovalRequestFactory.java`
- `lypi-tool/src/main/java/cn/lypi/tool/InlineAdditionalPermissionsAuthorizer.java`
- `lypi-tool/src/main/java/cn/lypi/tool/SandboxEscalationPolicy.java`
- `lypi-tool/src/main/java/cn/lypi/tool/PermissionAmendmentStore.java`
- `lypi-tool/src/main/java/cn/lypi/tool/builtin/BuiltInTools.java`
- `lypi-tool/src/main/java/cn/lypi/tool/builtin/RequestPermissionsTool.java`
- `lypi-tool/src/main/java/cn/lypi/tool/builtin/BashTool.java`
- `lypi-tool/src/main/java/cn/lypi/tool/mcp/McpToolAdapter.java`
- `lypi-tool/src/main/java/cn/lypi/tool/shell/PermissionProfileSandboxPolicyResolver.java`
- `lypi-tool/src/main/java/cn/lypi/tool/shell/ExecutorRegistry.java`
- `lypi-tool/src/main/java/cn/lypi/tool/shell/BubblewrapExecutor.java`
- `lypi-security/src/main/java/cn/lypi/security/PermissionDecisionPipeline.java`
- `lypi-security/src/main/java/cn/lypi/security/PermissionProfileConfigCompiler.java`
- `lypi-security/src/main/java/cn/lypi/security/DefaultPolicyEngine.java`
- `lypi-security/src/main/java/cn/lypi/security/DefaultBashRiskAnalyzer.java`
- `lypi-security/src/main/java/cn/lypi/security/BashSandboxEligibilityPolicy.java`
- `lypi-security/src/main/java/cn/lypi/security/PathSafetyChecker.java`

## Permission Runtime Model

- `PermissionRuntimeState` is the canonical permission state passed through context metadata, sessions, headless JSON and boot config.
- `PermissionMode` is compatibility data only. New permission decisions should read `permissionRuntimeState` and use legacy mode only as a fallback alias.
- Built-in profiles are `:read-only`, `:workspace`, `:danger-full-access` and external profiles. `:danger-full-access` projects to disabled sandbox with host network, while hard safety still lives in security decisions.
- `PermissionProfileSandboxPolicyResolver` projects managed filesystem/network profiles plus approved `AdditionalPermissionProfile` into `SandboxRuntimePolicy`.
- `BYPASS` has highest execution-boundary priority and always projects to `SandboxRuntimePolicy.disabled()`, even when an explicit managed profile is also present.
- `ToolPermissionCoordinator.Result` distinguishes direct `ALLOW` from approval during the current call. `DefaultToolRuntime` exposes the latter to the tool only as `permissionApprovedForHostExecution=true` in that call's metadata.
- Additional permissions are request payloads, not separate approval decisions. Approval still returns ordinary `APPROVED` / allow gate results.

## Execution Flow

1. Resolve tool name or alias in `DefaultToolRegistry`.
2. Canonicalize request to the resolved tool name.
3. `ToolExecutionPlanner` groups consecutive read-only and concurrency-safe calls into parallel batches; other calls are serial.
4. Publish tool start event.
5. Validate JSON schema, then tool-specific input.
6. `DefaultToolRuntime` rejects non-read-only calls in `AgentMode.PLAN` before security policy evaluation.
7. Build `ToolUseContext` metadata with canonical `permissionRuntimeState`, legacy `permissionMode`, invocation ids, strict auto review and approved additional permissions.
8. Ask `SecurityRuntimePort` and tool-specific permission checks for decisions. Bash risk metadata is retained independently from sandbox eligibility.
9. Return final `ALLOW` without calling a gate or reviewer. LOW/MEDIUM Bash and the explicit high-risk sandbox allowlist use this path.
10. Apply explicit sandbox escalation and delegate inline `sandboxPermissions=withAdditionalPermissions` approval to `InlineAdditionalPermissionsAuthorizer`.
11. Use `ApprovalCoordinator` for remaining ASK decisions and record whether approval occurred during this call.
12. Add current-call approval provenance to a fresh `ToolUseContext`; clear it when the next call context is created.
13. `BashTool` selects Host for BYPASS, current-call approval or `requireEscalated`; all other allowed Bash calls receive a managed policy. `ExecutorRegistry` routes only from the resulting request.
14. Run interceptors, abort handling and tool execution, then record successful `request_permissions` responses into turn/session permission state.
15. Apply `ToolResultBudgeter` and publish the tool end event with summary and optional output ref.

## Current Policy Order

`PermissionDecisionPipeline.decide()` currently evaluates:

1. Explicit `DENY` rules.
2. Agent `PLAN` mode restrictions for sandbox escalation, inline additional permissions and `request_permissions`.
3. Path safety for non-Bash tools.
4. Filesystem profile boundaries for non-Bash tools.
5. Bash prefix `ALLOW`.
6. Explicit `ALLOW`, with extra limits for unsafe Bash.
7. Bash risk and managed-sandbox eligibility.
8. Explicit `ASK`.
9. Mode default, with `strictAutoReview` able to turn later commands back into `ASK`.

Bash cwd and redirect targets are not rejected by Host-side path or filesystem checks. Redirect targets remain in `bashRisk` metadata for audit. A direct Bash `ALLOW` is constrained by Bubblewrap; a current-call approval or BYPASS is the authority to run on Host. Non-Bash file tools keep the path-safety and filesystem-profile ordering above.

The explicit managed-sandbox allowlist is `curl`, `wget`, `git push`, `sudo`, `rm`, `rmdir`, `shred`, `chmod` and `chown`. Their HIGH/DESTRUCTIVE risk level is not downgraded. `dd`, `mkfs`, `npm install`, `pip install`, unlisted future HIGH/DESTRUCTIVE commands and UNKNOWN shell structures default to ASK. Compound commands and wrappers use the strictest nested command.

## Request Permissions

`request_permissions` asks for turn or session scoped additional filesystem/network permissions. The first implementation supports restricted filesystem entries with exact paths and network enablement. When approved, `DefaultToolRuntime` records the additional permissions in runtime state for the current turn or session. An inline `withAdditionalPermissions` approval marks only that Bash call for Host execution; a later stored rule or direct ALLOW does not inherit that Host authority. When `strictAutoReview` is approved, later commands in the same turn receive `strictAutoReview` metadata and should be reviewed before execution. `DefaultTurnExecutor` must call `ToolRuntimePort.clearTurnState(...)` when the turn ends so turn-scoped permissions and `strictAutoReview` cannot leak. `PermissionAmendmentStore` is for durable `PermissionUpdate` rule amendments, not for `request_permissions` additional-permission payloads.

## Invariants

- Tool result order must match request order even when batches run in parallel.
- Unknown tools return tool errors; they should not crash the turn.
- Permission decisions must include reasons suitable for TUI and audit.
- Bash LOW/MEDIUM and explicitly allowlisted HIGH/DESTRUCTIVE commands reach managed sandbox without review while preserving their original `bashRisk`; review-only and UNKNOWN commands ask.
- Final `ALLOW` does not call the user gate or model reviewer. Current-call ASK or inline additional-permission approval runs Bash on Host; remembered ALLOW rules return to managed sandbox.
- BYPASS always runs Bash on Host. Approved Host execution does not call the sandbox resolver or enforce workspace/symlink containment; cwd must still exist and process launch may still fail normally.
- Directly allowed Bash skips Host-side cwd and redirect containment because managed Bubblewrap owns its resource boundary. Path safety and filesystem profile checks remain mandatory for non-Bash file tools.
- Ordinary sandbox command failures such as read-only filesystems, invisible paths and unavailable network remain command results; they do not automatically request approval or retry on Host.
- `AgentMode.PLAN` rejects non-read-only tool calls and explicit sandbox/additional-permission escalation.
- `request_permissions` may grant additional permissions for later tool calls, but direct inline `sandboxPermissions=withAdditionalPermissions` without approval should ask or fail.
- Turn-scoped additional permissions and `strictAutoReview` are cleared through `ToolRuntimePort.clearTurnState(...)` at turn completion; session-scoped additional permissions remain session runtime state.
- MCP tools share registry, permissions, result budgets and TUI events.

## Tests To Check

- `lypi-tool/src/test/java/cn/lypi/tool/DefaultToolRuntimeTest.java`
- `lypi-tool/src/test/java/cn/lypi/tool/ToolPermissionCoordinatorTest.java`
- `lypi-tool/src/test/java/cn/lypi/tool/ToolExecutionPlannerTest.java`
- `lypi-tool/src/test/java/cn/lypi/tool/ToolResultBudgeterTest.java`
- `lypi-tool/src/test/java/cn/lypi/tool/builtin/BashToolTest.java`
- `lypi-tool/src/test/java/cn/lypi/tool/builtin/RequestPermissionsToolTest.java`
- `lypi-tool/src/test/java/cn/lypi/tool/mcp/McpToolAdapterTest.java`
- `lypi-tool/src/test/java/cn/lypi/tool/ApprovalCoordinatorTest.java`
- `lypi-tool/src/test/java/cn/lypi/tool/PermissionAmendmentStoreTest.java`
- `lypi-security/src/test/java/cn/lypi/security/DefaultPolicyEngineTest.java`
- `lypi-security/src/test/java/cn/lypi/security/BashSandboxEligibilityPolicyTest.java`
- `lypi-security/src/test/java/cn/lypi/security/PermissionDecisionPipelineTest.java`
- `lypi-security/src/test/java/cn/lypi/security/PermissionProfileConfigCompilerTest.java`
- `lypi-security/src/test/java/cn/lypi/security/DefaultBashRiskAnalyzerTest.java`
- `lypi-security/src/test/java/cn/lypi/security/PathSafetyCheckerTest.java`
- `lypi-tool/src/test/java/cn/lypi/tool/shell/PermissionProfileSandboxPolicyResolverTest.java`
- `lypi-tool/src/test/java/cn/lypi/tool/shell/BubblewrapExecutorTest.java`
- `lypi-tool/src/test/java/cn/lypi/tool/shell/BubblewrapCommandBuilderTest.java`
- `lypi-tool/src/test/java/cn/lypi/tool/shell/SandboxPolicyResolverTest.java`
- `lypi-tool/src/test/java/cn/lypi/tool/shell/ExecutorRegistryTest.java`

## Before Editing

- Determine whether the change belongs in a tool, runtime orchestration, policy engine, or shell executor.
- Add tests for allow, ask and deny paths when permission behavior changes.
- For Codex-style permission changes, test canonical `PermissionRuntimeState` plus legacy `PermissionMode` compatibility.
- For Bash changes, test normalization, redirect parsing, risk level, sandbox eligibility, approval provenance and final executor routing.
- For path changes, include symlink, outside-workspace and protected metadata cases where relevant.
- For parallelism changes, prove result ordering remains stable.
- For `request_permissions`, test approval policy, strict auto review, turn/session scope and sandbox projection.

After using this Skill, reverse-check the current `PermissionDecisionPipeline` and `DefaultPolicyEngine` order because code is more authoritative than prose design docs.
