package io.github.ilyaandreev05.audiomatrix.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Portable backup for Audio Matrix JSON configs, routes, and referenced calibration files. */
public final class ConfigArchive {
    private static final int MAX_ENTRIES = 512;
    private static final long MAX_BYTES = 512L * 1024 * 1024;
    private static final List<String> CALIBRATION_FILE_FIELDS = List.of("measurement_file", "response_curve_file", "decay_curve_file");
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private ConfigArchive() {}

    public static void exportArchive(Path destination, Map<String, Object> config, Path dataDirectory) throws IOException {
        List<String> errors = ConfigValidator.validate(config);
        if (!errors.isEmpty()) throw new ArchiveException("Configuration is invalid: " + String.join("; ", errors));
        Path root = dataDirectory.toAbsolutePath().normalize();
        byte[] document = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(config);
        TreeMap<String, Path> assets = measurementFiles(config, root);
        TreeMap<String, String> hashes = new TreeMap<>(); hashes.put("config.json", ArchiveFiles.sha256(document));
        for (var asset : assets.entrySet()) hashes.put("data/" + asset.getKey(), ArchiveFiles.sha256(asset.getValue()));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", ArchiveManifestMigrator.FORMAT);
        manifest.put("version", ArchiveManifestMigrator.CURRENT_VERSION);
        manifest.put("app", ArchiveManifestMigrator.APP);
        manifest.put("config_schema_version", integer(config.get("schema_version"), -1));
        manifest.put("config_entry", "config.json");
        manifest.put("kind", "full");
        manifest.put("files", hashes);
        byte[] manifestBytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
        Path target = destination.toAbsolutePath().normalize(); Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".audio-matrix-archive-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp); ZipOutputStream zip = new ZipOutputStream(out)) {
                ArchiveFiles.put(zip, "manifest.json", manifestBytes); ArchiveFiles.put(zip, "config.json", document);
                for (var asset : assets.entrySet()) ArchiveFiles.put(zip, "data/" + asset.getKey(), asset.getValue());
            }
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { throw new ArchiveException("Archive destination does not support atomic replacement", e); }
        } finally { Files.deleteIfExists(temp); }
    }

    public static PreparedImport prepare(Path archive) throws IOException {
        Path staging = Files.createTempDirectory("audio-matrix-restore-");
        try {
            Map<String, Path> extracted = new LinkedHashMap<>(); long total = 0; int count = 0;
            try (InputStream file = Files.newInputStream(archive); ZipInputStream zip = new ZipInputStream(file)) {
                ZipEntry entry; byte[] buffer = new byte[16 * 1024];
                while ((entry = zip.getNextEntry()) != null) {
                    if (++count > MAX_ENTRIES) throw new ArchiveException("Archive has too many entries");
                    if (entry.isDirectory()) { zip.closeEntry(); continue; }
                    String name = entry.getName(); ArchiveFiles.validatePath(name);
                    if (extracted.containsKey(name)) throw new ArchiveException("Archive contains a duplicate path: " + name);
                    Path output = staging.resolve(name).normalize(); if (!output.startsWith(staging)) throw new ArchiveException("Archive path escapes staging");
                    Files.createDirectories(output.getParent()); long size = 0;
                    try (OutputStream out = Files.newOutputStream(output)) {
                        int read; while ((read = zip.read(buffer)) >= 0) if (read > 0) { size += read; total += read; if (total > MAX_BYTES) throw new ArchiveException("Archive exceeds 512 MiB expanded size"); out.write(buffer, 0, read); }
                    }
                    if (size == 0) throw new ArchiveException("Archive contains an empty file: " + name);
                    extracted.put(name, output); zip.closeEntry();
                }
            }
            if (!extracted.containsKey("manifest.json")) throw new ArchiveException("Archive must contain manifest.json");
            Map<String, Object> manifest = JSON.readValue(Files.readAllBytes(extracted.get("manifest.json")), MAP_TYPE);
            int archiveVersion=integer(manifest.get("version"),-1);
            String configEntry=archiveVersion>=3?String.valueOf(manifest.getOrDefault("config_entry","config.json")):"config.yaml";
            if(!List.of("config.json","config.yaml").contains(configEntry)||!extracted.containsKey(configEntry))throw new ArchiveException("Archive is missing its declared config document");
            if (!(manifest.get("files") instanceof Map<?, ?> fileTable)) throw new ArchiveException("Manifest has no file checksums");
            TreeMap<String, String> hashes = new TreeMap<>();
            for (var item : fileTable.entrySet()) {
                if (!(item.getKey() instanceof String path) || !(item.getValue() instanceof String hash) || !hash.matches("[0-9a-f]{64}")) throw new ArchiveException("Manifest contains an invalid file checksum");
                ArchiveFiles.validatePath(path); hashes.put(path, hash);
            }
            java.util.Set<String> actual = new java.util.TreeSet<>(extracted.keySet()); actual.remove("manifest.json");
            if (!actual.equals(hashes.keySet())) throw new ArchiveException("Archive contents do not match manifest");
            for (var item : hashes.entrySet()) if (!item.getValue().equals(ArchiveFiles.sha256(extracted.get(item.getKey())))) throw new ArchiveException("Checksum mismatch: " + item.getKey());
            Map<String, Object> config = "config.json".equals(configEntry)
                    ? JSON.readValue(Files.readAllBytes(extracted.get(configEntry)), MAP_TYPE)
                    : YAML.readValue(Files.readAllBytes(extracted.get(configEntry)), MAP_TYPE);
            if (config == null) throw new ArchiveException("Configuration must be a JSON or YAML mapping");
            int sourceSchema=integer(config.get("schema_version"),-1);
            manifest = ArchiveManifestMigrator.migrate(manifest, sourceSchema);
            if(sourceSchema<3){ConfigStore.upgradeConfig(config);manifest.put("config_schema_version",integer(config.get("schema_version"),-1));}
            List<String> errors = ConfigValidator.validate(config); if (!errors.isEmpty()) throw new ArchiveException("Imported config is invalid: " + String.join("; ", errors));
            verifyReferences(config, hashes);
            return new PreparedImport(staging, copyMap(config), hashes, count(config, "profiles"), count(config, "routes"), (int) hashes.keySet().stream().filter(s -> s.startsWith("data/")).count());
        } catch (Exception e) {
            ArchiveFiles.deleteTree(staging); if (e instanceof IOException io) throw io; throw new ArchiveException("Could not prepare archive: " + e.getMessage(), e);
        }
    }

    public static void install(PreparedImport prepared, Path dataDirectory, ConfigStore store) throws IOException, ConfigStore.ConfigValidationException, ConfigStore.ConfigConflictException {
        install(prepared, dataDirectory, store, store.revision());
    }

    public static void install(PreparedImport prepared, Path dataDirectory, ConfigStore store, long expectedRevision) throws IOException, ConfigStore.ConfigValidationException, ConfigStore.ConfigConflictException {
        if (prepared.closed) throw new ArchiveException("Prepared import has already been closed");
        store.requireRevision(expectedRevision);
        Path root = dataDirectory.toAbsolutePath().normalize(); Map<String, String> remapped = new LinkedHashMap<>();
        for (String archivePath : prepared.hashes.keySet()) {
            if (!archivePath.startsWith("data/")) continue;
            String relative = archivePath.substring(5); Path rel = ArchiveFiles.safeRelative(relative); Path target = root.resolve(rel).normalize();
            if (!target.startsWith(root)) throw new ArchiveException("Measurement path escapes the config directory"); ArchiveFiles.ensureNoSymlinkParents(root, target);
            String destRelative = relative;
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && prepared.hashes.get(archivePath).equals(ArchiveFiles.sha256(target))) { remapped.put(relative, relative); continue; }
                String hash = prepared.hashes.get(archivePath); String leaf = rel.getFileName().toString(); String extension = leaf.lastIndexOf('.') > 0 ? leaf.substring(leaf.lastIndexOf('.')) : ""; String stem = leaf.substring(0, leaf.length() - extension.length());
                destRelative = "measurements/imported/" + stem + "-" + hash.substring(0, 12) + extension; target = root.resolve(destRelative).normalize(); int suffix = 1;
                while (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !prepared.hashes.get(archivePath).equals(ArchiveFiles.sha256(target))) { destRelative = "measurements/imported/" + stem + "-" + hash.substring(0, 12) + "-" + suffix++ + extension; target = root.resolve(destRelative).normalize(); }
                ArchiveFiles.ensureNoSymlinkParents(root, target);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { remapped.put(relative, destRelative); continue; }
            }
            ArchiveFiles.copyAtomically(prepared.staging.resolve(archivePath), target); remapped.put(relative, destRelative);
        }
        Object calibrationValue = prepared.config.get("calibrations");
        if (calibrationValue instanceof List<?> calibrations) for (Object item : calibrations) if (item instanceof Map<?, ?> raw) {
            @SuppressWarnings("unchecked") Map<String, Object> calibration = (Map<String, Object>) raw;
            for (String field : CALIBRATION_FILE_FIELDS) {
                Object path = calibration.get(field);
                if (path instanceof String relative && remapped.containsKey(relative)) calibration.put(field, remapped.get(relative));
            }
        }
        store.saveIfRevision(prepared.config, expectedRevision);
    }

    public static final class PreparedImport implements AutoCloseable {
        private final Path staging; private final Map<String, Object> config; private final Map<String, String> hashes;
        private final int profileCount, routeCount, measurementCount; private boolean closed;
        private PreparedImport(Path staging, Map<String, Object> config, Map<String, String> hashes, int profiles, int routes, int measurements) { this.staging=staging;this.config=config;this.hashes=Map.copyOf(hashes);this.profileCount=profiles;this.routeCount=routes;this.measurementCount=measurements; }
        public int profileCount(){return profileCount;} public int routeCount(){return routeCount;} public int measurementCount(){return measurementCount;}
        public List<ImportedDevice> deviceMappings() {
            List<ImportedDevice> result = new ArrayList<>();
            for (Map<String, Object> device : maps(config.get("devices"))) {
                Map<String, Object> selector = objectMap(device.get("selector"));
                result.add(new ImportedDevice(String.valueOf(device.getOrDefault("id", "")),
                        String.valueOf(device.getOrDefault("name", device.getOrDefault("id", "Device"))),
                        String.valueOf(device.getOrDefault("role", "")),
                        String.valueOf(selector.getOrDefault("kind", "")),
                        String.valueOf(selector.getOrDefault("value", ""))));
            }
            return List.copyOf(result);
        }
        /** Rebinds imported device IDs to explicitly chosen local PipeWire node names. */
        public void remapDeviceSelectors(Map<String, DeviceTarget> remapping, List<DeviceTarget> availableTargets) throws IOException {
            if (closed) throw new ArchiveException("Prepared import has already been closed");
            if (remapping == null || remapping.isEmpty()) return;
            Set<DeviceTarget> available = new LinkedHashSet<>(availableTargets);
            Map<String, Map<String, Object>> devicesById = new LinkedHashMap<>();
            for (Map<String, Object> device : maps(config.get("devices"))) devicesById.put(String.valueOf(device.get("id")), device);
            Map<String, Object> updated = copyMap(config);
            Map<String, Map<String, Object>> updatedById = new LinkedHashMap<>();
            for (Map<String, Object> device : maps(updated.get("devices"))) updatedById.put(String.valueOf(device.get("id")), device);
            for (var item : remapping.entrySet()) {
                Map<String, Object> existing = devicesById.get(item.getKey());
                DeviceTarget target = item.getValue();
                if (existing == null) throw new ArchiveException("Imported device does not exist: " + item.getKey());
                if (target == null || !available.contains(target)) throw new ArchiveException("Selected device is not available: " + target);
                String role = String.valueOf(existing.getOrDefault("role", ""));
                String requiredKind = deviceDirection(role);
                if (!requiredKind.isBlank() && !requiredKind.equals(target.kind()))
                    throw new ArchiveException("Selected device has the wrong direction for " + item.getKey());
                Map<String, Object> mapped = updatedById.get(item.getKey());
                mapped.put("selector", DefaultConfig.map("kind", "name_equals", "value", target.name()));
                if (requiredKind.isBlank()) mapped.put("role", target.kind());
            }
            List<String> errors = ConfigValidator.validate(updated);
            if (!errors.isEmpty()) throw new ArchiveException("Remapped configuration is invalid: " + String.join("; ", errors));
            config.clear();
            config.putAll(updated);
        }
        @Override public void close(){if(!closed){closed=true;ArchiveFiles.deleteTree(staging);}}
    }

    public record ImportedDevice(String id, String name, String role, String selectorKind, String selectorValue) {}
    public record DeviceTarget(String kind, String name) {
        public DeviceTarget {
            if (!Set.of("output", "input").contains(kind) || name == null || name.isBlank())
                throw new IllegalArgumentException("A device target needs an input/output direction and node name");
        }
    }

    private static String deviceDirection(String role) {
        if (Set.of("source", "input", "microphone", "accelerometer").contains(role)) return "input";
        if (Set.of("output", "speaker", "speakers", "subwoofer", "haptic_output").contains(role)) return "output";
        return "";
    }

    private static TreeMap<String, Path> measurementFiles(Map<String, Object> config, Path root) throws IOException {
        TreeMap<String, Path> files = new TreeMap<>(); Object value = config.get("calibrations"); if (!(value instanceof List<?> calibrations)) return files;
        for (Object item : calibrations) if (item instanceof Map<?, ?> calibration)
            for (String field : CALIBRATION_FILE_FIELDS) if (calibration.get(field) instanceof String raw) {
                Path rel = ArchiveFiles.safeRelative(raw); Path file = root.resolve(rel).normalize();
                if (!file.startsWith(root) || !ArchiveFiles.regularWithoutSymlinks(root, file)) throw new ArchiveException("Measurement file is missing or outside config directory: " + raw);
                files.put(rel.toString().replace('\\','/'), file);
            }
        return files;
    }
    private static void verifyReferences(Map<String, Object> config, Map<String, String> hashes) throws IOException {
        Object value=config.get("calibrations");if(!(value instanceof List<?> calibrations))return;
        for(Object item:calibrations)if(item instanceof Map<?,?> c)for(String field:CALIBRATION_FILE_FIELDS)if(c.get(field) instanceof String raw){String path="data/"+ArchiveFiles.safeRelative(raw).toString().replace('\\','/');if(!hashes.containsKey(path))throw new ArchiveException("Archive is missing calibration file: "+raw);}
    }
    private static int integer(Object value,int fallback){return value instanceof Number n?n.intValue():fallback;}
    private static int count(Map<String,Object> config,String key){return config.get(key) instanceof List<?> list?list.size():0;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> maps(Object value){return value instanceof List<?> list?(List<Map<String,Object>>) (List<?>)list:List.of();}
    @SuppressWarnings("unchecked") private static Map<String,Object> objectMap(Object value){return value instanceof Map<?,?> map?(Map<String,Object>)map:new LinkedHashMap<>();}
    @SuppressWarnings("unchecked")private static Map<String,Object> copyMap(Map<String,Object> source){return (Map<String,Object>)deep(source);}
    private static Object deep(Object value){if(value instanceof Map<?,?> m){Map<String,Object> out=new LinkedHashMap<>();m.forEach((k,v)->out.put(String.valueOf(k),deep(v)));return out;}if(value instanceof List<?> l){List<Object> out=new ArrayList<>();l.forEach(v->out.add(deep(v)));return out;}return value;}
    public static final class ArchiveException extends IOException{public ArchiveException(String message){super(message);}public ArchiveException(String message,Throwable cause){super(message,cause);}}
}
