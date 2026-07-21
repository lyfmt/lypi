package cn.lypi.transport.headless;

import cn.lypi.contracts.subagent.HeadlessSubagentInput;
import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class HeadlessSubagentJsonCodec {
    private final ObjectMapper objectMapper;

    public HeadlessSubagentJsonCodec() {
        objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        objectMapper.configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
        objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        objectMapper.registerModule(new Jdk8Module());
        objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * 从 stdin JSON 读取 headless subagent 输入。
     */
    public HeadlessSubagentInput readInput(InputStream input) {
        try {
            return objectMapper.readValue(input, HeadlessSubagentInput.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid headless subagent input JSON: " + e.getMessage(), e);
        }
    }

    /**
     * 从 JSON 读取 headless subagent 输出。
     */
    public HeadlessSubagentOutput readOutput(InputStream input) {
        try {
            return objectMapper.readValue(input, HeadlessSubagentOutput.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid headless subagent output JSON: " + e.getMessage(), e);
        }
    }

    /**
     * 向 stdin 写入 headless subagent 输入。
     */
    public void writeInput(HeadlessSubagentInput input, OutputStream out) {
        try {
            objectMapper.writeValue(out, input);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write headless subagent input JSON", e);
        }
    }

    /**
     * 向 stdout 写入 headless subagent 输出。
     */
    public void writeOutput(HeadlessSubagentOutput output, OutputStream out) {
        try {
            objectMapper.writeValue(out, output);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write headless subagent output JSON", e);
        }
    }
}
