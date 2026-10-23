package io.github.ilyaandreev05.audiomatrix.config;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DeviceDisplayNamesTest {
    @Test void canonicalUserAliasWinsWithoutChangingStableIdentityOrSelector() {
        Map<String,Object> device=new LinkedHashMap<>(Map.of("id","monitor","name","Мой BenQ",
                "selector",Map.of("kind","name_equals","value","alsa_output.monitor")));
        Map<String,Object> before=new LinkedHashMap<>(device);
        assertEquals("Мой BenQ",DeviceDisplayNames.displayName(device,"BenQ HDMI"));
        assertEquals("Мой BenQ",DeviceDisplayNames.displayName(Map.of("devices",List.of(device)),"device:monitor","BenQ HDMI"));
        assertEquals(before,device);
        assertFalse(device.containsKey("alias"),"There must not be a second persistent alias source");
    }

    @Test void detectedBrandsRemainRecognizableAndGenericLabelsBecomeRussian() {
        assertEquals("BenQ GW2480 HDMI",DeviceDisplayNames.displayName(Map.of(),"BenQ GW2480 HDMI"));
        assertEquals("Focusrite Scarlett 2i2 Аналоговый стереовыход",
                DeviceDisplayNames.displayName(Map.of(),"Focusrite Scarlett 2i2 Analog Stereo"));
        assertEquals("Встроенное аудио Аналоговый стереовыход",
                DeviceDisplayNames.displayName(Map.of(),"Built-in Audio Analog Stereo"));
        assertEquals("Выходное устройство",DeviceDisplayNames.displayName(Map.of(),"device:6d7a4ff3-7237-4ae8-b171-b9f91dff1dee"));
    }


    @Test void systemInputAndInternalDeviceReferencesGetSafeRussianLabels() {
        assertEquals("Системный звук",DeviceDisplayNames.displayName(Map.of(),"System input"));
        assertEquals("Системный звук",DeviceDisplayNames.displayName(Map.of(),"SYSTEM INPUT"));
        assertEquals("BenQ GW2480 HDMI",DeviceDisplayNames.displayName(
                Map.of("name","device:6d7a4ff3-7237-4ae8-b171-b9f91dff1dee"),"BenQ GW2480 HDMI"));
        assertEquals("Аудиоэлемент",DeviceDisplayNames.displayLabel(
                "device:6d7a4ff3-7237-4ae8-b171-b9f91dff1dee","Аудиоэлемент"));
    }
    @Test void offlineFingerprintReferencesFindTheSameCanonicalAlias() {
        Map<String,Object> device=Map.of("id","user-stable-id","name","Наушники Sony",
                "identity",Map.of("fingerprint","abc123"));
        assertEquals("Наушники Sony",DeviceDisplayNames.displayName(Map.of("devices",List.of(device)),"device:abc123",""));
    }
}
