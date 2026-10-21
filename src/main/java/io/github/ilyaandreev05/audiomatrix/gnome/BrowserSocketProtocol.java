package io.github.ilyaandreev05.audiomatrix.gnome;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict, URL-free JSONL v1 parser shared by all browser socket clients. */
final class BrowserSocketProtocol {
    static final int PROTOCOL_VERSION = 1;
    static final int MAX_LINE_BYTES = 1_048_576;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,256}");
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:[a-z][a-z0-9+.-]*://|www\\.)\\S+");

    private BrowserSocketProtocol() {}

    static JsonNode parse(String line) throws IOException {
        JsonNode root;
        try { root = JSON.readTree(line); }
        catch (IOException malformed) { throw new ProtocolException("Malformed JSON line.", malformed); }
        if (root == null || !root.isObject()) throw new ProtocolException("A browser message must be a JSON object.");
        if (!root.path("version").isInt() || root.path("version").asInt() != PROTOCOL_VERSION)
            throw new ProtocolException("Unsupported browser protocol version.");
        if (!"chrome".equals(text(root, "browser"))) throw new ProtocolException("Unsupported browser adapter.");
        text(root, "type");
        safeKey(text(root, "requestId"), "requestId");
        text(root, "instanceId");
        text(root, "profileId");
        return root;
    }

    static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
        while (true) {
            int next = input.read();
            if (next < 0) {
                if (bytes.size() == 0) return null;
                throw new ProtocolException("A browser message ended before its newline.");
            }
            if (next == '\n') break;
            if (bytes.size() == MAX_LINE_BYTES) throw new ProtocolException("The browser JSON line exceeds one MiB.");
            bytes.write(next);
        }
        byte[] raw = bytes.toByteArray();
        int length = raw.length;
        if (length > 0 && raw[length - 1] == '\r') length--;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw, 0, length)).toString();
        } catch (CharacterCodingException malformed) {
            throw new ProtocolException("The browser JSON line is not valid UTF-8.", malformed);
        }
    }

    static BrowserSocketRegistry.Key key(JsonNode root) throws ProtocolException {
        return new BrowserSocketRegistry.Key(text(root, "browser"), safeKey(text(root, "instanceId"), "instanceId"),
                safeKey(text(root, "profileId"), "profileId"));
    }

    static List<BrowserAdapter.BrowserTab> tabs(JsonNode tabsNode) throws ProtocolException {
        if (!tabsNode.isArray() || tabsNode.size() > 4096) throw new ProtocolException("The browser tab list is invalid.");
        List<BrowserAdapter.BrowserTab> tabs = new ArrayList<>(tabsNode.size());
        Set<String> tokens = new HashSet<>();
        Set<String> captureKeys = new HashSet<>();
        for (JsonNode row : tabsNode) {
            requireFields(row, Set.of("sessionToken", "tabId", "title", "audible", "muted", "volumeCapability", "captureStreamKey"));
            String token = safeKey(text(row, "sessionToken"), "sessionToken");
            if (!tokens.add(token)) throw new ProtocolException("A snapshot contains a duplicate session token.");
            if (!row.path("tabId").isIntegralNumber() || !row.path("tabId").canConvertToInt()
                    || row.path("tabId").asInt() < 0 || !row.path("audible").isBoolean() || !row.path("muted").isBoolean())
                throw new ProtocolException("The browser tab state is invalid.");
            String capability = text(row, "volumeCapability");
            if (!Set.of("none", "captured").contains(capability)) throw new ProtocolException("The tab volume capability is invalid.");
            String captureKey = text(row, "captureStreamKey");
            if ("captured".equals(capability) != !captureKey.isEmpty())
                throw new ProtocolException("A captured tab must have exactly one stream key.");
            if (!captureKey.isEmpty()) safeKey(captureKey, "captureStreamKey");
            if (!captureKey.isEmpty() && !captureKeys.add(captureKey))
                throw new ProtocolException("A captured stream key cannot identify multiple tabs.");
            String title = URL.matcher(text(row, "title")).replaceAll("[link]");
            title = title.replaceAll("[\\p{Cc}&&[^\\t]]", "");
            if (title.length() > 512) title = title.substring(0, 512);
            tabs.add(new BrowserAdapter.BrowserTab(token, row.path("tabId").asInt(), title,
                    row.path("audible").asBoolean(), row.path("muted").asBoolean(), capability, captureKey));
        }
        return List.copyOf(tabs);
    }

    static void requireFields(JsonNode node, Set<String> allowed) throws ProtocolException {
        if (!node.isObject()) throw new ProtocolException("A browser field must be an object.");
        Set<String> found = new HashSet<>();
        node.fieldNames().forEachRemaining(found::add);
        if (!allowed.equals(found)) throw new ProtocolException("The browser message has missing or unsupported fields.");
    }

    static String text(JsonNode node, String field) throws ProtocolException {
        JsonNode value = node.path(field);
        if (!value.isTextual()) throw new ProtocolException("The browser field '" + field + "' must be text.");
        return value.textValue();
    }

    static String safeKey(String value, String field) throws ProtocolException {
        if (!SAFE_KEY.matcher(value).matches()) throw new ProtocolException("The browser field '" + field + "' is invalid.");
        return value;
    }
    static boolean isSafeKey(String value) { return value != null && SAFE_KEY.matcher(value).matches(); }


    static String stripUrls(String value) { return URL.matcher(value).replaceAll("[link]"); }

    static final class ProtocolException extends IOException {
        ProtocolException(String message) { super(message); }
        ProtocolException(String message, Throwable cause) { super(message, cause); }
    }
}
