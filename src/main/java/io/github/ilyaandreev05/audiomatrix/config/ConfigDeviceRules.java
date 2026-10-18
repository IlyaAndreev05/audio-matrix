package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Device slots persist a composite identity, not a transient PipeWire object id. */
final class ConfigDeviceRules {
    private ConfigDeviceRules() {}
    static void validate(ConfigValidationContext ctx) {
        Set<String> fingerprints = new HashSet<>();
        for (int i = 0; i < ctx.devices.size(); i++) {
            Map<String, Object> device = ctx.devices.get(i);
            String path = "devices[" + i + "]";
            if (!device.containsKey("identity")) {
                if ("device_slot".equals(device.get("kind"))) ctx.errors.add(path + ".identity is required for a device slot");
                continue;
            }
            Map<String, Object> identity = ConfigValidationValues.object(device.get("identity"), path + ".identity", ctx.errors);
            if (identity == null) continue;
            for (String key : Set.of("fingerprint", "physicalPath", "serial", "cardProfile", "port", "bluetoothAddress", "channelPositions", "confidence"))
                if (!(identity.get(key) instanceof String)) ctx.errors.add(path + ".identity." + key + " must be a string");
            DeviceIdentity parsed = DeviceIdentity.fromMap(identity);
            if (!parsed.reliable()) ctx.errors.add(path + ".identity must have HIGH confidence and physical hardware properties");
            double channels = ConfigValidationValues.number(identity.get("channels"), Double.NaN);
            if (!Double.isFinite(channels) || channels < 1 || channels > 64 || channels != Math.rint(channels))
                ctx.errors.add(path + ".identity.channels must be an integer between 1 and 64");
            if (!parsed.fingerprint().matches("[a-f0-9]{64}")) ctx.errors.add(path + ".identity.fingerprint must be SHA-256");
            if (!Boolean.TRUE.equals(device.get("ignored")) && !fingerprints.add(parsed.fingerprint()))
                ctx.errors.add(path + ".identity conflicts with another physical device binding (including Vibro)");
            for (String key : Set.of("selected", "ignored"))
                if (device.containsKey(key) && !(device.get(key) instanceof Boolean)) ctx.errors.add(path + "." + key + " must be boolean");
            if (device.containsKey("selector")) ctx.errors.add(path + " must not combine identity and legacy selector");
        }
    }
}
