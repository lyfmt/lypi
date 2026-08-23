package cn.lycode.boot.tool;

import cn.lycode.contracts.runtime.NetworkMode;
import cn.lycode.tool.builtin.ShellEnvironmentHarness;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lycode.tool")
public class LyCodeToolProperties {
    private SandboxProperties sandbox = new SandboxProperties();
    private ShellProperties shell = new ShellProperties();

    public SandboxProperties getSandbox() {
        return sandbox;
    }

    public void setSandbox(SandboxProperties sandbox) {
        this.sandbox = sandbox == null ? new SandboxProperties() : sandbox;
    }

    public ShellProperties getShell() {
        return shell;
    }

    public void setShell(ShellProperties shell) {
        this.shell = shell == null ? new ShellProperties() : shell;
    }

    public static class SandboxProperties {
        private boolean enabled = true;
        private NetworkMode networkMode = NetworkMode.DISABLED;
        private boolean failIfUnavailable;
        private boolean autoAllowBashIfSandboxed;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public NetworkMode getNetworkMode() {
            return networkMode;
        }

        public void setNetworkMode(NetworkMode networkMode) {
            this.networkMode = networkMode == null ? NetworkMode.DISABLED : networkMode;
        }

        public boolean isFailIfUnavailable() {
            return failIfUnavailable;
        }

        public void setFailIfUnavailable(boolean failIfUnavailable) {
            this.failIfUnavailable = failIfUnavailable;
        }

        public boolean isAutoAllowBashIfSandboxed() {
            return autoAllowBashIfSandboxed;
        }

        public void setAutoAllowBashIfSandboxed(boolean autoAllowBashIfSandboxed) {
            this.autoAllowBashIfSandboxed = autoAllowBashIfSandboxed;
        }
    }

    public static class ShellProperties {
        private Path stateRoot = ShellEnvironmentHarness.defaultStateRoot();

        public Path getStateRoot() {
            return stateRoot;
        }

        public void setStateRoot(Path stateRoot) {
            this.stateRoot = stateRoot == null ? ShellEnvironmentHarness.defaultStateRoot() : stateRoot;
        }
    }
}
