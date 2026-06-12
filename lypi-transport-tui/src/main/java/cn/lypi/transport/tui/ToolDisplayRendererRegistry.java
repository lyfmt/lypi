package cn.lypi.transport.tui;

import cn.lypi.contracts.tui.TuiToolBlock;
import cn.lypi.contracts.tui.TuiToolState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ToolDisplayRendererRegistry {
    private final Map<String, ToolDisplayRenderer> renderers;
    private final ToolDisplayRenderer fallback;

    private ToolDisplayRendererRegistry(Map<String, ToolDisplayRenderer> renderers, ToolDisplayRenderer fallback) {
        this.renderers = Map.copyOf(renderers);
        this.fallback = fallback;
    }

    static ToolDisplayRendererRegistry defaults() {
        ToolDisplayRenderer fallback = new FallbackToolDisplayRenderer();
        Map<String, ToolDisplayRenderer> renderers = new HashMap<>();
        ToolDisplayRenderer bash = new BashToolDisplayRenderer();
        ToolDisplayRenderer edit = new EditToolDisplayRenderer();
        ToolDisplayRenderer search = new SearchToolDisplayRenderer();
        renderers.put("bash", bash);
        renderers.put("shell", bash);
        renderers.put("read", new ReadToolDisplayRenderer());
        renderers.put("write", new WriteToolDisplayRenderer());
        renderers.put("edit", edit);
        renderers.put("grep", search);
        renderers.put("glob", search);
        return new ToolDisplayRendererRegistry(renderers, fallback);
    }

    ToolDisplayModel render(TuiToolBlock block, boolean expanded) {
        return renderers.getOrDefault(normalize(block.toolName()), fallback).render(block, expanded);
    }

    ToolDisplayModel render(TuiToolBlock block, boolean expanded, int detailLineLimit) {
        return renderers.getOrDefault(normalize(block.toolName()), fallback).render(block, expanded, detailLineLimit);
    }

    boolean isReadLikeTool(TuiToolBlock block) {
        String toolName = normalize(block.toolName());
        return "read".equals(toolName) || "grep".equals(toolName) || "glob".equals(toolName);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String stateLabel(TuiToolState state) {
        return state == null ? "pending" : state.name().toLowerCase(Locale.ROOT);
    }

    private static String label(TuiToolBlock block) {
        return block.label() == null || block.label().isBlank() ? block.toolName() : block.label();
    }

    private static List<String> detailLines(TuiToolBlock block) {
        if (block.details() == null || block.details().isBlank()) {
            return List.of();
        }
        return block.details().lines()
            .filter(line -> !line.isBlank())
            .toList();
    }

    private static List<String> firstLines(List<String> lines, int limit) {
        limit = Math.max(0, limit);
        if (lines.size() <= limit) {
            return lines;
        }
        List<String> preview = new ArrayList<>(lines.subList(0, limit));
        preview.add("... " + (lines.size() - limit) + " more lines");
        return preview;
    }

    private static List<String> tailLines(List<String> lines, int limit) {
        limit = Math.max(0, limit);
        if (lines.size() <= limit) {
            return lines;
        }
        List<String> preview = new ArrayList<>();
        preview.add("... " + (lines.size() - limit) + " earlier lines");
        preview.addAll(lines.subList(lines.size() - limit, lines.size()));
        return preview;
    }

    private static final class BashToolDisplayRenderer implements ToolDisplayRenderer {
        @Override
        public ToolDisplayModel render(TuiToolBlock block, boolean expanded) {
            return render(block, expanded, expanded ? 80 : 5);
        }

        @Override
        public ToolDisplayModel render(TuiToolBlock block, boolean expanded, int detailLineLimit) {
            return new ToolDisplayModel(
                stateLabel(block.state()) + " $ " + label(block),
                List.of(),
                tailLines(detailLines(block), expanded ? detailLineLimit : 5)
            );
        }
    }

    private static final class ReadToolDisplayRenderer implements ToolDisplayRenderer {
        @Override
        public ToolDisplayModel render(TuiToolBlock block, boolean expanded) {
            return new ToolDisplayModel(
                stateLabel(block.state()) + " " + block.toolName() + " " + label(block),
                List.of(),
                List.of()
            );
        }
    }

    private static final class WriteToolDisplayRenderer implements ToolDisplayRenderer {
        @Override
        public ToolDisplayModel render(TuiToolBlock block, boolean expanded) {
            return render(block, expanded, expanded ? 120 : 10);
        }

        @Override
        public ToolDisplayModel render(TuiToolBlock block, boolean expanded, int detailLineLimit) {
            return new ToolDisplayModel(
                stateLabel(block.state()) + " " + block.toolName() + " " + label(block),
                List.of(),
                firstLines(detailLines(block), expanded ? detailLineLimit : 10)
            );
        }
    }

    private static final class EditToolDisplayRenderer implements ToolDisplayRenderer {
        @Override
        public ToolDisplayModel render(TuiToolBlock block, boolean expanded) {
            return render(block, expanded, expanded ? 120 : 12);
        }

        @Override
        public ToolDisplayModel render(TuiToolBlock block, boolean expanded, int detailLineLimit) {
            List<String> lines = detailLines(block);
            int added = 0;
            int removed = 0;
            for (String line : lines) {
                if (line.startsWith("+") && !line.startsWith("+++")) {
                    added++;
                } else if (line.startsWith("-") && !line.startsWith("---")) {
                    removed++;
                }
            }
            String summary = added > 0 || removed > 0 ? " +" + added + " -" + removed : "";
            return new ToolDisplayModel(
                stateLabel(block.state()) + " edit " + label(block) + summary,
                List.of(),
                firstLines(lines, expanded ? detailLineLimit : 12)
            );
        }
    }

    private static final class SearchToolDisplayRenderer implements ToolDisplayRenderer {
        @Override
        public ToolDisplayModel render(TuiToolBlock block, boolean expanded) {
            return new ToolDisplayModel(
                stateLabel(block.state()) + " " + block.toolName() + " " + label(block),
                List.of(),
                List.of()
            );
        }
    }

    private static final class FallbackToolDisplayRenderer implements ToolDisplayRenderer {
        @Override
        public ToolDisplayModel render(TuiToolBlock block, boolean expanded) {
            return render(block, expanded, expanded ? 120 : 10);
        }

        @Override
        public ToolDisplayModel render(TuiToolBlock block, boolean expanded, int detailLineLimit) {
            return new ToolDisplayModel(
                stateLabel(block.state()) + " " + block.toolName() + " " + label(block),
                List.of(),
                firstLines(detailLines(block), expanded ? detailLineLimit : 10)
            );
        }
    }
}
