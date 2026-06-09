package cn.lypi.boot.tool;

import cn.lypi.contracts.runtime.NetworkMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lypi.tool")
public class LyPiToolProperties {
    private SandboxProperties sandbox = new SandboxProperties();

    public SandboxProperties getSandbox() {
        return sandbox;
    }

    public void setSandbox(SandboxProperties sandbox) {
        this.sandbox = sandbox == null ? new SandboxProperties() : sandbox;
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
}
