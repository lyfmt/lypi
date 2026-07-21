# ly-pi

`ly-pi` 是一个基于 Java 的本地 coding agent，面向代码库理解、文件修改、命令执行、长任务推进和会话沉淀。项目参考了 `pi`、`Codex` 和 `Claude Code` 的代码实现。

项目关注 coding agent 工程化中容易变复杂的部分：会话如何恢复，历史如何审计，工具如何受控，模型差异如何收敛，资源如何渐进披露，记忆如何沉淀，子任务如何隔离。代码采用 Maven 多模块结构，使用 Spring Boot 做装配，核心边界通过接口和契约类型定义，便于替换模型适配、工具实现、资源发现和交互入口。

## 亮点

- **会话可恢复**：会话记录采用追加式 JSONL，分支、压缩摘要、模型切换、thinking 切换、agent mode 和权限运行态都以 entry 表达。
- **上下文可管理**：从当前 leaf 回放会话路径，合并资源快照，按模型 context window 估算预算，并在超过阈值时规划压缩。
- **工具可审计**：默认工具、子代理工具和 MCP 工具都经过注册表、Schema 校验、权限判断、事件发布和结果预算链路。
- **三种权限模式**：`ASK` 交给用户确认，`AUTO` 交给模型独立复核，`BYPASS` 面向明确授权的高信任自动化；运行态统一携带 mode、approval policy 和 permission profile。
- **资源渐进披露**：支持 context file、memory、Skill、Prompt Template 和 MCP 配置发现，Skill 正文按需激活。
- **多协议模型适配**：OpenAI 兼容 Provider 覆盖 Responses、Chat Completions、SSE 和 WebSocket；Anthropic Provider 覆盖 Messages 请求和流式事件，两者统一为内部事件流。
- **异步子代理**：`spawn_agent` 启动 prompt-only child session，completion 通过 `wait_agent` 或模型边界 exactly-once 投递，不复制父对话。
- **原生终端交互**：稳定 transcript 写入终端原生 scrollback，动态区域保持有界，并支持 active-turn steering、会话投影切换和 provider fallback 状态。
- **后台记忆沉淀**：主 turn 完成后通过事件触发后台记忆 gate，满足 token 增长和工具调用条件时再运行沉淀流程。

## 架构概览

项目的模块边界围绕一条主线展开：契约层定义数据和端口，会话层保存历史，agent 内核编排单轮行为，模型层负责供应商差异，工具与安全层负责动作边界，资源层负责上下文来源，运行层承接事件、子代理和记忆后台任务，交互入口只做展示和输入输出适配。

| 模块 | 职责 |
| --- | --- |
| `lypi-contracts` | 公共契约、会话条目、工具描述、错误类型、事件、权限状态、资源结构、子代理协议和 TUI 视图模型。 |
| `lypi-session` | 会话创建、恢复、分支查询、JSONL 存储、fork、child session 和工作树 diff 查询。 |
| `lypi-agent-core` | 单轮编排、上下文组装、模型交互、工具回合、压缩规划、分支摘要和中断处理。 |
| `lypi-ai` | 模型注册、OpenAI 兼容与 Anthropic Messages Provider、远端模型发现、流式事件归一化、fallback 和 thinking 参数映射。 |
| `lypi-tool` | 工具注册、Schema 校验、人工或模型权限复核、批次执行、结果预算、内建工具、MCP 工具适配和沙盒执行。 |
| `lypi-security` | 权限 profile 编译、Bash 风险分析、前缀规则匹配、路径安全、网络策略和审批策略判断。 |
| `lypi-resource` | context file、memory、Skill、Prompt Template、MCP 配置发现，以及系统提示词构建。 |
| `lypi-runtime` | 事件总线、AgentCenter、mailbox、子进程管理、运行中 agent 快照和后台记忆沉淀。 |
| `lypi-transport-headless` | 子代理 stdin/stdout JSON 协议和单次 Headless Run 执行。 |
| `lypi-transport-tui` | 基于 JLine 的输入与事件投影、inline terminal rendering、原生 scrollback、steering、slash command、权限与 diff 弹层。 |
| `lypi-boot` | Spring Boot 自动装配、配置绑定、启动入口、默认组件图和示例配置。 |

核心原则是上层依赖抽象契约，下层能力通过端口接入。`lypi-agent-core` 不直接绑定具体终端、具体 Provider 或具体工具实现；`lypi-runtime` 只依赖 `lypi-contracts`；`lypi-boot` 负责把默认实现装配成可运行应用。

一次 turn 的典型路径是：TUI 或 Headless 入口提交 `TurnRequest`，agent core 从 session 和 resource 组装上下文，AI 层流式返回 assistant 文本和 tool call，工具运行时执行受控动作并生成 tool result，session 层追加 entry，事件总线把状态投影给交互入口。主 turn 完成后，运行层再异步处理记忆沉淀等后台任务。

## 核心系统

### 会话与上下文

每个 session 是一条可追溯的 entry 链。`lypi-session` 用 JSONL 追加写入历史，并用 leaf 表示当前分支位置；从历史节点继续输入会形成新分支，切换分支只移动 leaf，不会改写旧 entry。

会话回放会恢复 transcript、模型选择、thinking level、agent mode、权限运行态、压缩摘要和分支摘要。`SessionView` 只携带 `sessionId` 和 `leafId`，持久事实通过 entry 回放得到，避免把 UI 或运行时派生状态写回会话历史。

上下文预算按模型描述中的 context window 估算，自动压缩阈值为窗口的 80%。默认压缩规划保留最近约 `20_000` token，并按 API round 分组，避免把未闭合的工具调用和工具结果切开。大工具结果还会经过预算链路，按需要生成摘要、preview 或外部引用。

### 工具与权限

默认内建工具包括：

| 类型 | 工具 |
| --- | --- |
| 文件工具 | `read`、`write`、`edit` |
| 搜索工具 | `grep`、`glob` |
| 执行工具 | `bash` |
| 权限工具 | `request_permissions` |

Web 工具默认关闭。配置 `lypi.web.enabled=true` 后，运行时会注册 `web_fetch` 和 `get_search_content`；如果 Exa 启用或至少一个商业 provider API key 可用，还会注册 `web_search`。当前 `web_search` 支持 Exa、Tavily、Brave Search 和 Perplexity Search；`web_fetch` 使用本机 HTTP client 抓取公开网页，不依赖商业 provider。

子代理运行层可用时只注册 `spawn_agent` 和 `wait_agent`。`spawn_agent` 必填 `task_name`、`message`，可选 `tools`、`provider`、`model`、`thinking_level`；不暴露 cwd、权限和 Agent mode。`tools` 只接受已注册的 canonical 工具名，在固定的 `read`、`grep`、`glob` 基础集合上追加并去重。`wait_agent` 只接受可选的 `timeout_ms`，区分 completion、用户 steering、turn abort 和 timeout；timeout 不会终止 child。MCP 工具通过 adapter 映射到内部 `Tool` 契约，并使用规范化名称避免与内建工具直接冲突。

公开权限模式由 `lypi.runtime.permission-mode` 选择，默认是 `ASK`：

| 模式 | 非只读工具调用的复核方式 | 默认 profile |
| --- | --- | --- |
| `ASK` | 安全策略和工具检查完成后，由交互式 permission gate 请求用户确认。 | `:workspace` |
| `AUTO` | 使用当前模型和有界上下文执行独立的 allow/deny 复核；输出无效、provider 失败或复核中断时拒绝执行。 | `:workspace` |
| `BYPASS` | 跳过人工和模型复核，直接执行；只应在已明确授权的高信任环境使用。 | `:danger-full-access` |

跨模块仍以 `PermissionRuntimeState` 为 canonical state，统一携带公开 mode、approval policy、active profile 和完整 permission profile；旧权限枚举字符串只在 JSON 读取时兼容。可通过 `lypi.permissions.default-permissions` 选择 `:read-only`、`:workspace`、`:danger-full-access`、`:external` 或自定义 profile。

`request_permissions` 用于请求本轮或本会话 additional permissions。`bash` 只有在对应请求已批准后，才应使用 `sandboxPermissions=withAdditionalPermissions` 扩大 managed sandbox 权限。在 `ASK` 和 `AUTO` 下，路径安全、Bash 风险、网络策略、显式规则以及对应的人工或模型复核都经过统一管线；当沙盒策略无法满足时，工具结果会返回可审计的 retry 提示，而不是自动提权。

`web_search` 会把 query 或域名发送给配置的 provider；`web_fetch` 会由本机直接访问目标 URL，必要时回退到 Jina Reader。网络 profile 未启用且模式不是 `BYPASS` 时，工具级权限检查会进入人工或模型复核，而不是静默放行。`web_fetch` 会校验 URL scheme、credential、localhost、loopback、private 和 link-local 地址，避免访问明显的本地或内网地址。Jina fallback 复用同一次 `web_fetch` 权限决策，不作为网络权限绕过。

### 模型适配

`lypi-ai` 维护模型描述和 Provider 适配。内建 OpenAI 兼容配置可通过 `application.yml` 覆盖或关闭；也可以通过配置注册其他 OpenAI 兼容 Provider 或 Anthropic Messages Provider，并把模型追加到统一目录。

OpenAI 兼容适配支持 Responses、Chat Completions、SSE、WebSocket 和 fallback request style。上层收到的是项目内部的 `AssistantStreamEvent`，不需要直接处理供应商原始事件。模型描述中的 context window、最大输出 token、thinking 支持和图片输入支持会影响请求构建与上下文预算。

Anthropic 适配负责 Messages 请求、SSE 事件归一化、tool call/result 映射和 usage 合并。当前版本不启用 Anthropic extended thinking：Anthropic 模型的 `supports-thinking` 应保持 `false`，作为默认模型时还需把 `lypi.runtime.thinking-level` 设为 `off`。

### 资源与记忆

资源运行时按用户层、项目层、嵌套项目层和显式路径发现上下文材料：

- context file：`SYSTEM.md`、`APPEND_SYSTEM.md`、`AGENTS.md`、`CLAUDE.md`；
- memory：用户级 `memory.md`、项目级 `MEMORY.md` 和 `.ly-pi/memory/**` 主题文件；
- Skill：`skills/**/SKILL.md` 和 `.ly-pi/skills/**/SKILL.md`；
- Prompt Template：`prompts/*.md` 和 `.ly-pi/prompts/*.md`；
- MCP 配置：用户级 `mcp.json`、`mcp/*.json`，项目级 `.ly-pi/mcp.json`、`.ly-pi/mcp/*.json`。

`DefaultResourceLoader` 负责发现、解析和诊断；`DefaultSystemPromptBuilder` 决定哪些内容进入系统提示词。memory 会作为长期经验源注入，`MEMORY.md` 可作为 `.ly-pi/memory/**` 主题文件的索引；系统提示词也会提示 agent 在需要 L2 项目记忆时按需读取 `.ly-pi/memory.md` 或 `MEMORY.md`。Skill 默认只披露索引和触发描述，完整正文由激活流程按需读取；Prompt Template 保留 frontmatter 参数并由 renderer 渲染。

后台记忆沉淀由 `TurnEndEvent` 触发。运行层先检查本轮是否完成，再在后台读取 fork point transcript；默认达到约 `10_000` token 后初始化，之后要求约 `5_000` token 增长，并满足至少 3 次工具调用或自然对话断点。沉淀流程会跳过已由主 turn 成功写入 memory 的情况，失败也不会阻塞用户可见 turn。

### 子代理与入口

子代理用于把独立任务交给 prompt-only child session。每次 `spawn_agent` 创建可读的 `taskName`、稳定的 `agentId`、独立的 `childSessionId` 和本次执行的 `runId`；当前每个 Agent 只运行一次，底层 Agent 与 Run 状态保持分离。child 只接收本次 `message`，cwd 由父 session 内部继承，不复制父对话历史。

省略 `provider`、`model` 或 `thinking_level` 时分别继承主 Agent；显式配置由模型目录校验。child 审批模式固定为 `AUTO`，继承父 session 的 active sandbox/profile，不继承父审批模式、turn 级临时授权或 `strictAutoReview`。

completion 进入父 session 的持久 mailbox 后只会被消费一次。父 turn 正在执行时，它在下一模型边界作为 `AGENT_COMMUNICATION` 类型的 `SYSTEM_LOCAL` 消息注入；父 turn 正在 `wait_agent` 时，工具结果直接返回 task、Agent、child session、Run、状态和内容；父 turn 已结束时，消息保留到下一 turn。wait 与模型边界共用同一原子消费入口，不会重复投递。

`lypi-transport-headless` 面向单次 child Run，使用 stdin/stdout JSON 协议。输入贯通 task、Agent、child session、Run、父会话、任务提示、工作目录、工具策略和权限运行态；输出返回相同身份、状态、内容、最终 entry 和错误信息。协议要求 stdout 保持结构化 JSON，避免污染父进程解析。

`lypi-transport-tui` 通过事件 reducer 把语义事件投影成 `TuiViewModel`。真实终端渲染路径把稳定 transcript 每个 block 只提交一次到终端原生 scrollback，只重绘有界的 live content、输入框、弹层和状态栏，不再维护应用侧的固定行数历史窗口。

主 turn 执行期间提交的新输入会进入 steering 队列，在下一模型边界合并到当前 turn；如果模型正在 `wait_agent`，steering 会唤醒等待但不消费 pending completion。切换或新建 session 时，TUI 会开启新的 transcript projection epoch 并替换当前动态帧；状态栏同步展示 cwd，provider retry/fallback 通过瞬态事件展示而不写入持久 transcript。

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

推荐使用脚本从独立运行目录启动，避免把会话、mailbox、规则和本地运行态写入源码工作树：

```bash
scripts/run-lypi.sh --run-dir /tmp/lypi-run -- --lypi.runtime.transport=tui
```

一次性执行 prompt 可以放在 `--` 后：

```bash
scripts/run-lypi.sh --run-dir /tmp/lypi-run -- "总结这个目录的模块结构"
```

脚本会构建 `lypi-boot` fat jar，并拒绝使用 Git worktree 内的运行目录。如果已经手动构建 jar，运行时仍应显式指定独立 cwd：

```bash
java -jar lypi-boot/target/lypi-boot-0.0.1-SNAPSHOT.jar --lypi.runtime.cwd=/tmp/lypi-run --lypi.runtime.transport=tui
```

配置示例位于：

```text
lypi-boot/src/main/resources/application.yml.example
```

用户级配置默认从 `~/.ly-pi/application.yml` 读取；文件不存在时跳过。运行目录中的 `application.yml`、环境变量、JVM 系统属性和命令行参数按 Spring Boot 标准优先级覆盖用户级配置。

默认权限配置为 `ASK + :workspace`；显式配置示例：

```properties
lypi.runtime.permission-mode=ask
lypi.permissions.default-permissions=:workspace
```

切换到 Anthropic Messages Provider 的最小配置示例：

```properties
lypi.runtime.default-provider=anthropic
lypi.runtime.default-model=claude-sonnet-4-5
lypi.runtime.thinking-level=off
lypi.ai.providers.anthropic.enabled=true
lypi.ai.providers.anthropic.api-style=anthropic
lypi.ai.providers.anthropic.base-url=https://api.anthropic.com/v1
lypi.ai.providers.anthropic.api-key=${ANTHROPIC_API_KEY:}
lypi.ai.providers.anthropic.anthropic-version=2023-06-01
lypi.ai.providers.anthropic.models[0].model-id=claude-sonnet-4-5
lypi.ai.providers.anthropic.models[0].context-window=200000
lypi.ai.providers.anthropic.models[0].max-output-tokens=64000
lypi.ai.providers.anthropic.models[0].supports-thinking=false
```

启用 Web 工具的最小配置示例：

```properties
lypi.web.enabled=true
```

启用后，默认会注册：

- `web_search`：默认 provider 顺序优先使用 `lypi.web.default-provider`；未指定或默认 provider 不可用时，按 Exa、Tavily、Brave Search、Perplexity 的注册顺序 fallback。Exa 默认启用，endpoint 为 `https://mcp.exa.ai/mcp`，无需本地商业 API key。
- `web_fetch`：先本地抓取并用 jsoup 清洗 HTML；遇到 403、406、429、5xx、不支持的 `content-type` 或正文过短时，回退到 Jina Reader。
- `get_search_content`：按 `responseId`、`url`、`urlIndex`、`query` 或 `queryIndex` 取回 `web_search` / `web_fetch` 保存的结果。`web_search` 仅在 provider 返回正文时保存完整内容；只有摘要的搜索结果会提示改用 `web_fetch` 拉取 URL。

Web 结果缓存默认写入运行 cwd 下的 `.ly-pi/web-results.jsonl`。该文件是本地运行缓存，不应提交。可以关闭缓存：

```properties
lypi.web.cache.enabled=false
```

关闭缓存后，`web_search` 和 `web_fetch` 仍可运行，但结果不会落盘；工具输出会标记 `cache=disabled`，`get_search_content` 会返回明确的缓存未启用错误。

启用商业 `web_search` provider 的配置示例：

```properties
lypi.web.default-provider=tavily
lypi.web.timeout-seconds=20
lypi.web.max-results=10
lypi.web.providers.tavily.api-key-env=TAVILY_API_KEY
lypi.web.providers.brave.api-key-env=BRAVE_SEARCH_API_KEY
lypi.web.providers.perplexity.api-key-env=PERPLEXITY_API_KEY
```

也可以用 `lypi.web.providers.<provider>.api-key` 直接配置 key；该方式只建议用于本地临时验证，避免把密钥写入仓库或会话记录。单个 provider 可通过 `lypi.web.providers.<provider>.enabled=false` 关闭，或通过 `lypi.web.providers.<provider>.endpoint` 指向代理网关、私有中转或兼容服务。要禁用 Exa fallback，可配置 `lypi.web.providers.exa.enabled=false`。

`web_fetch` 的 Jina fallback 可以单独配置：

```properties
lypi.web.fetch.fallback.enabled=true
lypi.web.fetch.fallback.min-body-chars=200
lypi.web.fetch.jina.enabled=true
lypi.web.fetch.jina.endpoint=https://r.jina.ai/http://
```

`get_search_content` 示例：

```json
{"responseId":"web_20260623_000001","urlIndex":1,"maxChars":30000}
```

`web_fetch` 使用本地 HTTP GET 抓取网页，手动处理同 host redirect，并做 jsoup 内容清洗：过滤 script/style/nav/footer/隐藏节点和控制字符，优先抽取 `article`、`main` 或 `[role=main]`，支持输出 `markdown` 或 `text`，并按读取上限和 `maxChars` 截断。`web_fetch` 只做静态 URL 字面量防护，会拒绝明显的本地、内网、link-local、unspecified 和 URL credential；当前不做 DNS 解析级防护。第一阶段暂不支持 PDF、视频、GitHub 专用抽取或 curator UI。
