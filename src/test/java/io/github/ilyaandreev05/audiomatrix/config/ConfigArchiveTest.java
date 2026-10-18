package io.github.ilyaandreev05.audiomatrix.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ConfigArchiveTest {
    @TempDir Path temp;

    @Test void exportsVersionedManifestAndMigratesLegacyV1Archives() throws Exception {
        Path sourceDir = temp.resolve("manifest-source");
        Path archive = temp.resolve("current.amx.zip");
        try (ConfigStore source = new ConfigStore(sourceDir.resolve("config.yaml"))) {
            ConfigArchive.exportArchive(archive, DefaultConfig.create(), sourceDir);
        }
        Map<String, Object> current = readManifest(archive);
        assertEquals(3, ((Number) current.get("version")).intValue());
        assertEquals("config.json",current.get("config_entry"));
        assertEquals("audio-matrix", current.get("app"));
        assertEquals(7, ((Number) current.get("config_schema_version")).intValue());
        assertEquals("full", current.get("kind"));

        Path legacy = temp.resolve("legacy.amx.zip");
        rewriteManifest(archive, legacy, manifest -> {
            manifest.put("version", 1);
            manifest.remove("app");
            manifest.remove("config_schema_version");
            manifest.remove("kind");
        });
        try (ConfigArchive.PreparedImport prepared = ConfigArchive.prepare(legacy)) {
            assertEquals(1, prepared.profileCount());
        }

        Path future = temp.resolve("future.amx.zip");
        rewriteManifest(archive, future, manifest -> manifest.put("version", 99));
        assertThrows(ConfigArchive.ArchiveException.class, () -> ConfigArchive.prepare(future));

        Path mismatch = temp.resolve("schema-mismatch.amx.zip");
        rewriteManifest(archive, mismatch, manifest -> manifest.put("config_schema_version", 6));
        assertThrows(ConfigArchive.ArchiveException.class, () -> ConfigArchive.prepare(mismatch));
    }

    @Test void restoresProfilesAndMeasurementsWithoutOverwritingDifferentFile() throws Exception {
        Path sourceDir = temp.resolve("source");
        try (ConfigStore source = new ConfigStore(sourceDir.resolve("config.yaml"))) {
            Map<String, Object> config = DefaultConfig.create();
            Path measurement = sourceDir.resolve("measurements/desk.wav"); Files.createDirectories(measurement.getParent()); Files.write(measurement, new byte[]{1, 2, 3, 4});
            Path curve = sourceDir.resolve("measurements/desk-response.csv"); Files.writeString(curve, "frequency_hz,relative_db\n80,-2\n");
            Path decay = sourceDir.resolve("measurements/desk-decay.csv"); Files.writeString(decay, "time_ms,envelope_relative_db,integrated_decay_db\n0,0,0\n");
            @SuppressWarnings("unchecked") List<Map<String, Object>> calibrations = (List<Map<String, Object>>) config.get("calibrations");
            Map<String, Object> calibration = new LinkedHashMap<>(); calibration.put("id", "desk-measurement"); calibration.put("method", "microphone"); calibration.put("verified", false);
            calibration.put("measurement_file", "measurements/desk.wav"); calibration.put("response_curve_file", "measurements/desk-response.csv");
            calibration.put("decay_curve_file", "measurements/desk-decay.csv");
            calibrations.add(calibration);
            Path archive = temp.resolve("backup.amx.zip"); ConfigArchive.exportArchive(archive, config, sourceDir);

            Path destinationDir = temp.resolve("destination");
            try (ConfigStore destination = new ConfigStore(destinationDir.resolve("config.yaml")); ConfigArchive.PreparedImport prepared = ConfigArchive.prepare(archive)) {
                assertEquals(((List<?>) config.get("profiles")).size(), prepared.profileCount());
                assertTrue(prepared.measurementCount() > 0);
                Path collision = destinationDir.resolve("measurements/desk.wav"); Files.createDirectories(collision.getParent()); Files.writeString(collision, "local recording\n");
                Path curveCollision = destinationDir.resolve("measurements/desk-response.csv"); Files.writeString(curveCollision, "local curve\n");
                Path decayCollision = destinationDir.resolve("measurements/desk-decay.csv"); Files.writeString(decayCollision, "local decay\n");
                ConfigArchive.install(prepared, destinationDir, destination);
                assertEquals("local recording\n", Files.readString(collision));
                assertEquals("local curve\n", Files.readString(curveCollision));
                assertEquals("local decay\n", Files.readString(decayCollision));
                Map<String, Object> restored = destination.snapshot();
                @SuppressWarnings("unchecked") List<Map<String, Object>> restoredCalibrations = (List<Map<String, Object>>) restored.get("calibrations");
                Map<String, Object> restoredCalibration = restoredCalibrations.stream().filter(c -> "desk-measurement".equals(c.get("id"))).findFirst().orElseThrow();
                String restoredPath = String.valueOf(restoredCalibration.get("measurement_file"));
                assertTrue(restoredPath.startsWith("measurements/imported/"));
                assertEquals("local recording\n", Files.readString(collision));
                assertEquals(4, Files.readAllBytes(destinationDir.resolve(restoredPath)).length);
                String restoredCurve = String.valueOf(restoredCalibration.get("response_curve_file"));
                assertTrue(restoredCurve.startsWith("measurements/imported/"));
                assertEquals("frequency_hz,relative_db\n80,-2\n", Files.readString(destinationDir.resolve(restoredCurve)));
                String restoredDecay = String.valueOf(restoredCalibration.get("decay_curve_file"));
                assertTrue(restoredDecay.startsWith("measurements/imported/"));
                assertEquals("time_ms,envelope_relative_db,integrated_decay_db\n0,0,0\n", Files.readString(destinationDir.resolve(restoredDecay)));
                assertEquals("desk", restored.get("active_profile"));
            }
        }
    }

    @Test void remapsMissingDeviceSelectorsOnlyToAvailableDevicesOfTheSameDirection() throws Exception {
        Path sourceDir = temp.resolve("device-remap-source");
        Path archive = temp.resolve("device-remap.amx.zip");
        Map<String, Object> sourceConfig = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> devices = (List<Map<String, Object>>) sourceConfig.get("devices");
        devices.add(DefaultConfig.map("id", "haptic-output", "name", "Desk vibration", "role", "haptic_output",
                "selector", DefaultConfig.map("kind", "name_contains", "value", "Old workstation line out"),
                "volume", 0.3, "mute", false));
        try (ConfigStore source = new ConfigStore(sourceDir.resolve("config.yaml"))) {
            ConfigArchive.exportArchive(archive, sourceConfig, sourceDir);
        }

        Path destinationDir = temp.resolve("device-remap-destination");
        try (ConfigStore destination = new ConfigStore(destinationDir.resolve("config.yaml"));
             ConfigArchive.PreparedImport prepared = ConfigArchive.prepare(archive)) {
            ConfigArchive.ImportedDevice imported = prepared.deviceMappings().stream()
                    .filter(device -> "haptic-output".equals(device.id())).findFirst().orElseThrow();
            assertEquals("Old workstation line out", imported.selectorValue());
            List<ConfigArchive.DeviceTarget> available = List.of(
                    new ConfigArchive.DeviceTarget("output", "alsa_output.pci-0000_0b_00.3.analog-surround-40"),
                    new ConfigArchive.DeviceTarget("input", "alsa_input.usb-microphone"));
            assertThrows(ConfigArchive.ArchiveException.class, () -> prepared.remapDeviceSelectors(
                    Map.of("haptic-output", available.get(1)), available));
            assertThrows(ConfigArchive.ArchiveException.class, () -> prepared.remapDeviceSelectors(
                    Map.of("haptic-output", new ConfigArchive.DeviceTarget("output", "not-in-snapshot")), available));

            ConfigArchive.DeviceTarget selected = available.getFirst();
            prepared.remapDeviceSelectors(Map.of("haptic-output", selected), available);
            ConfigArchive.install(prepared, destinationDir, destination);

            Map<String, Object> restored = destination.snapshot();
            Map<String, Object> mapped = ((List<Map<String, Object>>) restored.get("devices")).stream()
                    .filter(device -> "haptic-output".equals(device.get("id"))).findFirst().orElseThrow();
            assertEquals("haptic_output", mapped.get("role"));
            assertEquals("name_equals", ((Map<String, Object>) mapped.get("selector")).get("kind"));
            assertEquals(selected.name(), ((Map<String, Object>) mapped.get("selector")).get("value"));
        }
    }

    @Test void fullImportRejectsConfigChangedAfterPreview() throws Exception {
        Path sourceDir = temp.resolve("conflict-source");
        Path archive = temp.resolve("conflict.amx.zip");
        try (ConfigStore source = new ConfigStore(sourceDir.resolve("config.yaml"))) {
            ConfigArchive.exportArchive(archive, DefaultConfig.create(), sourceDir);
        }

        Path destinationDir = temp.resolve("conflict-destination");
        try (ConfigStore destination = new ConfigStore(destinationDir.resolve("config.yaml"));
             ConfigArchive.PreparedImport prepared = ConfigArchive.prepare(archive)) {
            long previewRevision = destination.revision();
            destination.updateAsync(config -> ((Map<String, Object>) config.get("ui")).put("language", "en")).join();
            String beforeImport = Files.readString(destination.path());

            assertThrows(ConfigStore.ConfigConflictException.class, () ->
                    ConfigArchive.install(prepared, destinationDir, destination, previewRevision));
            assertEquals(beforeImport, Files.readString(destination.path()));
            assertEquals("en", ((Map<String, Object>) destination.snapshot().get("ui")).get("language"));
        }
    }

    @Test void rejectsArchivePathTraversal() throws Exception {
        Path archive = temp.resolve("bad.amx.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) { zip.putNextEntry(new ZipEntry("../escape.txt")); zip.write(1); zip.closeEntry(); }
        assertThrows(ConfigArchive.ArchiveException.class, () -> ConfigArchive.prepare(archive));
        assertFalse(Files.exists(temp.resolve("escape.txt")));
    }

    private static Map<String, Object> readManifest(Path archive) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("manifest.json".equals(entry.getName())) return new ObjectMapper().readValue(zip.readAllBytes(), new TypeReference<>() {});
                zip.closeEntry();
            }
        }
        throw new IllegalStateException("manifest.json is missing");
    }

    private static void rewriteManifest(Path source, Path destination, Consumer<Map<String, Object>> edit) throws Exception {
        ObjectMapper json = new ObjectMapper();
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(source))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) { entries.put(entry.getName(), zip.readAllBytes()); zip.closeEntry(); }
        }
        Map<String, Object> manifest = json.readValue(entries.get("manifest.json"), new TypeReference<>() {});
        edit.accept(manifest);
        if(manifest.get("version") instanceof Number version&&version.intValue()<3&&entries.containsKey("config.json")){
            byte[] document=entries.remove("config.json");entries.put("config.yaml",document);
            @SuppressWarnings("unchecked") Map<String,String> files=(Map<String,String>)manifest.get("files");String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(document));files.remove("config.json");files.put("config.yaml",digest);
        }
        entries.put("manifest.json", json.writeValueAsBytes(manifest));
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(destination))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
        }
    }
}
