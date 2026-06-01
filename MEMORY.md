# ly-pi 项目记忆

## 代码规范

- Java 代码统一使用驼峰命名法：类型使用 `UpperCamelCase`，方法、变量、字段使用 `lowerCamelCase`。
- 架构设计遵循「重接口、轻继承」：优先通过接口定义模块边界，避免深层继承结构。
- 当前阶段只落地数据结构定义、接口定义和基础框架，不写业务实现。
- 重要接口方法必须使用如下块注释风格：

```java
/*
*@status : 
*@summary :
*@description :
*
*
                          */
```

## Git 开发规范

- 所有功能开发必须使用独立 Git worktree，不直接在主工作区开发。
- 本地工作树目录优先放在 `.worktrees/` 下，命名必须使用 `worktree-xxxxx`，其中 `xxxxx` 表示开发方向，例如 `worktree-session-engine`。
- 新开发分支必须基于远端 `origin/dev` 创建。
- 功能分支命名建议使用 `feature/xxxxx`、`fix/xxxxx`、`chore/xxxxx`。
- 开发完成后必须推送远端分支，并以 Pull Request 形式合并。
- 普通功能 PR 目标分支为 `dev`；发布或稳定化 PR 再由 `dev` 合并到 `master`。
- PR 合并前必须通过 GitHub Actions 中的 Maven CI 检查。
- `docs/`、`.worktrees/`、`worktree-*/` 与所有 `target/` 构建产物不得提交。

