package cn.lypi.boot.tool;

import cn.lypi.contracts.security.ApprovalMode;
import cn.lypi.contracts.security.ApprovalPolicy;
import cn.lypi.contracts.security.FileSystemAccessMode;
import cn.lypi.contracts.security.FileSystemPath;
import cn.lypi.contracts.security.FileSystemPermissionEntry;
import cn.lypi.contracts.security.FileSystemPermissionPolicy;
import cn.lypi.contracts.security.FileSystemPolicyKind;
import cn.lypi.contracts.security.GranularApprovalPolicy;
import cn.lypi.contracts.security.NetworkPermissionPolicy;
import cn.lypi.contracts.security.NetworkPolicyMode;
import cn.lypi.contracts.security.PermissionProfileConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lypi.permissions")
public class LyPiPermissionsProperties {
    private String defaultPermissions = ":workspace";
    private boolean defaultPermissionsConfigured;
    private ApprovalPolicyProperties approvalPolicy = new ApprovalPolicyProperties();
    private boolean approvalPolicyConfigured;
    private Map<String, ProfileProperties> profiles = new LinkedHashMap<>();

    public String getDefaultPermissions() {
        return defaultPermissions;
    }

    public void setDefaultPermissions(String defaultPermissions) {
        this.defaultPermissionsConfigured = true;
        this.defaultPermissions = defaultPermissions == null || defaultPermissions.isBlank()
            ? ":workspace"
            : defaultPermissions;
    }

    public ApprovalPolicyProperties getApprovalPolicy() {
        return approvalPolicy;
    }

    public void setApprovalPolicy(ApprovalPolicyProperties approvalPolicy) {
        this.approvalPolicyConfigured = approvalPolicy != null;
        this.approvalPolicy = approvalPolicy == null ? new ApprovalPolicyProperties() : approvalPolicy;
    }

    public boolean hasExplicitApprovalPolicyConfig() {
        return approvalPolicyConfigured || approvalPolicy.isConfigured();
    }

    public Map<String, ProfileProperties> getProfiles() {
        return profiles;
    }

    public void setProfiles(Map<String, ProfileProperties> profiles) {
        this.profiles = profiles == null ? new LinkedHashMap<>() : new LinkedHashMap<>(profiles);
    }

    public Map<String, PermissionProfileConfig> profileConfigs() {
        Map<String, PermissionProfileConfig> configs = new LinkedHashMap<>();
        profiles.forEach((id, profile) -> configs.put(id, profile.toConfig()));
        return Map.copyOf(configs);
    }

    public boolean hasExplicitProfileConfig() {
        return defaultPermissionsConfigured || !profiles.isEmpty();
    }

    public static class ApprovalPolicyProperties {
        private ApprovalMode mode = ApprovalMode.ON_REQUEST;
        private GranularApprovalPolicyProperties granular = new GranularApprovalPolicyProperties();
        private boolean configured;

        public ApprovalMode getMode() {
            return mode;
        }

        public void setMode(ApprovalMode mode) {
            this.configured = true;
            this.mode = mode == null ? ApprovalMode.ON_REQUEST : mode;
        }

        public GranularApprovalPolicyProperties getGranular() {
            return granular;
        }

        public void setGranular(GranularApprovalPolicyProperties granular) {
            this.configured = true;
            this.granular = granular == null ? new GranularApprovalPolicyProperties() : granular;
        }

        private boolean isConfigured() {
            return configured || granular.isConfigured();
        }

        public ApprovalPolicy toApprovalPolicy() {
            if (mode != ApprovalMode.GRANULAR) {
                return new ApprovalPolicy(mode);
            }
            return new ApprovalPolicy(mode, Optional.of(granular.toGranularApprovalPolicy()));
        }
    }

    public static class GranularApprovalPolicyProperties {
        private ApprovalMode sandboxApproval = ApprovalMode.ON_REQUEST;
        private ApprovalMode rules = ApprovalMode.ON_REQUEST;
        private ApprovalMode skillApproval = ApprovalMode.ON_REQUEST;
        private ApprovalMode requestPermissions = ApprovalMode.ON_REQUEST;
        private ApprovalMode mcpElicitations = ApprovalMode.ON_REQUEST;
        private boolean configured;

        public ApprovalMode getSandboxApproval() {
            return sandboxApproval;
        }

        public void setSandboxApproval(ApprovalMode sandboxApproval) {
            this.configured = true;
            this.sandboxApproval = defaultOnRequest(sandboxApproval);
        }

        public ApprovalMode getRules() {
            return rules;
        }

        public void setRules(ApprovalMode rules) {
            this.configured = true;
            this.rules = defaultOnRequest(rules);
        }

        public ApprovalMode getSkillApproval() {
            return skillApproval;
        }

        public void setSkillApproval(ApprovalMode skillApproval) {
            this.configured = true;
            this.skillApproval = defaultOnRequest(skillApproval);
        }

        public ApprovalMode getRequestPermissions() {
            return requestPermissions;
        }

        public void setRequestPermissions(ApprovalMode requestPermissions) {
            this.configured = true;
            this.requestPermissions = defaultOnRequest(requestPermissions);
        }

        public ApprovalMode getMcpElicitations() {
            return mcpElicitations;
        }

        public void setMcpElicitations(ApprovalMode mcpElicitations) {
            this.configured = true;
            this.mcpElicitations = defaultOnRequest(mcpElicitations);
        }

        private boolean isConfigured() {
            return configured;
        }

        private GranularApprovalPolicy toGranularApprovalPolicy() {
            return new GranularApprovalPolicy(
                sandboxApproval,
                rules,
                skillApproval,
                requestPermissions,
                mcpElicitations
            );
        }

        private ApprovalMode defaultOnRequest(ApprovalMode mode) {
            return mode == null ? ApprovalMode.ON_REQUEST : mode;
        }
    }

    public static class ProfileProperties {
        private String description = "";
        private String extendsProfile;
        private List<String> workspaceRoots = new ArrayList<>();
        private FileSystemPolicyProperties fileSystem;
        private NetworkPolicyProperties network;

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description == null ? "" : description;
        }

        public String getExtendsProfile() {
            return extendsProfile;
        }

        public void setExtendsProfile(String extendsProfile) {
            this.extendsProfile = extendsProfile;
        }

        public List<String> getWorkspaceRoots() {
            return workspaceRoots;
        }

        public void setWorkspaceRoots(List<String> workspaceRoots) {
            this.workspaceRoots = workspaceRoots == null ? new ArrayList<>() : new ArrayList<>(workspaceRoots);
        }

        public FileSystemPolicyProperties getFileSystem() {
            return fileSystem;
        }

        public void setFileSystem(FileSystemPolicyProperties fileSystem) {
            this.fileSystem = fileSystem;
        }

        public NetworkPolicyProperties getNetwork() {
            return network;
        }

        public void setNetwork(NetworkPolicyProperties network) {
            this.network = network;
        }

        public PermissionProfileConfig toConfig() {
            List<Path> safeWorkspaceRoots = workspaceRoots.stream()
                .map(ProfileProperties::validateWorkspaceRoot)
                .toList();
            return new PermissionProfileConfig(
                description,
                optionalString(extendsProfile),
                safeWorkspaceRoots,
                fileSystem == null ? Optional.empty() : Optional.of(fileSystem.toPolicy()),
                network == null ? Optional.empty() : Optional.of(network.toPolicy())
            );
        }

        private static Path validateWorkspaceRoot(String configuredRoot) {
            if (configuredRoot == null || configuredRoot.isBlank() || configuredRoot.contains("..")) {
                throw new IllegalArgumentException("permission workspace root must be absolute and must not contain '..'");
            }
            Path workspaceRoot = Path.of(configuredRoot);
            if (!workspaceRoot.isAbsolute()) {
                throw new IllegalArgumentException("permission workspace root must be an absolute path");
            }
            return workspaceRoot.normalize();
        }
    }

    public static class FileSystemPolicyProperties {
        private FileSystemPolicyKind kind = FileSystemPolicyKind.RESTRICTED;
        private List<FileSystemPermissionEntryProperties> entries = new ArrayList<>();

        public FileSystemPolicyKind getKind() {
            return kind;
        }

        public void setKind(FileSystemPolicyKind kind) {
            this.kind = kind == null ? FileSystemPolicyKind.RESTRICTED : kind;
        }

        public List<FileSystemPermissionEntryProperties> getEntries() {
            return entries;
        }

        public void setEntries(List<FileSystemPermissionEntryProperties> entries) {
            this.entries = entries == null ? new ArrayList<>() : new ArrayList<>(entries);
        }

        private FileSystemPermissionPolicy toPolicy() {
            return new FileSystemPermissionPolicy(
                kind,
                entries.stream()
                    .map(FileSystemPermissionEntryProperties::toEntry)
                    .toList()
            );
        }
    }

    public static class FileSystemPermissionEntryProperties {
        private FileSystemPathProperties path = new FileSystemPathProperties();
        private FileSystemAccessMode access = FileSystemAccessMode.READ;

        public FileSystemPathProperties getPath() {
            return path;
        }

        public void setPath(FileSystemPathProperties path) {
            this.path = path == null ? new FileSystemPathProperties() : path;
        }

        public FileSystemAccessMode getAccess() {
            return access;
        }

        public void setAccess(FileSystemAccessMode access) {
            this.access = access == null ? FileSystemAccessMode.READ : access;
        }

        private FileSystemPermissionEntry toEntry() {
            return new FileSystemPermissionEntry(path.toPath(), access);
        }
    }

    public static class FileSystemPathProperties {
        private FileSystemPath.Kind kind = FileSystemPath.Kind.EXACT_PATH;
        private String value;

        public FileSystemPath.Kind getKind() {
            return kind;
        }

        public void setKind(FileSystemPath.Kind kind) {
            this.kind = kind == null ? FileSystemPath.Kind.EXACT_PATH : kind;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        private FileSystemPath toPath() {
            return new FileSystemPath(kind, value);
        }
    }

    public static class NetworkPolicyProperties {
        private NetworkPolicyMode mode = NetworkPolicyMode.RESTRICTED;

        public NetworkPolicyMode getMode() {
            return mode;
        }

        public void setMode(NetworkPolicyMode mode) {
            this.mode = mode == null ? NetworkPolicyMode.RESTRICTED : mode;
        }

        private NetworkPermissionPolicy toPolicy() {
            return new NetworkPermissionPolicy(mode);
        }
    }

    private static Optional<String> optionalString(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
