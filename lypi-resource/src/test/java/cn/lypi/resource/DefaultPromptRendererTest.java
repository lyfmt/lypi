package cn.lypi.resource;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.prompt.PromptParameter;
import cn.lypi.contracts.prompt.PromptRenderRequest;
import cn.lypi.contracts.prompt.PromptTemplate;
import cn.lypi.contracts.prompt.PromptTemplateSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DefaultPromptRendererTest {
    @Test
    void renderValidatesRequiredParametersAndAppliesDefaults() {
        PromptTemplate template = new PromptTemplate(
            "review",
            "Review changes",
            PromptTemplateSource.PROJECT,
            List.of(
                new PromptParameter("scope", "Review scope", true, Optional.empty()),
                new PromptParameter("tone", "Reply tone", false, Optional.of("concise"))
            ),
            "Review {{scope}} with {{tone}} tone.",
            "sha256:prompt"
        );

        PromptRenderResult result = new DefaultPromptRenderer()
            .render(template, new PromptRenderRequest("review", Map.of("scope", "staged diff"), "/review"));

        assertThat(result.content()).isEqualTo("Review staged diff with concise tone.");
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void renderReportsMissingRequiredParameter() {
        PromptTemplate template = new PromptTemplate(
            "review",
            "Review changes",
            PromptTemplateSource.PROJECT,
            List.of(new PromptParameter("scope", "Review scope", true, Optional.empty())),
            "Review {{scope}}.",
            "sha256:prompt"
        );

        PromptRenderResult result = new DefaultPromptRenderer()
            .render(template, new PromptRenderRequest("review", Map.of(), "/review"));

        assertThat(result.content()).isEmpty();
        assertThat(result.diagnostics())
            .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                .contains("missing required parameter")
                .contains("scope"));
    }

    @Test
    void renderWarnsForTemplateMismatchAndUnknownArguments() {
        PromptTemplate template = new PromptTemplate(
            "review",
            "Review changes",
            PromptTemplateSource.PROJECT,
            List.of(),
            "Review changes.",
            "sha256:prompt"
        );

        PromptRenderResult result = new DefaultPromptRenderer()
            .render(template, new PromptRenderRequest("commit", Map.of("extra", "value"), "/commit"));

        assertThat(result.content()).isEqualTo("Review changes.");
        assertThat(result.diagnostics())
            .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("template name mismatch"))
            .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("unknown prompt parameter").contains("extra"));
    }
}
