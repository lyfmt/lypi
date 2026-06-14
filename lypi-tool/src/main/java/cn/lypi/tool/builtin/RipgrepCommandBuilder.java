package cn.lypi.tool.builtin;

import java.util.ArrayList;
import java.util.List;

final class RipgrepCommandBuilder {
    private static final List<String> VCS_DIRECTORIES = List.of(".git", ".svn", ".hg", ".bzr", ".jj", ".sl");

    List<String> build(GrepQuery query) {
        List<String> args = new ArrayList<>();
        args.add("--hidden");
        for (String directory : VCS_DIRECTORIES) {
            args.add("--glob");
            args.add("!" + directory);
        }
        args.add("--glob");
        args.add("!target");
        args.add("--glob");
        args.add("!.lypi/sessions/**");
        args.add("--glob");
        args.add("!.lypi/cache/**");
        args.add("--max-columns");
        args.add("500");
        if (query.multiline()) {
            args.add("-U");
            args.add("--multiline-dotall");
        }
        if (query.caseInsensitive()) {
            args.add("-i");
        }
        if (query.outputMode() == GrepOutputMode.FILES_WITH_MATCHES) {
            args.add("-l");
        } else if (query.outputMode() == GrepOutputMode.COUNT) {
            args.add("-c");
        }
        if (query.outputMode() == GrepOutputMode.CONTENT && query.showLineNumbers()) {
            args.add("-n");
        }
        if (query.outputMode() == GrepOutputMode.CONTENT) {
            addContextArgs(args, query);
        }
        if (query.pattern().startsWith("-")) {
            args.add("-e");
        }
        args.add(query.pattern());
        if (query.type() != null) {
            args.add("--type");
            args.add(query.type());
        }
        addGlobArgs(args, query.glob());
        return List.copyOf(args);
    }

    private void addContextArgs(List<String> args, GrepQuery query) {
        if (query.context() != null) {
            args.add("-C");
            args.add(query.context().toString());
            return;
        }
        if (query.shortContext() != null) {
            args.add("-C");
            args.add(query.shortContext().toString());
            return;
        }
        if (query.beforeContext() != null) {
            args.add("-B");
            args.add(query.beforeContext().toString());
        }
        if (query.afterContext() != null) {
            args.add("-A");
            args.add(query.afterContext().toString());
        }
    }

    private void addGlobArgs(List<String> args, String glob) {
        if (glob == null || glob.isBlank()) {
            return;
        }
        for (String rawPattern : glob.split("\\s+")) {
            if (rawPattern.isBlank()) {
                continue;
            }
            if (rawPattern.contains("{") && rawPattern.contains("}")) {
                addGlob(args, rawPattern);
                continue;
            }
            for (String pattern : rawPattern.split(",")) {
                if (!pattern.isBlank()) {
                    addGlob(args, pattern);
                }
            }
        }
    }

    private void addGlob(List<String> args, String pattern) {
        args.add("--glob");
        args.add(pattern);
    }
}
