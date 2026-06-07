package cn.lypi.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionView;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;

class ArchitectureBoundaryTest {
    private static final Path ROOT = Path.of(System.getProperty("user.dir")).getParent();
    private static final List<String> REACTOR_MODULES = List.of(
        "lypi-contracts",
        "lypi-session",
        "lypi-agent-core",
        "lypi-ai",
        "lypi-tool",
        "lypi-security",
        "lypi-resource",
        "lypi-transport-headless",
        "lypi-transport-tui",
        "lypi-boot"
    );

    @Test
    void coreToolAndSecurityDoNotTransitivelyDependOnTuiTransport() throws Exception {
        Map<String, Set<String>> dependencyGraph = reactorDependencyGraph();

        for (String module : Set.of("lypi-agent-core", "lypi-tool", "lypi-security")) {
            Set<String> reachable = transitiveDependencies(module, dependencyGraph);

            assertFalse(
                reachable.contains("lypi-transport-tui"),
                module + " must not reach lypi-transport-tui through reactor dependencies"
            );
        }
    }

    @Test
    void onlyBootDeclaresTuiTransportDependency() throws Exception {
        for (String module : REACTOR_MODULES) {
            if (module.equals("lypi-transport-tui")) {
                continue;
            }
            boolean allowed = module.equals("lypi-boot");
            boolean declaresTui = declaredDependencies(ROOT.resolve(module).resolve("pom.xml"))
                .contains("lypi-transport-tui");

            assertEquals(allowed, declaresTui, module + " TUI dependency declaration mismatch");
        }
    }

    @Test
    void sessionEntrySubtypesExcludeTuiDerivedAndRuntimeFacts() {
        JsonSubTypes subTypes = SessionEntry.class.getAnnotation(JsonSubTypes.class);

        Set<String> typeNames = Arrays.stream(subTypes.value())
            .map(JsonSubTypes.Type::name)
            .collect(Collectors.toSet());

        assertFalse(typeNames.contains("file_change"));
        assertFalse(typeNames.contains("permission_decision"));
        assertFalse(typeNames.contains("command"));
        assertFalse(typeNames.contains("tool_use_audit"));
        assertFalse(typeNames.contains("tool_output"));
        assertFalse(classExists("cn.lypi.contracts.session.FileChangeEntry"));
        assertFalse(classExists("cn.lypi.contracts.session.PermissionDecisionEntry"));
        assertFalse(classExists("cn.lypi.contracts.session.CommandEntry"));
        assertFalse(classExists("cn.lypi.contracts.session.ToolUseAuditEntry"));
        assertFalse(classExists("cn.lypi.contracts.session.ToolOutputEntry"));
    }

    @Test
    void sessionViewOnlyCarriesReplayPointers() {
        Set<String> fields = Arrays.stream(SessionView.class.getRecordComponents())
            .map(component -> component.getName())
            .collect(Collectors.toSet());

        assertEquals(Set.of("sessionId", "leafId"), fields);
    }

    private static Map<String, Set<String>> reactorDependencyGraph() throws Exception {
        Map<String, Set<String>> graph = new HashMap<>();
        for (String module : REACTOR_MODULES) {
            graph.put(module, declaredDependencies(ROOT.resolve(module).resolve("pom.xml")));
        }
        return graph;
    }

    private static Set<String> declaredDependencies(Path pom) throws Exception {
        assertTrue(Files.exists(pom), "Missing module pom: " + pom);
        NodeList dependencies = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(pom.toFile())
            .getElementsByTagName("dependency");

        return java.util.stream.IntStream.range(0, dependencies.getLength())
            .mapToObj(dependencies::item)
            .map(Element.class::cast)
            .filter(ArchitectureBoundaryTest::isReactorDependency)
            .map(dependency -> text(dependency, "artifactId"))
            .collect(Collectors.toSet());
    }

    private static Set<String> transitiveDependencies(String module, Map<String, Set<String>> graph) {
        Set<String> visited = new HashSet<>();
        collect(module, graph, visited);
        visited.remove(module);
        return visited;
    }

    private static void collect(String module, Map<String, Set<String>> graph, Set<String> visited) {
        for (String dependency : graph.getOrDefault(module, Set.of())) {
            if (visited.add(dependency)) {
                collect(dependency, graph, visited);
            }
        }
    }

    private static boolean isReactorDependency(Element dependency) {
        return "cn.lypi".equals(text(dependency, "groupId")) && REACTOR_MODULES.contains(text(dependency, "artifactId"));
    }

    private static String text(Element dependency, String tagName) {
        NodeList nodes = dependency.getElementsByTagName(tagName);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
