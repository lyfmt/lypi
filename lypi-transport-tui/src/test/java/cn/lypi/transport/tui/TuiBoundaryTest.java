package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;

class TuiBoundaryTest {
    private static final Path MODULE_ROOT = Path.of(System.getProperty("user.dir"));
    private static final List<String> FORBIDDEN_IMPORT_PREFIXES = List.of(
        "cn.lypi.agent.",
        "cn.lypi.ai.provider.",
        "cn.lypi.boot.",
        "cn.lypi.resource.",
        "cn.lypi.security.",
        "cn.lypi.session.",
        "cn.lypi.tool."
    );

    @Test
    void tuiModuleOnlyDependsOnContracts() throws Exception {
        Set<String> dependencies = declaredDependencies(MODULE_ROOT.resolve("pom.xml"));

        assertEquals(Set.of(
            "lypi-contracts",
            "jline-terminal",
            "jline-terminal-jna",
            "jline-reader",
            "jline-builtins"
        ), dependencies);
    }

    @Test
    void tuiMainSourcesOnlyImportSemanticContractsAndDoNotReachBehindPorts() throws Exception {
        List<Path> sources = javaSources(MODULE_ROOT.resolve("src/main/java"));

        for (Path source : sources) {
            List<String> imports = importLines(source);
            for (String forbidden : FORBIDDEN_IMPORT_PREFIXES) {
                assertFalse(
                    imports.stream().anyMatch(line -> line.startsWith("import " + forbidden)),
                    source + " must not import " + forbidden + " directly"
                );
            }
        }
    }

    @Test
    void tuiMainSourcesDoNotUseForbiddenFullyQualifiedInternals() throws Exception {
        for (Path source : javaSources(MODULE_ROOT.resolve("src/main/java"))) {
            String text = stripLineComments(Files.readString(source));
            for (String forbidden : FORBIDDEN_IMPORT_PREFIXES) {
                assertFalse(text.contains(forbidden), source + " must not reference " + forbidden + " internals");
            }
        }
    }

    private static List<Path> javaSources(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream
                .filter(path -> path.toString().endsWith(".java"))
                .sorted()
                .toList();
        }
    }

    private static List<String> importLines(Path source) throws IOException {
        return Files.readAllLines(source).stream()
            .map(String::trim)
            .filter(line -> line.startsWith("import "))
            .toList();
    }

    private static Set<String> declaredDependencies(Path pom) throws Exception {
        NodeList dependencies = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(pom.toFile())
            .getElementsByTagName("dependency");

        return java.util.stream.IntStream.range(0, dependencies.getLength())
            .mapToObj(dependencies::item)
            .map(Element.class::cast)
            .filter(dependency -> "cn.lypi".equals(text(dependency, "groupId"))
                || "org.jline".equals(text(dependency, "groupId")))
            .map(dependency -> text(dependency, "artifactId"))
            .collect(Collectors.toSet());
    }

    private static String text(Element dependency, String tagName) {
        NodeList nodes = dependency.getElementsByTagName(tagName);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    private static String stripLineComments(String text) {
        return text.lines()
            .map(line -> {
                int commentStart = line.indexOf("//");
                return commentStart < 0 ? line : line.substring(0, commentStart);
            })
            .collect(Collectors.joining("\n"));
    }
}
