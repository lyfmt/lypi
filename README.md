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

## 系统导览

`ly-pi` 的核心系统不是孤立能力，而是一组围绕 session leaf 工作的协作链路：

| 系统 | 主要模块 | 关键职责 |
| --- | --- | --- |
| 记忆系统 | `lypi-resource`、`lypi-agent-core`、`lypi-runtime`、`lypi-tool` | 发现 memory 来源，注入读写纪律，提取可沉淀候选，触发长任务沉淀审计。 |
| 上下文系统 | `lypi-session`、`lypi-resource`、`lypi-agent-core` | 从当前 leaf 回放历史，加载资源快照，估算 token 预算，规划压缩和摘要回填。 |
| 权限系统 | `lypi-security`、`lypi-tool` | 统一判断工具请求、Bash 风险、路径边界、运行时规则和用户确认。 |
| 工具系统 | `lypi-tool`、`lypi-contracts`、`lypi-runtime` | 注册内建工具和 MCP 工具，校验入参，编排并发，发布事件，预算化工具结果。 |
| 其他运行系统 | `lypi-ai`、`lypi-runtime`、`lypi-transport-*`、`lypi-boot` | 处理模型适配、事件总线、子代理、TUI、Headless 协议、启动装配和错误归一。 |

一次 turn 的典型路径是：TUI 或 Headless 入口提交 `TurnRequest`，agent core 从 session 和 resource 组装 `ContextSnapshot`，模型层流式返回文本和 tool call，工具运行时执行受控动作并写回 tool result，session 层追加 entry，事件系统把状态投影给交互入口，turn 结束后再触发摘要、分支摘要或 memory 沉淀挂点。

## 核心能力

### 会话与分支

每个 session 是一条可追溯的记录链。`lypi-session` 使用 JSONL 存储不可变 entry，并用 `EntryTreeIndex` 维护当前 leaf。用户从历史节点继续输入时，会形成新的分支；切换分支只移动 leaf，不会破坏旧历史。

会话回放由 `SessionReplayProjector` 负责，它从 entry 链恢复 transcript、模型选择、thinking level、agent mode、permission mode、压缩摘要和分支摘要。fork、child session、文件变更视图和恢复列表都围绕同一套 entry 树构建。

session entry 覆盖消息、压缩摘要、分支摘要、模型切换、thinking 切换、agent mode、permission mode、label、custom entry 和子代理生命周期。所有状态变化都以追加 entry 表达，这让恢复、审计、分支对比和 UI 回放可以共享同一份事实来源。

### Agent 内核

`lypi-agent-core` 负责单轮生命周期：构建上下文、调用模型、累积流式输出、识别工具调用、执行工具批次、写回消息，并在成功完成后触发记忆提取挂点。`DefaultTurnExecutor` 只依赖端口，不直接知道终端、具体 Provider 或工具实现。

上下文由 `DefaultContextAssembler` 从会话和资源两侧组装：一边读取当前 session leaf 的回放结果，另一边加载项目资源并生成系统提示词；随后用 `ContextBudgetEstimator` 估算上下文占用，决定是否需要进入摘要规划。

压缩规划不会随意从中间截断历史。`DefaultCompactionPlanner` 会按 API round 分组，尽量保留完整的用户输入、assistant 输出、工具调用和工具结果组合，避免摘要边界落在未闭合的工具回合中。

自动压缩只在估算上下文超过 `autoCompactThreshold` 后触发，且不会连续对刚写入的 compaction entry 再压缩。默认规划会从最近回合倒推保留约 20,000 token，再把更早的完整回合写入 `CompactionPlan`。压缩摘要由专门 summarizer 生成；如果摘要 prompt 自身过长，重试策略会退化为更短上下文，避免为了压缩再次撑爆窗口。

工具结果还有独立的微压缩和预算链路。大结果可以被替换成摘要、preview 或 `ToolOutputRef`，并在上下文里留下 `ContentReplacementRecord`，这样模型能看到必要结论，完整输出仍可通过引用追溯。

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

工具描述由 `ToolDescriptor`、JSON Schema、别名、只读标记、并发标记和中断行为共同表达。`DefaultToolRuntime` 会为每批请求创建 `ToolUseContext`，把 cwd、session、turn、permission mode、sandbox 策略和运行时规则传给工具；工具自身仍可以通过 `validateInput` 和 `checkPermissions` 补充领域约束。

内建工具分为 4 类：

| 类型 | 工具 | 说明 |
| --- | --- | --- |
| 文件工具 | `read`、`write`、`edit` | 受路径安全和权限系统约束；`read` 支持带行号文本和小图片附件，输出按上下文预算折叠。 |
| 搜索工具 | `glob`、`grep` | 优先使用 ripgrep 能力，返回命中统计、分页和结构化结果。 |
| 执行工具 | `bash` | 先经过 Bash 风险分析和 sandbox 策略，再进入 host 或 Bubblewrap executor。 |
| 子代理工具 | `spawn_agent`、`list_agents`、`wait_agent`、`read_agent_result`、`read_mailbox`、`accept`、`stash`、`discard`、`continue`、`interrupt` | 把异步任务和 mailbox 操作显式建模为工具调用。 |

MCP 工具接入时会把外部 server、tool schema 和 tool result 映射到内部契约。名称映射由 `McpToolName` 负责，避免外部工具名和内建工具名直接冲突；结果映射会把 MCP 文本、结构化内容和错误统一成 `ToolResult`，供事件、TUI 和上下文预算继续处理。

`read` 工具读取文本文件时返回带行号内容；读取 PNG、JPEG、GIF 或 WEBP 图片时只返回短摘要，并把图片作为模型可见附件传给支持多模态输入的 Provider。第一版会对 PNG/JPEG 尝试尺寸缩放和压缩，GIF/WEBP 只执行大小限制；未知二进制文件会被拒绝，避免把二进制内容当 UTF-8 文本塞进上下文。

工具中断也有契约边界。每个工具可以声明 `InterruptBehavior`；运行时在收到中断后通过 abort signal 通知执行链，并把最终状态归一为 success、error、denied、cancelled 或 interrupted，避免 TUI、session 和模型上下文对同一次工具调用产生不同理解。

### 错误与事件

错误不是以字符串散落在各层。契约层定义了 `LyPiException` 及模型、工具、权限、隔离、压缩、记忆等分类错误，并带有严重程度和处理建议。agent 内核在单轮失败时会通过异常处理器生成标准错误消息，TUI 则通过事件 reducer 把错误投影成可读块。

事件模型覆盖 session start、turn start/end、message start/delta/end、tool start/progress/end、permission request/decision、retry、compact、interrupt、memory write 和 error。终端、日志、回放和子代理协调都可以消费同一套语义事件。

### 权限与隔离

`lypi-security` 和工具层共同决定动作是否可以发生。策略判断的顺序很明确：显式拒绝优先，其次是路径安全、Bash 重定向、prefix allow、Bash 风险、显式询问和显式允许。即使在放宽模式下，也不能越过路径安全和未知 Bash 风险。

Bash 命令会先做静态风险分析，区分低风险、写入、网络、远端变更、破坏性操作和未知风险；对于可接受的常用命令，可以生成 prefix 规则，后续按规范化后的命令前缀匹配。

命令隔离由 Bubblewrap 执行链承接。构建器会处理只读路径、可写路径、网络隔离、`/tmp`、`/proc`、缺失路径遮蔽、符号链接、受保护元数据目录等边界。大量测试覆盖 `.git`、`.codex`、`.agents`、deny-read、allow-read、allow-write 和 symlink 组合，目标是让策略无法满足时显式失败，而不是静默放行。

权限模型以 canonical `PermissionRuntimeState` 为中心，包含 approval policy、active permission profile、legacy behavior 和兼容用的 `legacyPermissionMode`。`PermissionMode` 只作为旧配置、旧 JSON 和 UI 展示的兼容入口；运行时判定优先读取 `permissionRuntimeState`。profile 决定文件系统和网络边界，approval policy 决定是否允许进入人工确认；显式 `DENY` 总是优先，显式 `ALLOW` 对 Bash 也只在风险静态可知且不是破坏性命令时生效。

模型可以通过 `request_permissions` 请求临时或会话级 additional permissions，再用 `sandboxPermissions=withAdditionalPermissions` 让本次 Bash 在 managed sandbox 内扩大权限。是否弹出确认由 approval policy 决定；additional permissions 的批准动作仍是标准 review decision（例如 `APPROVED` / `ABORT`），不会引入专用批准枚举。

路径安全检查覆盖文件工具入参、Bash cwd 和 Bash 重定向目标。工具不能通过 `../`、符号链接、重定向或隐藏元数据目录绕过 workspace 边界；当 sandbox 策略无法满足时，执行结果会携带 `retryWith=sandboxPermissions=requireEscalated`，由上层决定是否向用户请求升级，而不是自动提权。

允许规则既可以来自配置，也可以由用户在权限弹层中临时追加。Bash prefix 规则只匹配规范化后的命令片段，减少等价 shell 写法导致的误匹配；未知风险和破坏性命令不会生成自动允许建议。

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

长期记忆的写入遵循「No Verification, No Memory」原则：没有证据来源的推断不能进入 memory；一次性进度和临时任务状态不进入 memory；与现有 memory 冲突时优先保留可追溯来源并暴露待人工处理的诊断。`MemoryWriteRequest` 会声明 scope、kind、目标路径和写入条目，写入策略可以限制只能改 memory 文件，避免沉淀流程误写项目代码。

长任务结束后还可以触发 memory consolidation。`MemoryConsolidationTurnEndListener` 根据 turn 时长、工具轮次和 fork point 判断是否提交沉淀任务；提交、跳过、session 不匹配、缺少 fork point、runner 失败等阶段都会写入审计记录。沉淀任务使用独立提示词要求先读取相关 memory、检查重复和冲突，再小幅增量更新。

资源侧也提供 memory lint 入口，用于检查 memory 与 Skill、Prompt Template 等资源的可发现性和冲突诊断。它不是聊天历史压缩，而是长期知识库治理工具。

### 资源系统

资源系统负责发现并组织项目上下文，包括：

- `AGENTS.md`、`SYSTEM.md`、`APPEND_SYSTEM.md` 等上下文文件；
- 用户层与项目层 memory；
- Skill 元数据、冲突诊断和按需激活内容；
- 带 frontmatter 参数的 Prompt Template；
- MCP Client 配置及优先级校验。

`DefaultResourceLoader` 只做发现、解析和诊断，输出 `ResourceSnapshot`；`DefaultSystemPromptBuilder` 再决定哪些内容进入系统提示词，哪些只保留为索引。这个拆分让资源体系可以渐进披露，也能把重复来源、无效 frontmatter、MCP 配置冲突等问题以诊断信息暴露出来。

资源位置按用户层、项目层、嵌套项目层、显式路径等来源排序。memory 会进入系统提示词；Skill 默认只暴露索引和触发说明，完整 `SKILL.md` 由激活流程按需加载；Prompt Template 保留 frontmatter 参数并通过 renderer 渲染；MCP 配置在资源阶段做解析和优先级诊断，在工具阶段再建立连接。

这套上下文系统的核心目标是「渐进披露」：稳定且短的约束可以直接进入系统提示词，体积大或触发条件强的资源保持索引化，真正需要时再读取或激活，降低上下文窗口被静态材料挤占的概率。

### 子代理

子代理用于把独立任务拆给 child session。父 agent 通过工具发起任务，`DefaultAgentCenter` 创建 child session，记录 lifecycle entry，并启动无界面进程执行一轮任务。完成后，结果会以结构化输出回到父进程，再投递到 mailbox。

这套机制刻意保持隔离：child session 不自动继承父会话完整消息，父 agent 需要把必要背景写进任务提示；child session 会继承或覆盖模型、thinking、agent mode、permission mode 和工具策略。父 agent 可通过 `list_agents`、`wait_agent`、`read_agent_result`、`read_mailbox`、`accept`、`stash`、`discard`、`continue`、`interrupt` 等工具管理任务结果。

mailbox 是父子协作的交接点。子代理完成后不会把长输出直接塞回父上下文，而是投递带状态、摘要、结果引用和 child session 信息的消息；父 agent 可以接受到当前 leaf、暂存、丢弃或继续追问。这样既保留异步任务可追溯性，也避免子任务输出失控占满父会话窗口。

### 终端交互

`lypi-transport-tui` 不是直接打印字符串，而是把语义事件归约为 `TuiViewModel`。`TuiEventReducer` 处理 message start / delta / end、tool start / progress / end、permission request、retry、compact、interrupt、session state 等事件，并把它们映射为消息块、thinking 块、工具块、错误块和状态栏。

终端层还包含输入编辑、历史环、快捷键、slash command、文件 mention、Skill mention、权限弹层、diff 展示、图片尺寸识别、Markdown 渲染和宽度计算。渲染测试覆盖窄屏、长输入软换行、工具折叠预览、权限提示和 diff 区域。工具展示也有专门处理：bash 折叠显示命令状态和尾部预览，读取类工具避免直接泄露大段文件内容，搜索类工具优先显示命中统计。

### 启动装配

`lypi-boot` 负责把这些模块装配成可启动应用。配置层会绑定模型 Provider、默认模型、压缩摘要、工具目录、隔离策略、子代理命令和交互入口；自动装配会在用户未提供自定义 Bean 时创建默认组件图。

启动入口支持带初始提示的一次性执行，也支持进入终端界面；子代理协议模式会走专门命令，启动前关闭会污染 stdout 的日志输出。示例配置放在 `lypi-boot/src/main/resources/application.yml.example`，用于说明 Provider、工具和子代理命令的配置形态。

运行时组合由 Spring Boot 负责，但业务层不依赖 Spring API。默认组件图会把 session manager、resource runtime、AI provider runtime、tool runtime、security runtime、compaction runtime、event bus、agent center 和 transport adapter 组装到 `LyPiRuntime`。需要替换模型、工具、资源发现或交互入口时，优先替换端口实现，而不是改 agent core。

### 无界面协议

`lypi-transport-headless` 为子代理提供单次 stdin/stdout JSON 协议。输入包含 child session、父会话、任务提示、工作目录、工具策略、权限模式和超时；输出包含状态、摘要、最终 entry 和错误信息。协议测试要求 stdout 只包含结构化 JSON，避免日志污染父进程解析。

Headless 模式还支持 continue 语义，用于在已有 child session 上追加任务。它和 TUI 共用 session、工具、权限和 agent core，只是把交互层替换为 JSON 协议，因此适合被父进程、脚本或后续服务端入口复用。

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

提交前完整验证：

```bash
mvn verify
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
- 提交前至少执行 `mvn verify`。
- 完整 CI 规则见 `CONTRIBUTING.md`。
- `docs/`、`.worktrees/`、`worktree-*/` 与各模块构建产物不得提交。

## 设计取向

`ly-pi` 更关注 agent 工程化中的长期问题：上下文如何沉淀、历史如何追溯、工具如何受控、权限如何表达、子任务如何隔离、不同入口如何共享同一套内核。

因此项目的实现倾向于清晰边界和稳定契约：接口优先、事件统一、历史追加、资源分层、工具受控。这样的结构让能力扩展保持克制，也让问题定位和行为审计更直接。
