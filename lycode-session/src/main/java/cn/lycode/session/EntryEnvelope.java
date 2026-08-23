package cn.lycode.session;

import com.fasterxml.jackson.databind.JsonNode;

record EntryEnvelope(String type, JsonNode payload) {
}
