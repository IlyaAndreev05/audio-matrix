package io.github.ilyaandreev05.audiomatrix.audio;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Hardware identity includes the active endpoint format: profile changes require a new explicit binding. */
public record DeviceIdentity(String fingerprint, String physicalPath, String serial, String cardProfile,
                             String port, String bluetoothAddress, int channels, String channelPositions, String confidence) {
    public static DeviceIdentity from(Map<String, String> props, int channels) {
        String path = first(props, "device.bus-path", "device.bus_path", "device.sysfs.path");
        String serial = first(props, "device.serial", "api.alsa.card.serial");
        String profile = first(props, "device.profile.name", "api.alsa.pcm.stream", "api.bluez5.profile");
        String port = first(props, "device.port.name", "api.alsa.pcm.device", "port.name");
        String bluetooth = first(props, "api.bluez5.address", "device.string").toUpperCase(java.util.Locale.ROOT);
        if (!bluetooth.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) bluetooth = "";
        boolean hardware = !path.isBlank() || !serial.isBlank() || !bluetooth.isBlank();
        String positions = first(props, "audio.position").replaceAll("[\\[\\]\\\"]", "").trim()
                .replaceAll("[,\\s]+", ",").toUpperCase(java.util.Locale.ROOT);
        String confidence = hardware && channels > 0 && !profile.isBlank()
                && (!port.isBlank() || !bluetooth.isBlank()) ? "HIGH" : "LOW";
        String canonical = String.join("\u0000", path, serial, profile, port, bluetooth, String.valueOf(channels), positions);
        return new DeviceIdentity(hash(canonical), path, serial, profile, port, bluetooth, channels, positions, confidence);
    }

    public static DeviceIdentity fromMap(Map<String, Object> value) {
        return new DeviceIdentity(text(value, "fingerprint"), text(value, "physicalPath"), text(value, "serial"),
                text(value, "cardProfile"), text(value, "port"), text(value, "bluetoothAddress"),
                value.get("channels") instanceof Number number ? number.intValue() : 0,
                text(value, "channelPositions"), text(value, "confidence"));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fingerprint", fingerprint); result.put("physicalPath", physicalPath); result.put("serial", serial);
        result.put("cardProfile", cardProfile); result.put("port", port); result.put("bluetoothAddress", bluetoothAddress);
        result.put("channels", channels); result.put("channelPositions", channelPositions); result.put("confidence", confidence);
        return result;
    }

    public boolean reliable() {
        return "HIGH".equals(confidence) && channels > 0 && (!physicalPath.isBlank() || !serial.isBlank() || !bluetoothAddress.isBlank())
                && !cardProfile.isBlank() && (!port.isBlank() || !bluetoothAddress.isBlank())
                && fingerprint.equals(hash(String.join("\u0000", physicalPath, serial, cardProfile, port,
                        bluetoothAddress.toUpperCase(java.util.Locale.ROOT), String.valueOf(channels), channelPositions)));
    }

    private static String first(Map<String, String> props, String... names) {
        for (String name : names) { String value = props.getOrDefault(name, ""); if (!value.isBlank()) return value; }
        return "";
    }
    private static String text(Map<String, Object> value, String key) { return value.get(key) instanceof String text ? text : ""; }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
