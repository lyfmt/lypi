package cn.lypi.tool.web;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Web provider JSON 映射辅助函数。
 */
final class WebJson {
    private WebJson() {
    }

    static Optional<String> text(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return Optional.empty();
        }
        String text = value.asText("").trim();
        return text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    static Optional<Double> decimal(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull() || !value.isNumber()) {
            return Optional.empty();
        }
        return Optional.of(value.asDouble());
    }

    static Optional<Instant> instant(JsonNode node, String fieldName) {
        Optional<String> value = text(node, fieldName);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        String text = value.orElseThrow();
        try {
            if (text.length() == 10) {
                return Optional.of(LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC));
            }
            return Optional.of(Instant.parse(text));
        } catch (DateTimeParseException exception) {
            return Optional.empty();
        }
    }
}
