---
name: lycode-knowledge-maintenance
description: Use when maintaining ly-code project knowledge, Codex skills, module facts, corrections, or stale design notes under .codex/skills, docs/active, or project memory.
---

# ly-code Knowledge Maintenance

## Core Rule

Project knowledge is useful only when it matches current code. Treat existing notes as leads, not proof.

## Sources

Use this precedence when facts conflict:

1. Current source code and tests.
2. User instructions in `AGENTS.md` or the active conversation.
3. `docs/active/ly-code 最终总体设计书.md`.
4. `docs/active/subagent-current-design.md`.
5. `docs/deactive` only as history, never as current fact unless explicitly marked.

## Scope

Use this skill for:

- Adding or updating repository Codex skills under `.codex/skills/`.
- Correcting stale module knowledge, paths, call chains, or invariants.
- Auditing whether active docs still match code.
- Preparing durable project knowledge after repeated discoveries.

Do not use it for product runtime skills under `.ly-code/skills/` unless the task explicitly targets ly-code resource runtime behavior.

## Maintenance Procedure

1. Read the relevant current Skill before editing it.
2. Re-read the code paths and tests that justify the change.
3. Prefer small patches over rewrites.
4. If a doc and code disagree, record the code-backed fact and report the doc as stale.
5. Keep `SKILL.md` frontmatter limited to `name` and `description` for repository Codex skills.
6. Keep descriptions focused on trigger conditions, not workflow summaries.
7. Before finishing, run metadata and path checks for all touched skills.

## Reverse Verification

After using any ly-code project Skill:

- Check at least one listed code path still exists.
- Check whether the stated module boundary still matches imports and tests.
- If a fact is inconsistent, do not silently follow the stale Skill. Report a correction suggestion with the file path that proves it.

## Commit-Time Diff Check

Before staging or committing knowledge changes:

- Review `git diff -- .codex/skills`.
- Confirm no `.codex/history.jsonl`, `.codex/shell_snapshots/`, session logs, `target/`, `docs/`, `.worktrees/`, or `.ly-code/` runtime files are included by accident.
- If adding tracked knowledge, decide separately whether `.gitignore` needs a narrow exception. Do not change ignore rules as a side effect.

## Baseline Patrol

For broad audits, sample these anchors:

- Root module list: `pom.xml`.
- Architecture summary: `README.md`.
- Boundary tests: `lycode-contracts/src/test/java/cn/lycode/contracts/ArchitectureBoundaryTest.java`.
- Active docs: `docs/active/`.
- Module test directories matching the Skill being updated.

## Common Corrections

| Symptom | Action |
| --- | --- |
| Active doc mentions a capability that source removed | Mark the doc stale in your response and write the source-backed behavior in the Skill. |
| Source has a new tool or entry type missing from docs | Update the relevant Skill with the code path and test path. |
| Skill lists a missing path | Remove or replace the path after verifying with `rg --files` or `find`. |
| Skill description no longer triggers correctly | Rewrite only the description and keep body changes minimal. |
