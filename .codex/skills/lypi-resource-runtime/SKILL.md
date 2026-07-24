---
name: lypi-resource-runtime
description: Use when changing ly-pi resource discovery, AGENTS or memory loading, Skill scanning or activation, prompt templates, MCP config discovery, or system prompt assembly.
---

# ly-pi Resource Runtime

## Core Rule

Resource discovery and prompt assembly are separate. `DefaultResourceLoader` discovers and parses resources; `DefaultSystemPromptBuilder` decides what enters the system prompt.

## Product Boundary

This skill is for ly-pi product runtime resources. Product Skill roots are scanned under:

- `<resource-root>/skills`
- `<resource-root>/.ly-pi/skills`

Repository Codex skills under `.codex/skills/` are for this development environment and are not product runtime Skill roots unless a future code change adds that location.

## Key Code

- `lypi-resource/src/main/java/cn/lypi/resource/DefaultResourceLoader.java`
- `lypi-resource/src/main/java/cn/lypi/resource/ResourceLocationResolver.java`
- `lypi-resource/src/main/java/cn/lypi/resource/ProjectRootResolver.java`
- `lypi-resource/src/main/java/cn/lypi/resource/ContextFileScanner.java`
- `lypi-resource/src/main/java/cn/lypi/resource/MemorySourceScanner.java`
- `lypi-resource/src/main/java/cn/lypi/resource/SkillScanner.java`
- `lypi-resource/src/main/java/cn/lypi/resource/DefaultSkillActivationService.java`
- `lypi-resource/src/main/java/cn/lypi/resource/PromptTemplateScanner.java`
- `lypi-resource/src/main/java/cn/lypi/resource/McpConfigScanner.java`
- `lypi-resource/src/main/java/cn/lypi/resource/DefaultSystemPromptBuilder.java`
- `lypi-resource/src/main/java/cn/lypi/resource/PermissionPromptSection.java`

## Main Flow

1. `ProjectRootResolver.resolve(cwd)` finds project context.
2. `ResourceLocationResolver.resolve(projectRoot, cwd)` builds ordered user, project, nested and explicit locations.
3. `DefaultResourceLoader.load(cwd)` scans context files, memory, Skills, prompt templates and MCP configs.
4. `DefaultSystemPromptBuilder.build()` appends the base agent prompt, context files, memory, Skill index, prompt template index and current permission section.
5. Full Skill body is loaded later through explicit `SkillMention` injection in agent core.

## Permission Prompt

- `PermissionPromptSection` retains dynamic `Current permission mode`, approval policy, active sandbox profile, `request_permissions`, `strictAutoReview` and `withAdditionalPermissions` values.
- ASK, AUTO and BYPASS use the same mode description: `Follow the permission and sandbox guidance below for tool calls.`
- All three modes render the same default-sandbox guidance: Bash may see read-only or invisible paths and unavailable network; genuine boundary access requires a new `sandboxPermissions=requireEscalated` request with a user-facing justification.
- The prompt states that runtime does not request escalation or retry outside the sandbox automatically. BYPASS still receives this stable operational guidance; runtime execution semantics remain authoritative.
- The section contributes only the existing `permission-runtime-state` source name.

## Invariants

- Scanners should report diagnostics instead of throwing for ordinary malformed resources.
- Skill scanning indexes metadata and hashes; it does not load all bodies into the system prompt.
- Skill names must be lowercase letters, digits and hyphens, maximum 64 characters.
- Product Skill frontmatter may include `allowed_tools` or `allowed-tools`; repository Codex skills should keep only `name` and `description`.
- Prompt templates are lightweight prompt fragments, not workflows with permissions.
- MCP configs are discovery/config data; MCP tools still enter the unified tool runtime.
- Permission guidance must not branch by ASK/AUTO/BYPASS except for dynamic state values.

## Tests To Check

- `lypi-resource/src/test/java/cn/lypi/resource/DefaultResourceLoaderTest.java`
- `lypi-resource/src/test/java/cn/lypi/resource/DefaultSystemPromptBuilderTest.java`
- `lypi-resource/src/test/java/cn/lypi/resource/SkillScannerValidationTest.java`
- `lypi-resource/src/test/java/cn/lypi/resource/SkillScannerSourceAndConflictTest.java`
- `lypi-resource/src/test/java/cn/lypi/resource/DefaultSkillActivationServiceTest.java`
- `lypi-resource/src/test/java/cn/lypi/resource/PromptTemplateOverrideTest.java`
- `lypi-resource/src/test/java/cn/lypi/resource/McpConfigValidationTest.java`

## Before Editing

- Decide whether the new behavior is discovery, rendering, activation or diagnostics.
- Preserve ordered precedence across user, project, nested project and explicit roots.
- Check both valid and invalid frontmatter cases.
- For system prompt changes, test source names, stable cross-mode guidance, dynamic runtime values and content ordering.
- For new resource files, ensure scanner failures do not block unrelated resources.

## Common Changes

| Need | Likely Files |
| --- | --- |
| Add context file type | `ContextFileScanner`, prompt builder tests |
| Change Skill metadata rules | `SkillScanner`, Skill validation tests |
| Change Skill activation | `DefaultSkillActivationService`, agent context assembly tests |
| Change memory loading | `MemorySourceScanner`, `MemoryPromptSection` |
| Change permission guidance | `PermissionPromptSection`, system prompt builder tests |
| Change prompt template behavior | `PromptTemplateScanner`, `DefaultPromptRenderer` |
| Change MCP config precedence | `McpConfigScanner`, MCP config tests |

After using this Skill, verify whether the relevant resource is supposed to be product runtime knowledge or repository Codex knowledge.
