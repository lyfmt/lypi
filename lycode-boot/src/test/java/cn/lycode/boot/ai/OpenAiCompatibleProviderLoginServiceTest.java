package cn.lycode.boot.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lycode.ai.DefaultModelRegistry;
import cn.lycode.ai.ProviderAdapterApiProvider;
import cn.lycode.ai.RuntimeModelRegistry;
import cn.lycode.ai.model.DiscoveredModel;
import cn.lycode.ai.model.DiscoveredModelDefaults;
import cn.lycode.ai.model.RemoteModelDiscoveryClient;
import cn.lycode.ai.provider.ProviderRequest;
import cn.lycode.ai.provider.RequestStyle;
import cn.lycode.ai.provider.TransportMode;
import cn.lycode.ai.provider.openai.OpenAiCompatibleProviderAdapter;
import cn.lycode.ai.provider.openai.OpenAiProviderConfig;
import cn.lycode.contracts.error.ModelProviderException;
import cn.lycode.contracts.model.ApiStyle;
import cn.lycode.contracts.model.CostProfile;
import cn.lycode.contracts.model.ModelDescriptor;
import cn.lycode.contracts.runtime.ProviderLoginResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenAiCompatibleProviderLoginServiceTest {
    private static final String AUTH_KEY = "test-auth-key";

    @TempDir
    Path tempDir;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void persistsAndRegistersOnlyAfterModelDiscoverySucceeds() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        startServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, """
                {"data":[
                  {
                    "id":"zeta",
                    "context_window":128000,
                    "max_output_tokens":16384,
                    "supports_reasoning":false,
                    "supports_image_input":false
                  },
                  {"id":"alpha"}
                ]}
                """);
        });
        RuntimeModelRegistry registry = new DefaultModelRegistry(List.of());
        ProviderAdapterApiProvider dispatcher = new ProviderAdapterApiProvider(ApiStyle.OPENAI_COMPATIBLE, List.of());
        Path home = tempDir.resolve("home");
        OpenAiCompatibleProviderLoginService service = service(registry, dispatcher, home);

        ProviderLoginResult result = service.register("zen", baseUrl().toString() + "/", AUTH_KEY);
        Path storeFile = home.resolve(".ly-code/login-providers.properties");

        assertThat(("Bearer " + AUTH_KEY).equals(authorization.get())).isTrue();
        assertThat(result.provider()).isEqualTo("zen");
        assertThat(result.models()).extracting(ModelDescriptor::modelId).containsExactly("zeta", "alpha");
        assertThat(result.models().get(0)).satisfies(model -> {
            assertThat(model.provider()).isEqualTo("zen");
            assertThat(model.baseUrl()).isEqualTo(baseUrl());
            assertThat(model.contextWindow()).isEqualTo(128_000);
            assertThat(model.maxOutputTokens()).isEqualTo(16_384);
            assertThat(model.supportsThinking()).isFalse();
            assertThat(model.supportsImageInput()).isFalse();
        });
        assertThat(result.models().get(1)).satisfies(model -> {
            assertThat(model.provider()).isEqualTo("zen");
            assertThat(model.contextWindow()).isEqualTo(256_000);
            assertThat(model.maxOutputTokens()).isEqualTo(8_192);
            assertThat(model.supportsThinking()).isTrue();
            assertThat(model.supportsImageInput()).isTrue();
        });
        assertThat(registry.list()).containsExactlyElementsOf(result.models());
        assertThat(storeFile).exists();
        Properties stored = properties(storeFile);
        assertThat(stored.stringPropertyNames())
            .contains(
                "lycode.ai.providers." + result.provider() + ".request-style",
                "lycode.ai.providers." + result.provider() + ".fallback-request-style",
                "lycode.ai.providers." + result.provider() + ".transport"
            );
        assertThat(stored.getProperty(
            "lycode.ai.providers." + result.provider() + ".request-style"
        )).isEqualTo("chat_completions");
        assertThat(stored.getProperty(
            "lycode.ai.providers." + result.provider() + ".fallback-request-style"
        )).isEqualTo("chat_completions");
        assertThat(stored.getProperty(
            "lycode.ai.providers." + result.provider() + ".transport"
        )).isEqualTo("sse");
        assertThat(stored.getProperty(
            "lycode.ai.providers." + result.provider()
                + ".compat.requires-reasoning-content-on-assistant-messages"
        )).isEqualTo("true");
        assertThat(stored.stringPropertyNames())
            .noneMatch(name -> name.contains(".models[") || name.endsWith("supports-thinking"));
        assertThat(config(dispatcher, result.provider()).requestStyle()).isEqualTo(RequestStyle.CHAT_COMPLETIONS);
        assertThat(config(dispatcher, result.provider()).fallbackRequestStyle()).isEqualTo(RequestStyle.CHAT_COMPLETIONS);
        assertThat(config(dispatcher, result.provider()).transportMode()).isEqualTo(TransportMode.SSE);
        assertThat(config(dispatcher, result.provider()).compat())
            .containsEntry("requires-reasoning-content-on-assistant-messages", true);
        assertThat(config(dispatcher, result.provider()).toString().contains(AUTH_KEY)).isFalse();
        assertPrivateFile(storeFile);
    }

    @Test
    void fallsBackToModelEndpointAndReplacesOnlyTheNamedProvider() throws Exception {
        AtomicReference<Integer> calls = new AtomicReference<>(0);
        List<String> requestedPaths = new CopyOnWriteArrayList<>();
        startServer(exchange -> {
            requestedPaths.add(exchange.getRequestURI().getPath());
            if (exchange.getRequestURI().getPath().endsWith("/models")) {
                respond(exchange, 404, "");
                return;
            }
            int call = calls.updateAndGet(value -> value + 1);
            String modelId = switch (call) {
                case 1 -> "old-model";
                case 2 -> "other-model";
                default -> "new-model";
            };
            respond(exchange, 200, "{\"models\":[{\"id\":\"" + modelId + "\"}]}");
        });
        RuntimeModelRegistry registry = new DefaultModelRegistry(List.of());
        ProviderAdapterApiProvider dispatcher = new ProviderAdapterApiProvider(ApiStyle.OPENAI_COMPATIBLE, List.of());
        Path home = tempDir.resolve("home");
        OpenAiCompatibleProviderLoginService service = service(registry, dispatcher, home);

        ProviderLoginResult first = service.register("zen-a", baseUrl().toString(), AUTH_KEY);
        OpenAiCompatibleProviderAdapter firstAdapter = adapter(dispatcher, first.provider());
        ProviderLoginResult other = service.register("zen-b", baseUrl().toString(), AUTH_KEY);
        OpenAiCompatibleProviderAdapter otherAdapter = adapter(dispatcher, other.provider());
        String replacementKey = "replacement-auth-key";
        ProviderLoginResult second = service.register("zen-a", baseUrl().toString(), replacementKey);

        assertThat(second.provider()).isEqualTo(first.provider());
        assertThat(registry.list())
            .filteredOn(model -> model.provider().equals(first.provider()))
            .extracting(ModelDescriptor::modelId)
            .containsExactly("new-model");
        assertThat(registry.list())
            .filteredOn(model -> model.provider().equals(other.provider()))
            .extracting(ModelDescriptor::modelId)
            .containsExactly("other-model");
        Properties stored = properties(home.resolve(".ly-code/login-providers.properties"));
        assertThat(stored.stringPropertyNames())
            .anyMatch(name -> name.startsWith("lycode.ai.providers.zen-a."))
            .anyMatch(name -> name.startsWith("lycode.ai.providers.zen-b."));
        assertThat(stored.getProperty("lycode.ai.providers.zen-a.api-key")).isEqualTo(replacementKey);
        assertThat(stored.getProperty("lycode.ai.providers.zen-b.api-key")).isEqualTo(AUTH_KEY);
        assertThat(calls.get()).isEqualTo(3);
        assertThat(requestedPaths).containsExactly(
            "/v1/models", "/v1/model",
            "/v1/models", "/v1/model",
            "/v1/models", "/v1/model"
        );
        assertThat(adapter(dispatcher, second.provider())).isNotSameAs(firstAdapter);
        assertThat(adapter(dispatcher, other.provider())).isSameAs(otherAdapter);
        assertThat(config(dispatcher, second.provider()).apiKey()).isEqualTo(replacementKey);
        assertThat(config(dispatcher, other.provider()).apiKey()).isEqualTo(AUTH_KEY);
    }

    @Test
    void leavesFileAndRuntimeUnchangedWhenDiscoveryFails() throws Exception {
        startServer(exchange -> respond(exchange, 401, ""));
        RuntimeModelRegistry registry = new DefaultModelRegistry(List.of(existingModel()));
        ProviderAdapterApiProvider dispatcher = new ProviderAdapterApiProvider(ApiStyle.OPENAI_COMPATIBLE, List.of());
        Path home = tempDir.resolve("home");
        OpenAiCompatibleProviderLoginService service = service(registry, dispatcher, home);

        assertThatThrownBy(() -> service.register("zen", baseUrl().toString(), AUTH_KEY))
            .isInstanceOf(ModelProviderException.class)
            .hasMessageNotContaining(AUTH_KEY);

        assertThat(registry.list()).containsExactly(existingModel());
        assertThat(home.resolve(".ly-code/login-providers.properties")).doesNotExist();
        assertThat(adapterCount(dispatcher)).isZero();
    }

    @Test
    void leavesFileAndRuntimeUnchangedWhenBothDiscoveryEndpointsHaveNoModels() throws Exception {
        List<String> requestedPaths = new CopyOnWriteArrayList<>();
        startServer(exchange -> {
            requestedPaths.add(exchange.getRequestURI().getPath());
            respond(exchange, 200, "{\"data\":[]}");
        });
        RuntimeModelRegistry registry = new DefaultModelRegistry(List.of(existingModel()));
        ProviderAdapterApiProvider dispatcher = new ProviderAdapterApiProvider(ApiStyle.OPENAI_COMPATIBLE, List.of());
        Path home = tempDir.resolve("home");
        OpenAiCompatibleProviderLoginService service = service(registry, dispatcher, home);

        assertThatThrownBy(() -> service.register("zen", baseUrl().toString(), AUTH_KEY))
            .isInstanceOf(ModelProviderException.class)
            .hasMessageNotContaining(AUTH_KEY);

        assertThat(requestedPaths).containsExactly("/v1/models", "/v1/model");
        assertThat(registry.list()).containsExactly(existingModel());
        assertThat(adapterCount(dispatcher)).isZero();
        assertThat(home.resolve(".ly-code/login-providers.properties")).doesNotExist();
    }

    @Test
    void leavesExistingStateUntouchedWhenPersistenceFails() throws Exception {
        RuntimeModelRegistry registry = new DefaultModelRegistry(List.of());
        ProviderAdapterApiProvider dispatcher = new ProviderAdapterApiProvider(ApiStyle.OPENAI_COMPATIBLE, List.of());
        Path home = tempDir.resolve("home");
        Path storeFile = home.resolve(".ly-code/login-providers.properties");
        OpenAiCompatibleProviderLoginService initialService = service(
            registry,
            dispatcher,
            fixedDiscovery("old-model"),
            new LoginProviderPropertiesStore(home)
        );
        ProviderLoginResult initial = initialService.register(
            "zen",
            "https://old.example.test/v1",
            "old-key"
        );
        String initialFile = Files.readString(storeFile);
        OpenAiCompatibleProviderAdapter initialAdapter = adapter(dispatcher, "zen");

        LoginProviderPropertiesStore failingStore = new LoginProviderPropertiesStore(home) {
            @Override
            public void save(String provider, URI baseUrl, String authKey) throws IOException {
                throw new IOException("simulated persistence failure");
            }
        };
        OpenAiCompatibleProviderLoginService service = service(
            registry,
            dispatcher,
            fixedDiscovery("verified-model"),
            failingStore
        );

        String sensitiveUrl = "https://example.test/private-path";

        assertThatThrownBy(() -> service.register("zen", sensitiveUrl, AUTH_KEY))
            .isInstanceOf(ModelProviderException.class)
            .hasMessageNotContaining("zen")
            .hasMessageNotContaining(sensitiveUrl)
            .hasMessageNotContaining(AUTH_KEY);

        assertThat(Files.readString(storeFile)).isEqualTo(initialFile);
        assertThat(registry.list()).containsExactlyElementsOf(initial.models());
        assertThat(adapter(dispatcher, "zen")).isSameAs(initialAdapter);
        assertThat(adapterCount(dispatcher)).isOne();
    }

    @Test
    void rejectsInvalidChannelNamesBeforeDiscoveryPersistenceOrRuntimeMutation() {
        AtomicInteger discoveryCalls = new AtomicInteger();
        RemoteModelDiscoveryClient discovery = new RemoteModelDiscoveryClient() {
            @Override
            public List<DiscoveredModel> discoverModels(
                URI baseUrl,
                String apiKey,
                List<String> paths,
                java.time.Duration timeout
            ) {
                discoveryCalls.incrementAndGet();
                return List.of(DiscoveredModel.idOnly("unexpected"));
            }
        };
        RuntimeModelRegistry registry = new DefaultModelRegistry(List.of(existingModel()));
        ProviderAdapterApiProvider dispatcher = new ProviderAdapterApiProvider(ApiStyle.OPENAI_COMPATIBLE, List.of());
        Path home = tempDir.resolve("home");
        OpenAiCompatibleProviderLoginService service = service(
            registry,
            dispatcher,
            discovery,
            new LoginProviderPropertiesStore(home)
        );

        for (String invalid : List.of("Zen", "with space", ".nested", "a/b", "a".repeat(65))) {
            assertThatThrownBy(() -> service.register(invalid, "not-a-url", " "))
                .isInstanceOfSatisfying(ModelProviderException.class, error ->
                    assertThat(error.errorId()).isEqualTo("provider.login_invalid_channel_name"))
                .hasMessage("Provider channel name must match [a-z0-9][a-z0-9_-]{0,63}.")
                .hasMessageNotContaining(invalid);
        }
        assertThatThrownBy(() -> service.register(" ", "not-a-url", " "))
            .isInstanceOfSatisfying(ModelProviderException.class, error ->
                assertThat(error.errorId()).isEqualTo("provider.login_invalid_channel_name"))
            .hasMessage("Provider channel name must match [a-z0-9][a-z0-9_-]{0,63}.");
        assertThatThrownBy(() -> service.register(null, "not-a-url", " "))
            .isInstanceOfSatisfying(ModelProviderException.class, error ->
                assertThat(error.errorId()).isEqualTo("provider.login_invalid_channel_name"));

        assertThat(discoveryCalls).hasValue(0);
        assertThat(registry.list()).containsExactly(existingModel());
        assertThat(adapterCount(dispatcher)).isZero();
        assertThat(home.resolve(".ly-code/login-providers.properties")).doesNotExist();
    }

    @Test
    void appliesInjectedDefaultsToIdOnlyDiscoveredModels() {
        RuntimeModelRegistry registry = new DefaultModelRegistry(List.of());
        ProviderAdapterApiProvider dispatcher = new ProviderAdapterApiProvider(ApiStyle.OPENAI_COMPATIBLE, List.of());
        RemoteModelDiscoveryClient discovery = new RemoteModelDiscoveryClient() {
            @Override
            public List<DiscoveredModel> discoverModels(
                URI baseUrl,
                String apiKey,
                List<String> paths,
                java.time.Duration timeout
            ) {
                return List.of(DiscoveredModel.idOnly("defaulted"));
            }
        };
        DiscoveredModelDefaults defaults = new DiscoveredModelDefaults(
            192_000,
            12_288,
            true,
            false,
            new CostProfile(java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "USD"),
            Map.of()
        );
        OpenAiCompatibleProviderLoginService service = new OpenAiCompatibleProviderLoginService(
            discovery,
            registry,
            dispatcher,
            new LoginProviderPropertiesStore(tempDir.resolve("home")),
            defaults
        );

        ModelDescriptor descriptor = service.register(
            "zen",
            "https://example.test/v1",
            AUTH_KEY
        ).models().getFirst();

        assertThat(descriptor.contextWindow()).isEqualTo(192_000);
        assertThat(descriptor.maxOutputTokens()).isEqualTo(12_288);
        assertThat(descriptor.supportsThinking()).isTrue();
        assertThat(descriptor.supportsImageInput()).isFalse();
    }

    @Test
    void rejectsInvalidUrlAndBlankKeyBeforeMutatingRuntime() {
        RuntimeModelRegistry registry = new DefaultModelRegistry(List.of(existingModel()));
        ProviderAdapterApiProvider dispatcher = new ProviderAdapterApiProvider(ApiStyle.OPENAI_COMPATIBLE, List.of());
        OpenAiCompatibleProviderLoginService service = service(registry, dispatcher, tempDir.resolve("home"));

        for (String invalidUrl : List.of(
            "https://user:pass@example.test/v1",
            "https://example.test/v1?tenant=test",
            "https://example.test/v1#fragment",
            "ftp://example.test/v1"
        )) {
            assertThatThrownBy(() -> service.register("zen", invalidUrl, AUTH_KEY))
                .isInstanceOf(ModelProviderException.class)
                .hasMessageNotContaining(invalidUrl);
        }
        assertThatThrownBy(() -> service.register("zen", "https://example.test/v1", "  "))
            .isInstanceOf(ModelProviderException.class)
            .hasMessageNotContaining(AUTH_KEY);

        assertThat(registry.list()).containsExactly(existingModel());
        assertThat(adapterCount(dispatcher)).isZero();
    }

    @Test
    void redactsAuthKeyFromDiscoveryFailuresAndProviderRequestStrings() {
        RuntimeModelRegistry registry = new DefaultModelRegistry(List.of(existingModel()));
        ProviderAdapterApiProvider dispatcher = new ProviderAdapterApiProvider(ApiStyle.OPENAI_COMPATIBLE, List.of());
        RemoteModelDiscoveryClient unsafeDiscovery = new RemoteModelDiscoveryClient() {
            @Override
            public List<DiscoveredModel> discoverModels(
                URI baseUrl,
                String apiKey,
                List<String> paths,
                java.time.Duration timeout
            ) {
                throw new ModelProviderException(
                    "test.unsafe_discovery",
                    cn.lycode.contracts.error.ErrorSeverity.ERROR,
                    false,
                    "unsafe discovery " + apiKey
                );
            }
        };
        OpenAiCompatibleProviderLoginService service = service(
            registry,
            dispatcher,
            unsafeDiscovery,
            new LoginProviderPropertiesStore(tempDir.resolve("home"))
        );
        ProviderRequest request = new ProviderRequest(
            URI.create("https://example.test/v1/chat/completions"),
            Map.of("Authorization", "Bearer " + AUTH_KEY),
            "{}"
        );

        assertThatThrownBy(() -> service.register("zen", "https://example.test/v1", AUTH_KEY))
            .isInstanceOf(ModelProviderException.class)
            .hasMessageNotContaining(AUTH_KEY);
        assertThat(request.toString().contains(AUTH_KEY)).isFalse();
        assertThat(registry.list()).containsExactly(existingModel());
        assertThat(adapterCount(dispatcher)).isZero();
    }

    private OpenAiCompatibleProviderLoginService service(
        RuntimeModelRegistry registry,
        ProviderAdapterApiProvider dispatcher,
        Path home
    ) {
        return service(registry, dispatcher, new RemoteModelDiscoveryClient(), new LoginProviderPropertiesStore(home));
    }

    private OpenAiCompatibleProviderLoginService service(
        RuntimeModelRegistry registry,
        ProviderAdapterApiProvider dispatcher,
        RemoteModelDiscoveryClient discovery,
        LoginProviderPropertiesStore store
    ) {
        return new OpenAiCompatibleProviderLoginService(discovery, registry, dispatcher, store, defaults());
    }

    private static DiscoveredModelDefaults defaults() {
        return new DiscoveredModelDefaults(
            DiscoveredModelDefaults.DEFAULT_CONTEXT_WINDOW,
            DiscoveredModelDefaults.DEFAULT_MAX_OUTPUT_TOKENS,
            DiscoveredModelDefaults.DEFAULT_SUPPORTS_THINKING,
            DiscoveredModelDefaults.DEFAULT_SUPPORTS_IMAGE_INPUT,
            new CostProfile(java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "USD"),
            Map.of()
        );
    }

    private static RemoteModelDiscoveryClient fixedDiscovery(String modelId) {
        return new RemoteModelDiscoveryClient() {
            @Override
            public List<DiscoveredModel> discoverModels(
                URI baseUrl,
                String apiKey,
                List<String> paths,
                java.time.Duration timeout
            ) {
                return List.of(DiscoveredModel.idOnly(modelId));
            }
        };
    }

    private URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", handler::handle);
        server.createContext("/v1/model", handler::handle);
        server.start();
    }

    private static Properties properties(Path path) throws IOException {
        Properties properties = new Properties();
        try (java.io.InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static OpenAiProviderConfig config(ProviderAdapterApiProvider dispatcher, String provider) {
        try {
            OpenAiCompatibleProviderAdapter adapter = adapter(dispatcher, provider);
            Field configField = OpenAiCompatibleProviderAdapter.class.getDeclaredField("config");
            configField.setAccessible(true);
            return (OpenAiProviderConfig) configField.get(adapter);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Unable to inspect registered provider adapter", error);
        }
    }

    private static OpenAiCompatibleProviderAdapter adapter(ProviderAdapterApiProvider dispatcher, String provider) {
        return (OpenAiCompatibleProviderAdapter) adapters(dispatcher).get(provider);
    }

    private static int adapterCount(ProviderAdapterApiProvider dispatcher) {
        return adapters(dispatcher).size();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> adapters(ProviderAdapterApiProvider dispatcher) {
        try {
            Field adaptersField = ProviderAdapterApiProvider.class.getDeclaredField("adapters");
            adaptersField.setAccessible(true);
            java.util.concurrent.atomic.AtomicReference<Map<String, ?>> adapters =
                (java.util.concurrent.atomic.AtomicReference<Map<String, ?>>) adaptersField.get(dispatcher);
            return adapters.get();
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Unable to inspect registered provider adapters", error);
        }
    }

    private static void assertPrivateFile(Path file) throws IOException {
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) == null) {
            return;
        }
        assertThat(Files.getPosixFilePermissions(file))
            .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    private static ModelDescriptor existingModel() {
        return new ModelDescriptor(
            "existing",
            "existing-model",
            URI.create("https://existing.test/v1"),
            ApiStyle.OPENAI_COMPATIBLE,
            0,
            0,
            false,
            false,
            new CostProfile(java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "USD"),
            Map.of()
        );
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
