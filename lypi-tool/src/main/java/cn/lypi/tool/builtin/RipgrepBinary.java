package cn.lypi.tool.builtin;

record RipgrepBinary(String command, String mode) {
    RipgrepBinary {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command must not be blank");
        }
        mode = mode == null || mode.isBlank() ? "vendor" : mode;
    }
}
