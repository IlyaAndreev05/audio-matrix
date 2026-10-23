package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Splits pw-dump monitor output into complete updates and recognises pure node-level changes. */
final class PipeWireMonitorChange {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final StringBuilder buffer = new StringBuilder();
    private int depth;
    private boolean inString;
    private boolean escaped;
    private boolean initialComplete;

    record Update(String payload, boolean initialCompleted) {}

    Update accept(String line) {
        if (initialComplete) {
            if (buffer.isEmpty() && line.isBlank()) return null;
            buffer.append(line).append('\n');
            if (!closesArray(line)) return null;
            String payload = buffer.toString();
            buffer.setLength(0);
            return new Update(payload, false);
        }
        if (!closesArray(line)) return null;
        initialComplete = true;
        return new Update(null, true);
    }

    /** True only for a monitor payload that cannot change the owned node or link topology. */
    static boolean levelOnly(String payload) {
        try {
            JsonNode events = JSON.readTree(payload);
            if (!events.isArray() || events.isEmpty()) return false;
            boolean levelChange = false;
            for (JsonNode event : events) {
                JsonNode info = event.path("info");
                if (!info.isObject()) return false;
                String type = event.path("type").asText();
                if ("PipeWire:Interface:Client".equals(type)) continue;
                if (!"PipeWire:Interface:Node".equals(type)) return false;
                JsonNode changeMask = info.path("change-mask");
                if (!changeMask.isArray() || changeMask.isEmpty()) return false;
                for (JsonNode change : changeMask)
                    if (!"params".equals(change.asText())) return false;
                levelChange = true;
            }
            return levelChange;
        } catch (Exception malformed) {
            return false;
        }
    }

    private boolean closesArray(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '[') depth++;
            else if (c == ']' && --depth == 0) return true;
        }
        return false;
    }
}
