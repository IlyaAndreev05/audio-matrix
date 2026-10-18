package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.audio.DeviceDiscovery;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;
import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConfigDeviceRulesTest {
    private Map<String, Object> device(String id) {
        return new LinkedHashMap<>(Map.of("id", id, "kind", "device_slot", "identity",
                DeviceIdentity.from(Map.of("device.serial", "dac-1", "device.profile.name", "analog-stereo",
                        "device.port.name", "headphones", "audio.position", "FL,FR"), 2).toMap(), "volume", 1.0, "mute", false));
    }
    private ConfigValidationContext context(List<Map<String, Object>> devices) {
        var context = new ConfigValidationContext(new LinkedHashMap<>()); context.devices = devices; return context;
    }
    @Test void compositeSlotDoesNotRequireLegacySelector() {
        var context = context(List.of(device("slot"))); ConfigDeviceRules.validate(context);
        assertTrue(context.errors.isEmpty());
    }
    @Test void duplicatePhysicalIdentityIsRejectedEvenForVibro() {
        var vibro = device("vibro"); vibro.put("role", "haptic_output");
        var context = context(List.of(vibro, device("music"))); ConfigDeviceRules.validate(context);
        assertTrue(context.errors.stream().anyMatch(error -> error.contains("conflicts")));
    }
    @Test void malformedFingerprintAndMixedLegacySelectorAreRejected() {
        var device = device("slot"); device.put("selector", Map.of("kind", "name_equals", "value", "old"));
        var identity = new LinkedHashMap<String, Object>(); identity.put("fingerprint", "transient-99"); device.put("identity", identity);
        var context = context(List.of(device)); ConfigDeviceRules.validate(context);
        assertTrue(context.errors.stream().anyMatch(error -> error.contains("fingerprint")));
        assertTrue(context.errors.stream().anyMatch(error -> error.contains("legacy selector")));
        assertTrue(context.errors.stream().anyMatch(error -> error.contains("HIGH confidence")));
    }
}
