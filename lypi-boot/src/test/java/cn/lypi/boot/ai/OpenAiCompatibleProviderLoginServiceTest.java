package cn.lypi.boot.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lypi.ai.DefaultModelRegistry;
import cn.lypi.ai.ProviderAdapterApiProvider;
import cn.lypi.ai.RuntimeModelRegistry;
import cn.lypi.ai.model.RemoteModelDiscoveryClient;
import cn.lypi.ai.provider.ProviderRequest;
import cn.lypi.ai.provider.RequestStyle;
import cn.lypi.ai.provider.TransportMode;
import cn.lypi.ai.provider.openai.OpenAiCompatibleProviderAdapter;
import cn.lypi.ai.provider.openai.OpenAiProviderConfig;
import cn.lypi.contracts.error.ModelProviderException;
import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.CostProfile;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.runtime.ProviderLoginResult;
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
            respond(exchange, 200, "{\"data\":[{\"id\":\"zeta\"},{\"id\":\"alpha\"}]}");
        });
        RuntimeModelRegistry registry = new DefaultModelRegistry(List.of());
        ProviderAdapterApiProvider dispatcher = new ProviderAdapterApiProvider(ApiStyle.OPENAI_COMPATIBLE, List.of());
        Path home = tempDir.resolve("home");
        OpenAiCompatibleProviderLoginService service = service(registry, dispatcher, home);

        ProviderLoginResult result = service.register(baseUrl().toString() + "/", AUTH_KEY);
        Path storeFile = home.resolve(".ly-pi/login-providers.properties");

        assertThat(("Bearer " + AUTH_KEY).equals(authorization.get())).isTrue();
        assertThat(result.models()).extracting(ModelDescriptor::modelId).containsExactly("alpha", "zeta");
        assertThat(result.models()).allSatisfy(model -> {
            assertThat(model.baseUrl()).isEqualTo(baseUrl());
            assertThat(model.supportsThinking()).isFalse();
        });
        assertThat(registry.list()).containsExactlyElementsOf(result.models());
        assertThat(storeFile).exists();
        assertThat(properties(storeFile).stringPropertyNames())
            .contains(
                "lypi.ai.providers." + result.provider() + ".request-style",
                "lypi.ai.providers." + result.provider() + ".fallback-request-style",
                "lypi.ai.providers." + result.provider() + ".transport"
            );
        assertThat(properties(storeFile).getProperty(
            "lypi.ai.providers." + result.provider() + ".request-style"
        )).isEqualTo("chat_completions");
        assertThat(properties(storeFile).getProperty(
            "lypi.ai.providers." + result.provider() + ".fallback-request-style"
        )).isEqualTo("chat_completions");
        assertThat(properties(storeFile).getProperty(
            "lypi.ai.providers." + result.provider() + ".transport"
        )).isEqualTo("sse");
        assertThat(config(dispatcher, result.provider()).requestStyle()).isEqualTo(RequestStyle.CHAT_COMPLETIONS);
        assertThat(config(dispatcher, result.provider()).fallbackRequestStyle()).isEqualTo(RequestStyle.CHAT_COMPLETIONS);
        assertThat(config(dispatcher, result.provider()).transportMode()).isEqualTo(TransportMode.SSE);
        assertThat(config(dispatcher, result.provider()).toString().contains(AUTH_KEY)).isFalse();
        assertPrivateFile(storeFile);
    }

    @Test
    void fallsBackToModelEndpointAndReplacesTheSameProvider() throws Exception {
        AtomicReference<Integer> calls = new AtomicReference<>(0);
        List<String> requestedPaths = new CopyOnWriteArrayList<>();
        startServer(exchange -> {
            requestedPaths.add(exchange.getRequestURI().getPath());
            if (exchange.getRequestURI().getPath().endsWith("/models")) {
                respond(exchange, 404, "");
                return;
            }
            int call = calls.updateAndGet(value -> value + 1);
            respond(exchange, 200, call == 1
                ? "{\"models\":[{\"id\":\"old-model\"}]}"
                : "{\"models\":[{\"id\":\"new-model\"}]}");
        });
        RuntimeModelRegistry registry = new DefaultModelRegistry(List.of());
        ProviderAdapterApiProvider dispatcher = new ProviderAdapterApiProvider(ApiStyle.OPENAI_COMPATIBLE, List.of());
        Path home = tempDir.resolve("home");
        OpenAiCompatibleProviderLoginService service = service(registry, dispatcher, home);

        ProviderLoginResult first = service.register(baseUrl().toString(), AUTH_KEY);
        OpenAiCompatibleProviderAdapter firstAdapter = adapter(dispatcher, first.provider());
        ProviderLoginResult second = service.register(baseUrl().toString(), AUTH_KEY);

        assertThat(second.provider()).isEqualTo(first.provider());
        assertThat(registry.list())
            .filteredOn(model -> model.provider().equals(first.provider()))
            .extracting(ModelDescriptor::modelId)
            .containsExactly("new-model");
        assertThat(properties(home.resolve(".ly-pi/login-providers.properties")).values())
            .doesNotContain("old-model");
        assertThat(calls.get()).isEqualTo(2);
        assertThat(requestedPaths).containsExactly("/v1/models", "/v1/model", "/v1/models", "/v1/model");
        assertThat(adapter(dispatcher, second.provider())).isNotSameAs(firstAdapter);
    }

    @Test
    void leavesFileAndRuntimeUnchangedWhenDiscoveryFails() throws Exception {
        startServer(exchange -> respond(exchange, 401, ""));
        RuntimeModelRegistry registry = new DefaultModelRegistry(List.of(existingModel()));
        ProviderAdapterApiProvider dispatcher = new ProviderAdapterApiProvider(ApiStyle.OPENAI_COMPATIBLE, List.of());
        Path home = tempDir.resolve("home");
        OpenAiCompatibleProviderLoginService service = service(registry, dispatcher, home);

        assertThatThrownBy(() -> service.register(baseUrl().toString(), AUTH_KEY))
            .isInstanceOf(ModelProviderException.class)
            .hasMessageNotContaining(AUTH_KEY);

        assertThat(registry.list()).containsExactly(existingModel());
        assertThat(home.resolve(".ly-pi/login-providers.properties")).doesNotExist();
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

        assertThatThrownBy(() -> service.register(baseUrl().toString(), AUTH_KEY))
            .isInstanceOf(ModelProviderException.class)
            .hasMessageNotContaining(AUTH_KEY);

        assertThat(requestedPaths).containsExactly("/v1/models", "/v1/model");
        assertThat(registry.list()).containsExactly(existingModel());
        assertThat(adapterCount(dispatcher)).isZero();
        assertThat(home.resolve(".ly-pi/login-providers.properties")).doesNotExist();
    }

    @Test
    void leavesExistingStateUntouchedWhenPersistenceFails() throws Exception {
        RuntimeModelRegistry registry = new DefaultModelRegistry(List.of(existingModel()));
        ProviderAdapterApiProvider dispatcher = new ProviderAdapterApiProvider(ApiStyle.OPENAI_COMPATIBLE, List.of());
        Path home = tempDir.resolve("home");
        Path storeFile = home.resolve(".ly-pi/login-providers.properties");
        Files.createDirectories(storeFile.getParent());
        Files.writeString(storeFile, "existing.property=preserved\n");
        LoginProviderPropertiesStore failingStore = new LoginProviderPropertiesStore(home) {
            @Override
            public void save(String provider, URI baseUrl, String authKey, List<String> modelIds) throws IOException {
                throw new IOException("simulated persistence failure");
            }
        };
        RemoteModelDiscoveryClient discovery = new RemoteModelDiscoveryClient() {
            @Override
            public List<String> discover(URI baseUrl, String apiKey, List<String> paths, java.time.Duration timeout) {
                return List.of("verified-model");
            }
        };
        OpenAiCompatibleProviderLoginService service = service(registry, dispatcher, discovery, failingStore);

        assertThatThrownBy(() -> service.register("https://example.test/v1", AUTH_KEY))
            .isInstanceOf(ModelProviderException.class)
            .hasMessageNotContaining(AUTH_KEY);

        assertThat(Files.readString(storeFile)).isEqualTo("existing.property=preserved\n");
        assertThat(registry.list()).containsExactly(existingModel());
        assertThat(adapterCount(dispatcher)).isZero();
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
            assertThatThrownBy(() -> service.register(invalidUrl, AUTH_KEY))
                .isInstanceOf(ModelProviderException.class)
                .hasMessageNotContaining(invalidUrl);
        }
        assertThatThrownBy(() -> service.register("https://example.test/v1", "  "))
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
            public List<String> discover(URI baseUrl, String apiKey, List<String> paths, java.time.Duration timeout) {
                throw new ModelProviderException(
                    "test.unsafe_discovery",
                    cn.lypi.contracts.error.ErrorSeverity.ERROR,
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

        assertThatThrownBy(() -> service.register("https://example.test/v1", AUTH_KEY))
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
        return new OpenAiCompatibleProviderLoginService(discovery, registry, dispatcher, store);
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
