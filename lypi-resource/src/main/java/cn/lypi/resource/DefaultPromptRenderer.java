package cn.lypi.resource;

import cn.lypi.contracts.prompt.PromptParameter;
import cn.lypi.contracts.prompt.PromptRenderRequest;
import cn.lypi.contracts.prompt.PromptTemplate;
import cn.lypi.contracts.resource.ResourceDiagnostic;
import cn.lypi.contracts.resource.ResourceDiagnosticLevel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class DefaultPromptRenderer implements PromptRenderer {
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([A-Za-z0-9_.-]+)}}");

    @Override
    public PromptRenderResult render(PromptTemplate template, PromptRenderRequest request) {
        ArrayList<ResourceDiagnostic> diagnostics = new ArrayList<>();
        if (!template.name().equals(request.templateName())) {
            diagnostics.add(warning("template name mismatch: requested " + request.templateName() + ", template " + template.name()));
        }

        Map<String, String> values = new LinkedHashMap<>();
        for (PromptParameter parameter : template.parameters()) {
            String value = request.arguments().get(parameter.name());
            if (value == null) {
                value = parameter.defaultValue().orElse(null);
            }
            if (value == null && parameter.required()) {
                diagnostics.add(warning("missing required parameter: " + parameter.name()));
            } else if (value != null) {
                values.put(parameter.name(), value);
            }
        }

        Set<String> declaredNames = template.parameters().stream()
            .map(PromptParameter::name)
            .collect(Collectors.toSet());
        for (String argumentName : request.arguments().keySet()) {
            if (!declaredNames.contains(argumentName)) {
                diagnostics.add(warning("unknown prompt parameter: " + argumentName));
            }
        }

        if (diagnostics.stream().anyMatch(diagnostic -> diagnostic.message().startsWith("missing required parameter"))) {
            return new PromptRenderResult("", diagnostics);
        }

        return new PromptRenderResult(renderTemplate(template.templateBody(), values), diagnostics);
    }

    private ResourceDiagnostic warning(String message) {
        return new ResourceDiagnostic(ResourceDiagnosticLevel.WARNING, message, Optional.empty());
    }

    private String renderTemplate(String templateBody, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(templateBody);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String replacement = values.getOrDefault(matcher.group(1), matcher.group());
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }
}
