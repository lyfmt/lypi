# ly-pi 开发规范

## 分支模型

- `master`：稳定分支，只接收从 `dev` 发起的发布或稳定化 PR。
- `dev`：日常集成分支，所有功能开发都从该分支创建。
- `feature/xxxxx`、`fix/xxxxx`、`chore/xxxxx`：开发分支，必须通过 PR 合并回 `dev`。

## Worktree 规范

所有开发必须使用独立 Git worktree，不直接在主工作区开发。

推荐目录：

```bash
.worktrees/worktree-xxxxx
```

其中 `xxxxx` 表示开发方向，例如：

```bash
.worktrees/worktree-session-engine
.worktrees/worktree-tool-runtime
.worktrees/worktree-ci
```

创建方式：

```bash
git fetch origin
git worktree add .worktrees/worktree-xxxxx -b feature/xxxxx origin/dev
cd .worktrees/worktree-xxxxx
```

## 开发流程

1. 从远端 `dev` 更新代码。
2. 基于 `origin/dev` 创建独立 worktree。
3. 在 worktree 中完成开发和验证。
4. 提交前运行：

```bash
mvn verify
```

局部开发时可以先运行模块级测试，例如：

```bash
mvn -pl lypi-tool -am test
```

但提交前以完整 `mvn verify` 为准。

5. 推送开发分支：

```bash
git push -u origin feature/xxxxx
```

6. 在 GitHub 上创建 PR，普通功能 PR 目标分支为 `dev`。
7. 等待 GitHub Actions 通过后再合并。

## CI/CD 规则

- PR 到 `dev` 或 `master` 时必须运行 Maven verify、脚本 lint、workflow lint 和依赖审查。
- 推送到 `dev` 或 `master` 时必须运行 Maven verify、脚本 lint 和 workflow lint。
- `master` 推送或手动触发时会构建 Maven 包，执行 boot jar headless 空启动冒烟，并上传构建产物。
- Dependabot 每周检查 GitHub Actions 和 Maven 依赖更新，目标分支为 `dev`。

## 忽略规则

以下内容不得提交：

- `docs/`
- `.worktrees/`
- `worktree-*/`
- `target/`
- `**/target/`
