package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.ThemeManager;
import io.github.ilyaandreev05.audiomatrix.audio.*;
import io.github.ilyaandreev05.audiomatrix.config.*;
import io.github.ilyaandreev05.audiomatrix.gnome.*;
import io.github.ilyaandreev05.audiomatrix.graph.*;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.transform.Scale;
import javafx.stage.*;
import javafx.util.Duration;
import javafx.util.StringConverter;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

abstract class AppShellBase extends BorderPane implements AppShellTypes {
    protected static boolean isButtonTarget(Object target,Node root){Node current=target instanceof Node node?node:null;while(current!=null&&current!=root){if(current instanceof Button)return true;current=current.getParent();}return false;}

    protected static boolean calibrationFilterRefUsedElsewhere(Map<String, Object> config, String ref,
                                                               String ignoredDeviceId, String ignoredCalibrationId) {
        for (String collection : List.of("devices", "applications", "buses", "routes", "calibrations")) {
            for (Map<String, Object> item : maps(config.get(collection))) {
                String id = string(item.get("id"), "");
                if ("devices".equals(collection) && ignoredDeviceId.equals(id)) continue;
                if ("calibrations".equals(collection) && ignoredCalibrationId.equals(id)) continue;
                if (ref.equals(item.get("filters_ref"))) return true;
            }
        }
        return false;
    }

    protected static Map<String, Object> copyCalibrationChain(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>(source);
        List<Map<String, Object>> filters = new ArrayList<>();
        for (Map<String, Object> filter : maps(source.get("filters"))) filters.add(new LinkedHashMap<>(filter));
        copy.put("filters", filters);
        return copy;
    }

    protected static void deleteTemporaryCalibrationDirectory(Path directory) {
        if (directory == null) return;
        Path temporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        Path normalized = directory.toAbsolutePath().normalize();
        if (!temporaryRoot.equals(normalized.getParent()) || !normalized.getFileName().toString().startsWith("audio-matrix-calibration-")) return;
        try (var paths = Files.walk(normalized)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) {}
    }

    protected static Path savedCalibrationAsset(Path root, String raw) throws IOException {
        Path relative;
        try { relative = Path.of(raw).normalize(); }
        catch (RuntimeException invalid) { throw new IOException("Saved calibration file path is invalid", invalid); }
        if (raw.isBlank() || relative.isAbsolute() || relative.startsWith("..") || !relative.toString().equals(raw))
            throw new IOException("Calibration files must stay inside the Audio Matrix config directory");
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) throw new IOException("Saved calibration file is outside the config directory");
        checkMeasurementParents(root, target);
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target))
            throw new IOException("Saved calibration file is missing or is a symbolic link");
        return target;
    }

    protected static void atomicCopyMeasurement(Path root, Path source, Path target) throws IOException {
        Path parent = target.getParent(); createMeasurementParents(root, target);
        Path temporary = Files.createTempFile(parent, ".measurement-", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Measurement directory does not support atomic file replacement", unsupported);
        } finally { Files.deleteIfExists(temporary); }
    }

    protected static void atomicWriteMeasurement(Path root, Path target, String content) throws IOException {
        Path parent = target.getParent(); createMeasurementParents(root, target);
        Path temporary = Files.createTempFile(parent, ".measurement-", ".tmp");
        try {
            Files.writeString(temporary, content);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Measurement directory does not support atomic file replacement", unsupported);
        } finally { Files.deleteIfExists(temporary); }
    }

    protected static void createMeasurementParents(Path root, Path target) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!target.toAbsolutePath().normalize().startsWith(normalizedRoot)) throw new IOException("Measurement path escapes the config directory");
        Path current = normalizedRoot;
        for (Path part : normalizedRoot.relativize(target.getParent().toAbsolutePath().normalize())) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Measurement path traverses a symbolic link");
            if (!Files.exists(current, java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(current);
            else if (!Files.isDirectory(current, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new IOException("Measurement parent is not a directory");
        }
    }

    protected static void checkMeasurementParents(Path root, Path target) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!target.toAbsolutePath().normalize().startsWith(normalizedRoot)) throw new IOException("Measurement path escapes the config directory");
        Path current = normalizedRoot;
        for (Path part : normalizedRoot.relativize(target.getParent().toAbsolutePath().normalize())) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Measurement path traverses a symbolic link");
            if (!Files.isDirectory(current, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new IOException("Measurement parent directory is missing");
        }
    }

    protected static String deviceDirection(String role) {
        if (Set.of("source", "input", "microphone", "accelerometer").contains(role)) return "input";
        if (Set.of("output", "speaker", "speakers", "subwoofer", "haptic_output").contains(role)) return "output";
        return "";
    }

    protected static List<String> matrixBusPositions(String layout) {
        return switch (layout) {
            case "mono" -> List.of("MONO");
            case "5.1" -> List.of("FL", "FR", "FC", "LFE", "RL", "RR");
            case "7.1" -> List.of("FL", "FR", "FC", "LFE", "RL", "RR", "SL", "SR");
            default -> List.of("FL", "FR");
        };
    }

    protected static List<String> matrixStreamPositions(int channels) {
        return switch (channels) {
            case 1 -> List.of("MONO");
            case 3 -> List.of("FL", "FR", "FC");
            case 4 -> List.of("FL", "FR", "RL", "RR");
            case 5 -> List.of("FL", "FR", "FC", "RL", "RR");
            case 6 -> List.of("FL", "FR", "FC", "LFE", "RL", "RR");
            case 7 -> List.of("FL", "FR", "FC", "LFE", "RL", "RR", "RC");
            case 8 -> List.of("FL", "FR", "FC", "LFE", "RL", "RR", "SL", "SR");
            default -> List.of("FL", "FR");
        };
    }

    protected static boolean isPassFilter(String type) { return "high_pass".equals(type) || "low_pass".equals(type); }

    protected static boolean limiterEnabled(Map<String, Object> config) {
        return Boolean.TRUE.equals(map(map(config.get("dsp")).get("limiter")).get("enabled"));
    }

    protected static Map<String, Object> activeProfile(Map<String, Object> config) {
        String id = string(config.get("active_profile"), "desk");
        return maps(config.get("profiles")).stream().filter(p -> id.equals(p.get("id"))).findFirst().orElseGet(() -> maps(config.get("profiles")).stream().findFirst().orElseGet(LinkedHashMap::new));
    }

    public static String setting(Map<String, Object> config, String section, String key, String fallback) { return string(map(config.get(section)).get(key), fallback); }

    protected static boolean matchesSearch(String text, String query) { return query == null || query.isBlank() || text.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)); }

    @SuppressWarnings("unchecked") protected static Map<String, Object> map(Object value) { return value instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>(); }

    @SuppressWarnings("unchecked") protected static List<Map<String, Object>> maps(Object value) { return value instanceof List<?> l ? (List<Map<String, Object>>) l : new ArrayList<>(); }

    protected static Map<String, Object> copyMap(Map<String, Object> value) { return new LinkedHashMap<>(value); }

    protected static String string(Object value, String fallback) { return value == null ? fallback : String.valueOf(value); }

    protected static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    protected static double number(Object value, double fallback) { return value instanceof Number n ? n.doubleValue() : fallback; }

    protected static boolean finite(double value) { return Double.isFinite(value); }

    protected static String volumeLabel(double volume) { return Double.isFinite(volume) ? Math.round(volume * 100) + "%" : "—"; }

    protected static String rootMessage(Throwable error) {
        Throwable current = error; while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    protected static String slug(String value) { return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", ""); }

    protected static int indexOf(List<Map<String, Object>> list, String id) { for (int i=0;i<list.size();i++) if (id.equals(list.get(i).get("id"))) return i; return -1; }

    protected static boolean executable(String name) { return java.util.Arrays.stream(System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)).anyMatch(path -> java.nio.file.Files.isExecutable(java.nio.file.Path.of(path, name))); }

    protected static final class DefaultConfigMap {
        static Map<String, Object> map(Object... pairs) { Map<String, Object> result = new LinkedHashMap<>(); for (int i=0;i<pairs.length;i+=2) result.put((String)pairs[i], pairs[i+1]); return result; }
    }
}
