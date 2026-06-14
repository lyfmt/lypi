package cn.lypi.session;

import cn.lypi.contracts.session.BranchSummaryEntry;
import cn.lypi.contracts.session.CompactionEntry;
import cn.lypi.contracts.session.CustomEntry;
import cn.lypi.contracts.session.CustomMessageEntry;
import cn.lypi.contracts.session.AgentLifecycleEntry;
import cn.lypi.contracts.session.LabelEntry;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.ModeChangeEntry;
import cn.lypi.contracts.session.ModelChangeEntry;
import cn.lypi.contracts.session.PermissionModeChangeEntry;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHeader;
import cn.lypi.contracts.session.SessionInfoEntry;
import cn.lypi.contracts.session.ThinkingChangeEntry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Map;

/**
 * 映射 session JSONL envelope 和 entry 类型。
 *
 * NOTE: 第一行必须是 session header，后续行必须是已登记的 SessionEntry 类型。
 */
final class SessionJsonMapper {
    private static final String HEADER_TYPE = "session";
    private final ObjectMapper objectMapper;
    private final Map<String, Class<? extends SessionEntry>> entryTypes = Map.ofEntries(
        Map.entry("message", MessageEntry.class),
        Map.entry("model_change", ModelChangeEntry.class),
        Map.entry("thinking_change", ThinkingChangeEntry.class),
        Map.entry("mode_change", ModeChangeEntry.class),
        Map.entry("permission_mode_change", PermissionModeChangeEntry.class),
        Map.entry("compaction", CompactionEntry.class),
        Map.entry("branch_summary", BranchSummaryEntry.class),
        Map.entry("custom", CustomEntry.class),
        Map.entry("custom_message", CustomMessageEntry.class),
        Map.entry("agent_lifecycle", AgentLifecycleEntry.class),
        Map.entry("label", LabelEntry.class),
        Map.entry("session_info", SessionInfoEntry.class)
    );
    private final Map<Class<? extends SessionEntry>, String> typeNames = Map.ofEntries(
        Map.entry(MessageEntry.class, "message"),
        Map.entry(ModelChangeEntry.class, "model_change"),
        Map.entry(ThinkingChangeEntry.class, "thinking_change"),
        Map.entry(ModeChangeEntry.class, "mode_change"),
        Map.entry(PermissionModeChangeEntry.class, "permission_mode_change"),
        Map.entry(CompactionEntry.class, "compaction"),
        Map.entry(BranchSummaryEntry.class, "branch_summary"),
        Map.entry(CustomEntry.class, "custom"),
        Map.entry(CustomMessageEntry.class, "custom_message"),
        Map.entry(AgentLifecycleEntry.class, "agent_lifecycle"),
        Map.entry(LabelEntry.class, "label"),
        Map.entry(SessionInfoEntry.class, "session_info")
    );

    SessionJsonMapper() {
        objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        objectMapper.registerModule(new Jdk8Module());
        objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * 将 session header 写成 JSONL envelope。
     */
    String writeHeader(SessionHeader header) {
        return writeEnvelope(HEADER_TYPE, header);
    }

    /**
     * 将 session entry 写成 JSONL envelope。
     */
    String writeEntry(SessionEntry entry) {
        String type = typeNames.get(entry.getClass());
        if (type == null) {
            throw new SessionEngineException("Unsupported session entry type: " + entry.getClass().getName());
        }
        return writeEnvelope(type, entry);
    }

    /**
     * 读取 JSONL 行中的 type 和 payload。
     */
    EntryEnvelope readEnvelope(String line) {
        try {
            JsonNode node = objectMapper.readTree(line);
            JsonNode typeNode = node.get("type");
            if (typeNode == null || !typeNode.isTextual()) {
                throw new SessionEngineException("Session JSONL line is missing type");
            }
            return new EntryEnvelope(typeNode.asText(), node);
        } catch (JsonProcessingException e) {
            throw new SessionEngineException("Failed to parse session JSONL line: " + e.getOriginalMessage(), e);
        }
    }

    SessionHeader readHeader(EntryEnvelope envelope) {
        if (!HEADER_TYPE.equals(envelope.type())) {
            throw new SessionEngineException("First session JSONL line must be a session header");
        }
        return convert(envelope.payload(), SessionHeader.class);
    }

    SessionEntry readEntry(EntryEnvelope envelope) {
        Class<? extends SessionEntry> entryType = entryTypes.get(envelope.type());
        if (entryType == null) {
            throw new SessionEngineException("Unsupported session entry type: " + envelope.type());
        }
        return convert(envelope.payload(), entryType);
    }

    private String writeEnvelope(String type, Object value) {
        try {
            JsonNode node = objectMapper.valueToTree(value);
            return objectMapper.writeValueAsString(((com.fasterxml.jackson.databind.node.ObjectNode) node).put("type", type));
        } catch (JsonProcessingException e) {
            throw new SessionEngineException("Failed to write session JSON", e);
        }
    }

    private <T> T convert(JsonNode node, Class<T> type) {
        try {
            return objectMapper.treeToValue(node, type);
        } catch (JsonProcessingException e) {
            throw new SessionEngineException("Failed to read session JSON as " + type.getSimpleName(), e);
        }
    }
}
