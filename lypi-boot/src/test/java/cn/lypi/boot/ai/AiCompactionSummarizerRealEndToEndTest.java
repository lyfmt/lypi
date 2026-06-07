package cn.lypi.boot.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.lypi.ai.DefaultApiProviderRegistry;
import cn.lypi.ai.DefaultModelPort;
import cn.lypi.ai.DefaultModelRegistry;
import cn.lypi.ai.ProviderAdapterApiProvider;
import cn.lypi.ai.provider.RequestStyle;
import cn.lypi.ai.provider.TransportMode;
import cn.lypi.ai.provider.ProviderEventStream;
import cn.lypi.ai.provider.ProviderRequest;
import cn.lypi.ai.provider.ProviderTransport;
import cn.lypi.ai.provider.openai.OpenAiCompatibleProviderAdapter;
import cn.lypi.ai.provider.openai.OpenAiProviderConfig;
import cn.lypi.ai.spec.ContextSnapshotRequestFactory;
import cn.lypi.ai.transport.HttpSseProviderTransport;
import cn.lypi.agent.ContextAssembly;
import cn.lypi.agent.ContextBudgetEstimator;
import cn.lypi.agent.ContextBuildRequest;
import cn.lypi.agent.DefaultContextAssembler;
import cn.lypi.agent.compact.AiCompactionSummarizer;
import cn.lypi.agent.compact.CompactSummaryContextBuilder;
import cn.lypi.agent.compact.CompactSummaryInstructionFactory;
import cn.lypi.agent.compact.CompactSummaryRequest;
import cn.lypi.agent.compact.CompactSummaryResult;
import cn.lypi.agent.compact.CompactionDecision;
import cn.lypi.agent.compact.CompactionRequest;
import cn.lypi.agent.compact.CompactionSummarizer;
import cn.lypi.agent.compact.CompactionSummaryOptions;
import cn.lypi.agent.compact.DefaultCompactionCoordinator;
import cn.lypi.agent.compact.DefaultCompactionPlanner;
import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventConsumer;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.CostProfile;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import cn.lypi.contracts.session.CompactionEntry;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.ModeChangeEntry;
import cn.lypi.contracts.session.ModelChangeEntry;
import cn.lypi.contracts.session.PermissionModeChangeEntry;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.ThinkingChangeEntry;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.skill.SkillIndex;
import cn.lypi.session.SessionManagerImpl;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class AiCompactionSummarizerRealEndToEndTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    @Timeout(180)
    void compactsRealSessionContextThroughConfiguredRealProvider() throws IOException {
        assumeTrue(Boolean.getBoolean("lypi.summary.e2e"), "Enable with -Dlypi.summary.e2e=true");
        RealSummarySettings settings = RealSummarySettings.fromSystemProperties();
        SessionManagerImpl session = new SessionManagerImpl(tempDir);
        String sessionId = "real-summary-e2e";
        session.openOrCreate(sessionId);
        String leafId = appendRealBranch(session, settings).leafId();
        DefaultContextAssembler assembler = new DefaultContextAssembler(
            session,
            fixedResourceRuntime(realSystemPrompt()),
            new ContextBudgetEstimator()
        );
        ContextBuildRequest buildRequest = new ContextBuildRequest(sessionId, Optional.of(leafId), tempDir, true);
        ContextAssembly assembly = assembler.build(buildRequest);
        RecordingProviderTransport recordingTransport = new RecordingProviderTransport(new HttpSseProviderTransport());
        RecordingSummarizer summarizer = new RecordingSummarizer(new AiCompactionSummarizer(
            modelPort(settings, recordingTransport),
            new CompactSummaryContextBuilder(new CompactSummaryInstructionFactory()),
            CompactionSummaryOptions.defaults()
        ));
        DefaultCompactionCoordinator coordinator = new DefaultCompactionCoordinator(
            session,
            assembler,
            new NoopEventBus(),
            new DefaultCompactionPlanner(8),
            summarizer,
            Clock.systemUTC()
        );

        CompactionDecision decision = coordinator.preflight(new CompactionRequest(
            sessionId,
            Optional.of(leafId),
            tempDir,
            buildRequest,
            assembly,
            () -> false
        ));

        assertThat(decision.compacted())
            .as("decision reason=%s, estimatedTokens=%s, threshold=%s",
                decision.reason(),
                assembly.snapshot().budget().estimatedContextTokens(),
                assembly.snapshot().budget().autoCompactThreshold())
            .isTrue();
        assertThat(summarizer.requests).hasSize(1);
        CompactSummaryRequest summaryRequest = summarizer.requests.getFirst();
        assertThat(summaryRequest.context().messages()).containsExactlyElementsOf(assembly.snapshot().messages());
        assertThat(summaryRequest.context().systemPrompt()).isEqualTo(assembly.snapshot().systemPrompt());
        assertThat(summaryRequest.context().model()).isEqualTo(assembly.snapshot().model());
        assertThat(summaryRequest.context().thinkingLevel()).isEqualTo(assembly.snapshot().thinkingLevel());
        assertThat(summaryRequest.context().mode()).isEqualTo(assembly.snapshot().mode());
        assertThat(summaryRequest.context().permissionMode()).isEqualTo(assembly.snapshot().permissionMode());
        assertThat(ContextSnapshotRequestFactory.from(summaryRequest.context(), "summary-e2e", List.of())
            .options()
            .maxOutputTokens()).isEmpty();
        assertThat(recordingTransport.requestBodies).hasSize(1);
        assertRealProviderPayload(recordingTransport.requestBodies.getFirst(), summaryRequest.context().messages().size());
        assertThat(summaryRequest.context().model())
            .isEqualTo(new ModelSelection(settings.provider(), settings.model(), settings.thinkingLevel()));
        assertThat(summaryRequest.context().thinkingLevel()).isEqualTo(settings.thinkingLevel());
        assertThat(summarizer.results).hasSize(1);
        String summary = summarizer.results.getFirst().summary();
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/real-compact-summary.txt"), summary);
        System.out.println("REAL_COMPACT_SUMMARY_BEGIN");
        System.out.println(summary);
        System.out.println("REAL_COMPACT_SUMMARY_END");
        assertCompliantSummary(summary);
        CompactionEntry compactionEntry = appendedCompaction(session, decision.plan().orElseThrow().firstKeptEntryId());
        assertThat(compactionEntry.summary()).isEqualTo(summary);
        assertThat(decision.context().messages().getFirst().content().getFirst().text()).isEqualTo(summary);
    }

    private static SessionHandle appendRealBranch(SessionManagerImpl session, RealSummarySettings settings) {
        SessionHandle handle = session.append(new ModelChangeEntry(
            "entry-model",
            null,
            new ModelSelection(settings.provider(), settings.model(), settings.thinkingLevel()),
            "real summary e2e model",
            Instant.EPOCH
        ));
        handle = session.append(new ThinkingChangeEntry(
            "entry-thinking",
            handle.leafId(),
            settings.thinkingLevel(),
            "real summary e2e thinking",
            Instant.EPOCH
        ));
        handle = session.append(new ModeChangeEntry("entry-mode", handle.leafId(), AgentMode.EXECUTE, "mode", Instant.EPOCH));
        handle = session.append(new PermissionModeChangeEntry(
            "entry-permission",
            handle.leafId(),
            PermissionMode.DEFAULT_EXECUTE,
            "permission",
            Instant.EPOCH
        ));
        handle = appendTurn(handle, session, 1,
            "按照 docs/2026-06-06-session-compact-ai-access-plan.md 实现 session compact AI 接入。"
                + "文档底部给了测试专用 AI 资源，真实链路只用于测试，不要把 key 写入提交。"
                + "实际链路不需要 token 上限限制；summary model 直接使用当前上下文 model。",
            "已确认实现方向：AI compact summary 通过当前 ContextSnapshot 构造请求，沿用当前 system prompt、model、"
                + "thinking、mode、permission，并在原消息后追加一条总结指导。测试资源只通过系统属性传入。"
        );
        handle = appendTurn(handle, session, 2,
            "我选择方案 2：接入 AI summary。model 不做单独设置，直接使用当前上下文的 model。",
            "代码侧移除 summary 专用 provider/model/thinking 配置入口，只保留 compact-summary.enabled 和失败策略兼容字段。"
                + "CompactSummaryContextBuilder 负责构造 summary 上下文。"
        );
        handle = appendTurn(handle, session, 3,
            "不仅仅 thinking level，所有配置都必须一致。你确定都是一致的吗？",
            "补充核对：summary 请求使用 assembly.snapshot() 的 systemPrompt、model、thinkingLevel、mode、permissionMode。"
                + "ContextSnapshotRequestFactory 只从该 snapshot 转 provider 请求，tools 传空列表。"
        );
        handle = appendTurn(handle, session, 4,
            "我们只保证最新的总结指导放在最后，前面所有保持和之前一致就行，这样做到了吗？",
            "已调整为保留原 messages 前缀顺序不变，只把 compact-summary-instruction 作为最后一条 SYSTEM_LOCAL message 追加。"
                + "这能提高 provider prompt/cache 前缀一致性，但不承诺命中率。"
        );
        handle = appendTurn(handle, session, 5,
            "我的 dev merge 了另一个提交，出现大量冲突。先订修复计划，再开始修复，最后发送子代理复核。",
            "修复计划是：先定位冲突来源，再按语义合并 origin/dev 的 compact/budget 改动和本分支 AI summary 接入，"
                + "不机械选择 ours/theirs；之后跑测试、真实 AI 链路，并派 gpt-5.4 子代理复核。"
        );
        handle = appendTurn(handle, session, 6,
            "允许，进行修复。",
            "冲突修复保留 origin/dev 删除 DefaultCompactionSummarizer 的结果；AI summarizer 失败时抛错，"
                + "DefaultCompactionCoordinator 捕获后回到原上下文且不追加 CompactionEntry。"
        );
        handle = appendTurn(handle, session, 7,
            "你必须跑真实 AI 路径验证。别 smoke，制造真实的上下文发送。",
            "新增 AiCompactionSummarizerRealEndToEndTest：真实创建 SessionManagerImpl、DefaultContextAssembler、"
                + "DefaultCompactionCoordinator 和 AiCompactionSummarizer，经 OpenAI-compatible SSE transport 调用用户提供的测试模型。"
        );
        handle = appendTurn(handle, session, 8,
            "模型返回的内容呢，你得亲自检查是否合规。真实的 summary 对吗，是内容的总结吗？",
            "验证要求升级：测试会把模型真实返回写入 target/real-compact-summary.txt，"
                + "并检查返回不含 analysis/summary 标签、代码围栏、寒暄开头。人工读取时必须确认它总结的是上下文内容。"
        );
        handle = appendTurn(handle, session, 9,
            "记得不是说实际链路不需要 token 上限限制吗？",
            "补充 RecordingProviderTransport 捕获真实 HTTP 请求 body，并断言不包含 max_output_tokens、max_tokens、temperature。"
                + "模型 descriptor 的 maxOutputTokens 仅是能力元数据，不写入 summary generation options。"
        );
        handle = appendTurn(handle, session, 10,
            "最终收尾要给我一份做了什么的文档，然后提交 PR。",
            "收尾事项：更新本地 docs 工作说明但不提交 docs；运行 focused tests、lypi-agent-core tests、真实 AI E2E、mvn test；"
                + "发送 gpt-5.4 子代理 review；提交 feature/session-compact-ai 并更新 PR #34。"
        );
        handle = session.append(new MessageEntry(
            "entry-final-user",
            handle.leafId(),
            message("msg-final-user", MessageRole.USER, "最终要求：请在摘要中明确写出“真实 AI compact summary 验证”。"),
            Instant.EPOCH
        ));
        return handle;
    }

    private static void assertRealProviderPayload(String body, int originalMessageCount) throws IOException {
        JsonNode root = OBJECT_MAPPER.readTree(body);
        assertThat(root.has("max_output_tokens")).isFalse();
        assertThat(root.has("max_tokens")).isFalse();
        assertThat(root.has("temperature")).isFalse();
        assertThat(root.path("input").size()).isEqualTo(originalMessageCount + 1);
        JsonNode instruction = root.path("input").get(originalMessageCount);
        assertThat(instruction.path("role").asText()).isEqualTo("system");
        assertThat(instruction.path("content").get(0).path("text").asText())
            .contains("compact summary", "不要调用工具");
    }

    private static SessionHandle appendTurn(
        SessionHandle handle,
        SessionManagerImpl session,
        int index,
        String userText,
        String assistantText
    ) {
        handle = session.append(new MessageEntry(
            "entry-user-" + index,
            handle.leafId(),
            message("msg-user-" + index, MessageRole.USER, userText + "\n" + detailedTranscript(index, "用户上下文")),
            Instant.EPOCH
        ));
        return session.append(new MessageEntry(
            "entry-assistant-" + index,
            handle.leafId(),
            message(
                "msg-assistant-" + index,
                MessageRole.ASSISTANT,
                assistantText + "\n" + detailedTranscript(index, "助手上下文")
            ),
            Instant.EPOCH
        ));
    }

    private static String detailedTranscript(int index, String label) {
        String detail = label + " " + index
            + "：真实 AI compact summary 验证需要保留 gpt-5.4-mini、thinking low、system prompt、"
            + "mode、permission、message order、cache prefix、abort signal、coordinator fallback、"
            + "实际链路无 token 上限、无 temperature、summary instruction 最后一条追加、"
            + "真实测试输出写入 target/real-compact-summary.txt、子代理 review 使用 gpt-5.4。";
        return (detail + "\n").repeat(74);
    }

    private static void assertCompliantSummary(String summary) {
        assertThat(summary).isNotBlank();
        assertThat(summary).contains("真实 AI compact summary 验证");
        assertThat(summary).containsIgnoringCase("compact");
        assertThat(summary).containsAnyOf("冲突", "merge", "dev");
        assertThat(summary).containsAnyOf("token", "max_output_tokens", "max_tokens", "上限");
        assertThat(summary).containsAnyOf("配置", "model", "thinking", "permission");
        assertThat(summary).doesNotContain("<analysis>", "</analysis>", "<summary>", "</summary>", "```");
        assertThat(summary).doesNotStartWith("当然");
        assertThat(summary).doesNotStartWith("好的");
    }

    private static CompactionEntry appendedCompaction(SessionManagerImpl session, String firstKeptEntryId) {
        return session.currentView().leafId() == null
            ? null
            : session.branch(session.currentView().leafId()).stream()
                .filter(CompactionEntry.class::isInstance)
                .map(CompactionEntry.class::cast)
                .filter(entry -> entry.firstKeptEntryId().equals(firstKeptEntryId))
                .findFirst()
                .orElseThrow();
    }

    private static DefaultModelPort modelPort(RealSummarySettings settings, ProviderTransport sseTransport) {
        ModelDescriptor descriptor = new ModelDescriptor(
            settings.provider(),
            settings.model(),
            settings.baseUrl(),
            ApiStyle.OPENAI_COMPATIBLE,
            256_000,
            // NOTE: Model capability metadata only; summary requests keep maxOutputTokens empty.
            16_384,
            true,
            false,
            new CostProfile(BigDecimal.ZERO, BigDecimal.ZERO, "USD"),
            Map.of()
        );
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter(
            new OpenAiProviderConfig(
                settings.provider(),
                settings.baseUrl(),
                Optional.empty(),
                "/v1/responses",
                settings.apiKey(),
                RequestStyle.RESPONSES,
                RequestStyle.RESPONSES,
                TransportMode.SSE,
                Duration.ofSeconds(120),
                0,
                Map.of()
            ),
            (request, signal) -> {
                throw new IllegalStateException("Summary e2e uses HTTP SSE transport only.");
            },
            sseTransport,
            sseTransport
        );
        return new DefaultModelPort(
            new DefaultModelRegistry(List.of(descriptor)),
            new DefaultApiProviderRegistry(List.of(new ProviderAdapterApiProvider(ApiStyle.OPENAI_COMPATIBLE, List.of(adapter))))
        );
    }

    private static ResourceRuntimePort fixedResourceRuntime(SystemPrompt systemPrompt) {
        return new ResourceRuntimePort() {
            @Override
            public ResourceSnapshot load(Path cwd) {
                return new ResourceSnapshot(List.of(), List.of(), new SkillIndex(List.of(), List.of()), List.of(), List.of(), List.of());
            }

            @Override
            public SystemPrompt buildSystemPrompt(ResourceSnapshot resources) {
                return systemPrompt;
            }
        };
    }

    private static SystemPrompt realSystemPrompt() {
        return new SystemPrompt(
            "系统提示：你正在参与真实 session compact 验证。必须保留事实，不要输出寒暄或包装标签。",
            List.of("real-summary-e2e"),
            "real-summary-e2e"
        );
    }

    private static AgentMessage message(String id, MessageRole role, String text) {
        return new AgentMessage(
            id,
            role,
            MessageKind.TEXT,
            List.of(new TextContentBlock(text)),
            Instant.EPOCH,
            Optional.empty(),
            Optional.empty()
        );
    }

    private static final class RecordingSummarizer implements CompactionSummarizer {
        private final CompactionSummarizer delegate;
        private final List<CompactSummaryRequest> requests = new ArrayList<>();
        private final List<CompactSummaryResult> results = new ArrayList<>();

        private RecordingSummarizer(CompactionSummarizer delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompactSummaryResult summarize(CompactSummaryRequest request) {
            requests.add(request);
            CompactSummaryResult result = delegate.summarize(request);
            results.add(result);
            return result;
        }
    }

    private static final class NoopEventBus implements EventBus {
        @Override
        public void publish(AgentEvent event) {
        }

        @Override
        public EventSubscription subscribe(EventFilter filter, EventConsumer consumer) {
            return () -> {
            };
        }
    }

    private static final class RecordingProviderTransport implements ProviderTransport {
        private final ProviderTransport delegate;
        private final List<String> requestBodies = new ArrayList<>();

        private RecordingProviderTransport(ProviderTransport delegate) {
            this.delegate = delegate;
        }

        @Override
        public ProviderEventStream stream(ProviderRequest request, AbortSignal signal) {
            requestBodies.add(request.body());
            return delegate.stream(request, signal);
        }
    }

    private record RealSummarySettings(
        URI baseUrl,
        String apiKey,
        String provider,
        String model,
        ThinkingLevel thinkingLevel
    ) {
        private static RealSummarySettings fromSystemProperties() {
            return new RealSummarySettings(
                requiredUri("lypi.summary.e2e.base-url"),
                required("lypi.summary.e2e.api-key"),
                System.getProperty("lypi.summary.e2e.provider", "real-summary"),
                required("lypi.summary.e2e.model"),
                ThinkingLevel.valueOf(System.getProperty("lypi.summary.e2e.thinking", "LOW").toUpperCase(java.util.Locale.ROOT))
            );
        }

        private static URI requiredUri(String key) {
            return URI.create(required(key));
        }

        private static String required(String key) {
            String value = System.getProperty(key);
            assumeTrue(value != null && !value.isBlank(), "Missing required system property: " + key);
            return value;
        }
    }
}
