# ly-pi

`ly-pi` 是一个基于 Java 的本地 coding agent，面向代码库理解、文件修改、命令执行、长任务推进和会话沉淀。

它关注的是 coding agent 工程化中最容易变复杂的部分：会话如何恢复，历史如何审计，工具如何受控，模型差异如何收敛，记忆如何沉淀，子任务如何隔离。它采用 Maven 多模块结构，使用 Spring Boot 进行装配，核心边界通过接口定义，便于替换模型适配、工具实现、资源发现和交互入口。

## 亮点

- **会话可恢复**：会话记录采用追加式 JSONL，历史不被原地改写；分支、摘要、模型切换和权限变化都有明确记录。
- **上下文可管理**：从当前 leaf 回放会话路径，按模型窗口估算预算，并在接近阈值时触发摘要规划。
- **工具可审计**：内建文件、搜索、命令、子代理等工具能力，执行前经过参数校验、权限判断和结果归一化。
- **权限有边界**：命令风险分析、权限提示、允许规则和隔离策略分层处理，避免工具调用绕过统一入口。
- **记忆可沉淀**：把用户偏好、项目事实、纠错记录和会话摘要分层管理，让经验能跨轮次、跨会话复用。
- **模型适配克制**：把 Responses、Chat Completions、WebSocket、SSE 和 fallback 差异收敛到统一事件流。
- **资源渐进披露**：支持 `AGENTS.md`、memory、Skill、Prompt Template 和 MCP 配置等资源发现，不把所有内容一次性塞进系统提示词。
- **多入口协同**：终端交互、无界面子进程和后续扩展入口共享同一套会话、工具、资源和事件模型。
- **子代理协作**：父 agent 可以启动独立 child session 承接异步任务，并通过 mailbox 回收摘要式结果。

## 架构概览

项目的模块边界围绕一条主线展开：契约层定义数据和端口，会话层保存历史，agent 内核编排单轮行为，模型层负责供应商差异，工具与安全层负责动作边界，资源层负责上下文来源，交互入口只做展示和输入输出适配。

项目按职责拆分为多个 Maven 模块：

| 模块 | 职责 |
| --- | --- |
| `lypi-contracts` | 公共契约、会话条目、工具描述、错误类型、事件、子代理协议和视图模型。 |
| `lypi-session` | 会话创建、恢复、分支查询、JSONL 存储、fork 和 child session 管理。 |
| `lypi-agent-core` | 单轮编排、上下文组装、模型交互、工具回合、摘要规划和中断处理。 |
| `lypi-ai` | 模型注册、Provider 适配、流式事件归一化、fallback 和 thinking 参数映射。 |
| `lypi-tool` | 工具注册、Schema 校验、权限门禁、批次规划、结果预算、内建工具和 MCP 工具适配。 |
| `lypi-security` | 命令风险分析、前缀规则匹配、路径安全检查和策略判断。 |
| `lypi-resource` | 项目资源发现、Skill 扫描、记忆加载、Prompt 渲染、MCP 配置和诊断。 |
| 事件与子代理协调模块 | 事件总线、AgentCenter、子进程管理、mailbox 投递和运行中 agent 快照。 |
| `lypi-transport-tui` | 基于 JLine 的终端界面、输入编辑、快捷键、弹层、diff、mention 和渲染。 |
| `lypi-transport-headless` | 子代理进程的 stdin/stdout JSON 协议、失败输出和 continue 模式。 |
| `lypi-boot` | Spring Boot 装配、配置绑定、入口分流、默认组件图和示例配置。 |

核心原则是上层依赖抽象契约，下层能力通过端口接入。`agent-core` 不直接绑定具体终端、具体模型供应商或具体工具实现。

`lypi-contracts` 是这条边界的锚点。它把 agent turn、消息块、工具调用、权限决策、事件、模型流、session entry、memory、Skill、MCP、TUI 视图和子代理协议都定义成显式契约。契约层还包含产品边界、能力守卫和错误分级，让模块之间传递稳定数据结构，而不是互相暴露内部对象。

## 核心能力

### 会话与分支

每个 session 是一条可追溯的记录链。`lypi-session` 使用 JSONL 存储不可变 entry，并用 `EntryTreeIndex` 维护当前 leaf。用户从历史节点继续输入时，会形成新的分支；切换分支只移动 leaf，不会破坏旧历史。

会话回放由 `SessionReplayProjector` 负责，它从 entry 链恢复 transcript、模型选择、thinking level、agent mode、permission mode、压缩摘要和分支摘要。fork、child session、文件变更视图和恢复列表都围绕同一套 entry 树构建。

### Agent 内核

`lypi-agent-core` 负责单轮生命周期：构建上下文、调用模型、累积流式输出、识别工具调用、执行工具批次、写回消息，并在成功完成后触发记忆提取挂点。`DefaultTurnExecutor` 只依赖端口，不直接知道终端、具体 Provider 或工具实现。

上下文由 `DefaultContextAssembler` 从会话和资源两侧组装：一边读取当前 session leaf 的回放结果，另一边加载项目资源并生成系统提示词；随后用 `ContextBudgetEstimator` 估算上下文占用，决定是否需要进入摘要规划。

压缩规划不会随意从中间截断历史。`DefaultCompactionPlanner` 会按 API round 分组，尽量保留完整的用户输入、assistant 输出、工具调用和工具结果组合，避免摘要边界落在未闭合的工具回合中。

### 模型适配

模型层把不同 Provider 的请求格式、流式事件、工具调用、thinking 内容和错误分类收敛为项目内部统一结构。上层只关心「发起请求、接收事件、处理失败」，不需要理解供应商协议细节。

`lypi-ai` 中的 OpenAI 兼容适配器会按配置生成多种请求尝试：Responses over WebSocket、Responses over SSE、Chat Completions over SSE，以及 fallback request style。流式 normalizer 把供应商原始事件转换为 `AssistantStreamEvent`，并保留文本、thinking、工具调用、usage、错误等结构。

模型注册也不是硬编码单表：内建模型、远端发现和配置覆盖会合并成 `ModelDescriptor` 列表；兼容性清洗会避免把密钥类配置混进模型描述。Provider 重试由错误分类、退避策略和输出是否已开始共同决定，避免在已经开始输出后盲目重放请求。

### 工具系统

工具调用经过统一入口：

1. 工具注册表查找工具描述，并把别名归一到规范工具名。
2. Schema 校验和工具自校验共同检查输入。
3. 规划器把只读工具和可并行工具分批，保证返回顺序仍与请求顺序一致。
4. 权限策略决定是否允许、拒绝或请求用户确认。
5. 工具执行过程发布 start、progress、end 事件，TUI 可实时展示状态。
6. 结果按预算生成完整内容、摘要、preview 或外部引用。

内建工具覆盖文件读取、写入、编辑、glob、grep、bash 和子代理控制。MCP 工具通过独立 adapter 接入，并使用名称归一化避免外部工具名污染内部注册表。

这条链路让工具能力可以扩展，也让审计、权限、并发和上下文预算保持一致。

### 错误与事件

错误不是以字符串散落在各层。契约层定义了 `LyPiException` 及模型、工具、权限、隔离、压缩、记忆等分类错误，并带有严重程度和处理建议。agent 内核在单轮失败时会通过异常处理器生成标准错误消息，TUI 则通过事件 reducer 把错误投影成可读块。

事件模型覆盖 session start、turn start/end、message start/delta/end、tool start/progress/end、permission request/decision、retry、compact、interrupt、memory write 和 error。终端、日志、回放和子代理协调都可以消费同一套语义事件。

### 权限与隔离

`lypi-security` 和工具层共同决定动作是否可以发生。策略判断的顺序很明确：显式拒绝优先，其次是路径安全、Bash 重定向、prefix allow、Bash 风险、显式询问和显式允许。即使在放宽模式下，也不能越过路径安全和未知 Bash 风险。

Bash 命令会先做静态风险分析，区分低风险、写入、网络、远端变更、破坏性操作和未知风险；对于可接受的常用命令，可以生成 prefix 规则，后续按规范化后的命令前缀匹配。

命令隔离由 Bubblewrap 执行链承接。构建器会处理只读路径、可写路径、网络隔离、`/tmp`、`/proc`、缺失路径遮蔽、符号链接、受保护元数据目录等边界。大量测试覆盖 `.git`、`.codex`、`.agents`、deny-read、allow-read、allow-write 和 symlink 组合，目标是让策略无法满足时显式失败，而不是静默放行。

### 记忆系统

`ly-pi` 把 memory 视为可演进的经验源，而不是简单的长文本附录。它区分长期记忆和会话摘要：长期记忆记录稳定偏好、项目事实、纠错结论和团队约定；会话摘要只服务于当前会话的上下文延续。

当前设计按层组织记忆：

| 层级 | 典型位置 | 内容 |
| --- | --- | --- |
| L0 全局索引 | `~/.ly-pi/memory.md` | 用户级记忆入口、下层指针、触发场景和治理红线。 |
| L1 用户记忆 | `~/.ly-pi/memory/*` | 跨项目复用的长期偏好、协作习惯和重要纠错。 |
| L2 项目记忆 | `MEMORY.md`、`.ly-pi/memory/*` | 当前项目目标、边界、设计方向、项目事实和团队约定。 |
| 会话摘要 | session JSONL 中的摘要条目 | 当前会话的压缩摘要和分支摘要。 |

资源加载阶段会扫描用户层和项目层的 memory 文件，去重后生成带来源路径和内容哈希的 `MemorySource`。系统提示词构建阶段会注入 memory 读写纪律：只有经过工具执行、文件读取、测试结果或用户明确确认的信息，才允许沉淀；临时进度、未验证猜测、密钥、日志流水和一次性命令输出不得写入。

agent 单轮正常完成后会进入记忆提取挂点。提取结果使用结构化契约表达候选内容、写入请求、跳过原因和失败原因，并按 `PREFERENCE`、`PROJECT_FACT`、`CORRECTION`、`CONVENTION` 等类型分类。这套契约让后续自动沉淀可以独立演进，即使提取失败也不会改变本轮对话结果。

### 资源系统

资源系统负责发现并组织项目上下文，包括：

- `AGENTS.md`、`SYSTEM.md`、`APPEND_SYSTEM.md` 等上下文文件；
- 用户层与项目层 memory；
- Skill 元数据、冲突诊断和按需激活内容；
- 带 frontmatter 参数的 Prompt Template；
- MCP Client 配置及优先级校验。

`DefaultResourceLoader` 只做发现、解析和诊断，输出 `ResourceSnapshot`；`DefaultSystemPromptBuilder` 再决定哪些内容进入系统提示词，哪些只保留为索引。这个拆分让资源体系可以渐进披露，也能把重复来源、无效 frontmatter、MCP 配置冲突等问题以诊断信息暴露出来。

### 子代理

子代理用于把独立任务拆给 child session。父 agent 通过工具发起任务，`DefaultAgentCenter` 创建 child session，记录 lifecycle entry，并启动无界面进程执行一轮任务。完成后，结果会以结构化输出回到父进程，再投递到 mailbox。

这套机制刻意保持隔离：child session 不自动继承父会话完整消息，父 agent 需要把必要背景写进任务提示；child session 会继承或覆盖模型、thinking、agent mode、permission mode 和工具策略。父 agent 可通过 `list_agents`、`wait_agent`、`read_agent_result`、`read_mailbox`、`accept`、`stash`、`discard`、`continue`、`interrupt` 等工具管理任务结果。

### 终端交互

`lypi-transport-tui` 不是直接打印字符串，而是把语义事件归约为 `TuiViewModel`。`TuiEventReducer` 处理 message start / delta / end、tool start / progress / end、permission request、retry、compact、interrupt、session state 等事件，并把它们映射为消息块、thinking 块、工具块、错误块和状态栏。

终端层还包含输入编辑、历史环、快捷键、slash command、文件 mention、Skill mention、权限弹层、diff 展示、图片尺寸识别、Markdown 渲染和宽度计算。渲染测试覆盖窄屏、长输入软换行、工具折叠预览、权限提示和 diff 区域。工具展示也有专门处理：bash 折叠显示命令状态和尾部预览，读取类工具避免直接泄露大段文件内容，搜索类工具优先显示命中统计。

### 启动装配

`lypi-boot` 负责把这些模块装配成可启动应用。配置层会绑定模型 Provider、默认模型、压缩摘要、工具目录、隔离策略、子代理命令和交互入口；自动装配会在用户未提供自定义 Bean 时创建默认组件图。

启动入口支持带初始提示的一次性执行，也支持进入终端界面；子代理协议模式会走专门命令，启动前关闭会污染 stdout 的日志输出。示例配置放在 `lypi-boot/src/main/resources/application.yml.example`，用于说明 Provider、工具和子代理命令的配置形态。

### 无界面协议

`lypi-transport-headless` 为子代理提供单次 stdin/stdout JSON 协议。输入包含 child session、父会话、任务提示、工作目录、工具策略、权限模式和超时；输出包含状态、摘要、最终 entry 和错误信息。协议测试要求 stdout 只包含结构化 JSON，避免日志污染父进程解析。

## 快速开始

环境要求：

- JDK 21
- Maven 3.9+

构建项目：

```bash
mvn -DskipTests package
```

执行测试：

```bash
mvn test
```

打包后启动：

```bash
java -jar lypi-boot/target/lypi-boot-0.0.1-SNAPSHOT.jar
```

配置示例位于：

```text
lypi-boot/src/main/resources/application.yml.example
```

常见配置关注点包括：默认模型、OpenAI 兼容 Provider、请求风格与降级、摘要模型、工具工作目录、隔离策略、子代理命令和终端入口。

## 开发约定

- 日常开发基于 `origin/dev` 创建功能分支。
- 所有功能开发放在独立 Git worktree 中，推荐目录为 `.worktrees/worktree-xxxxx`。
- 普通功能 PR 目标分支为 `dev`，发布或稳定化 PR 再由 `dev` 合并到 `master`。
- 提交前至少执行 `mvn test`。
- `docs/`、`.worktrees/`、`worktree-*/` 与各模块构建产物不得提交。

## 设计取向

`ly-pi` 更关注 agent 工程化中的长期问题：上下文如何沉淀、历史如何追溯、工具如何受控、权限如何表达、子任务如何隔离、不同入口如何共享同一套内核。

因此项目的实现倾向于清晰边界和稳定契约：接口优先、事件统一、历史追加、资源分层、工具受控。这样的结构让能力扩展保持克制，也让问题定位和行为审计更直接。
