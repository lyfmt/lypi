package cn.lypi.resource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.util.Map;

final class FrontmatterParser {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    FrontmatterDocument parse(String content) throws IOException {
        String normalized = content.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            return new FrontmatterDocument(Map.of(), normalized.strip());
        }
        int end = normalized.indexOf("\n---", 4);
        if (end < 0) {
            throw new IOException("Unterminated frontmatter");
        }
        String metadataText = normalized.substring(4, end);
        int bodyStart = end + "\n---".length();
        if (bodyStart < normalized.length() && normalized.charAt(bodyStart) == '\n') {
            bodyStart++;
        }
        Map<String, Object> metadata = mapper.readValue(metadataText, MAP_TYPE);
        return new FrontmatterDocument(metadata == null ? Map.of() : metadata, normalized.substring(bodyStart).strip());
    }
}
