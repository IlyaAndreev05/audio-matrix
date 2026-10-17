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

interface AppShellTypes {
    enum Page { HOME, APPLICATIONS, DEVICES, ROUTES, GRAPH, DSP, CALIBRATION, PROFILES, SETTINGS }
    record Endpoint(String id, String label, boolean bus, AudioNode node) {}
    record MeasurementPreview(Path source, AudioNode output, String method, String sensorSource,
                                     String sensorSourceDescription, int channel, double startHz, double endHz,
                                     int steps, int dwellMs, int offsetMs, String sensorMount,
                                     CalibrationMeasurementAnalyzer.Result result, String measurementFile,
                                     String responseCurveFile, String calibrationId, Path temporaryDirectory) {}
    record ImpulseDecayPreview(Path source, AudioNode output, String method, int channel, String sensorMount,
                                       int impulseOffsetMs, int responseWindowMs, CalibrationImpulseAnalyzer.Result result,
                                       String measurementFile, String decayCurveFile, String calibrationId) {}
    record CalibrationComparisonPreview(String beforeId, String afterId, String beforeLabel, String afterLabel,
                                                CalibrationMeasurementAnalyzer.Comparison comparison,
                                                boolean clipped, boolean verified) {}
    record SavedCalibrationFiles(Path recording, Path curve, CalibrationMeasurementAnalyzer.Result result) {}
    record CalibrationFilterRollback(String deviceId, String calibrationId, String previousRef,
                                             Map<String, Object> previousChain, String appliedRef,
                                             Map<String, Object> appliedChain, String outputDescription) {}
    record RouteHistoryEntry(String description, Map<String, Object> previousSnapshot) {}
    record GraphPortRef(String routeId,String nodeId,String portId) {}
}
