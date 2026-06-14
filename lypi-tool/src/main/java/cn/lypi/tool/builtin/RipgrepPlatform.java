package cn.lypi.tool.builtin;

import java.util.Locale;

record RipgrepPlatform(String osName, String osArch) {
    static RipgrepPlatform current() {
        return new RipgrepPlatform(System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    String resourcePath() {
        return "ripgrep/" + platformId() + "/" + executableName();
    }

    String platformId() {
        return architectureName() + "-" + platformName();
    }

    String executableName() {
        return "windows".equals(platformName()) ? "rg.exe" : "rg";
    }

    private String platformName() {
        String normalized = normalize(osName);
        if (normalized.contains("mac") || normalized.contains("darwin")) {
            return "macos";
        }
        if (normalized.contains("win")) {
            return "windows";
        }
        if (normalized.contains("linux")) {
            return "linux";
        }
        return normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private String architectureName() {
        String normalized = normalize(osArch);
        return switch (normalized) {
            case "amd64", "x64", "x8664", "x86-64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
