package cn.lycode.boot.ai;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * Persists only providers created through {@code /login}.
 *
 * <p>The file is deliberately separate from user-maintained configuration so a login never rewrites it.</p>
 */
public class LoginProviderPropertiesStore {
    private static final String PROVIDERS_PREFIX = "lycode.ai.providers.";
    private static final List<String> DISCOVERY_PATHS = List.of("/models", "/model");
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS = EnumSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE
    );

    private final Path file;

    public LoginProviderPropertiesStore(Path userHome) {
        this.file = Objects.requireNonNull(userHome, "userHome")
            .resolve(".ly-code")
            .resolve("login-providers.properties");
    }

    public void save(String provider, URI baseUrl, String authKey) throws IOException {
        String requiredProvider = requireProvider(provider);
        URI requiredBaseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        String requiredAuthKey = Objects.requireNonNull(authKey, "authKey");
        Path directory = file.getParent();
        Path temporary = null;
        boolean moved = false;
        try {
            Files.createDirectories(directory);
            Properties properties = readProperties();
            String prefix = PROVIDERS_PREFIX + requiredProvider + ".";
            removeProviderProperties(properties, prefix);
            writeProviderProperties(properties, prefix, requiredBaseUrl, requiredAuthKey);

            temporary = Files.createTempFile(directory, ".login-providers-", ".tmp");
            setPrivatePermissionsIfSupported(temporary);
            writeProperties(temporary, properties);
            moveReplacing(temporary, file);
            moved = true;
            setPrivatePermissionsIfSupported(file);
        } catch (IOException | RuntimeException ignored) {
            throw persistenceFailure();
        } finally {
            if (!moved && temporary != null) {
                deleteQuietly(temporary);
            }
        }
    }

    private Properties readProperties() throws IOException {
        Properties properties = new Properties();
        if (!Files.exists(file)) {
            return properties;
        }
        try (java.io.InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        return properties;
    }

    private static void removeProviderProperties(Properties properties, String prefix) {
        for (String propertyName : List.copyOf(properties.stringPropertyNames())) {
            if (propertyName.startsWith(prefix)) {
                properties.remove(propertyName);
            }
        }
    }

    private static void writeProviderProperties(
        Properties properties,
        String prefix,
        URI baseUrl,
        String authKey
    ) {
        properties.setProperty(prefix + "enabled", "true");
        properties.setProperty(prefix + "api-style", "openai_compatible");
        properties.setProperty(prefix + "base-url", baseUrl.toString());
        properties.setProperty(prefix + "api-key", authKey);
        properties.setProperty(prefix + "request-style", "chat_completions");
        properties.setProperty(prefix + "fallback-request-style", "chat_completions");
        properties.setProperty(prefix + "transport", "sse");
        properties.setProperty(prefix + "model-discovery.enabled", "true");
        for (int index = 0; index < DISCOVERY_PATHS.size(); index++) {
            properties.setProperty(prefix + "model-discovery.paths[" + index + "]", DISCOVERY_PATHS.get(index));
        }
        properties.setProperty(prefix + "compat.requires-reasoning-content-on-assistant-messages", "true");
    }

    private static void writeProperties(Path temporary, Properties properties) throws IOException {
        try (OutputStream output = Files.newOutputStream(temporary)) {
            properties.store(output, null);
        }
    }

    private static void moveReplacing(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void setPrivatePermissionsIfSupported(Path path) throws IOException {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(path, PRIVATE_FILE_PERMISSIONS);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The temporary path name is random and contains no provider credentials.
        }
    }

    private static String requireProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider is required");
        }
        return provider;
    }

    private static IOException persistenceFailure() {
        return new IOException("Provider login properties could not be saved.");
    }
}
