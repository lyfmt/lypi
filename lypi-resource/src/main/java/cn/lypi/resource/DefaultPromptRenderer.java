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
import java.util.stream.Collectors;

public class DefaultPromptRenderer implements PromptRenderer {
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

        String content = template.templateBody();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            content = content.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return new PromptRenderResult(content, diagnostics);
    }

    private ResourceDiagnostic warning(String message) {
        return new ResourceDiagnostic(ResourceDiagnosticLevel.WARNING, message, Optional.empty());
    }
}
