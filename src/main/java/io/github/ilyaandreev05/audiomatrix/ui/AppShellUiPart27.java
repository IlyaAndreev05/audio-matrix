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

abstract class AppShellUiPart27 extends AppShellUiPart26 {
    protected AppShellUiPart27(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    protected String polarityOptionLabel(String polarity) {
        return switch (polarity) {
            case "normal" -> t("Обычная", "Normal");
            case "invert" -> t("Инвертировать все", "Invert all");
            case "invert_left" -> t("Инвертировать левый", "Invert left");
            case "invert_right" -> t("Инвертировать правый", "Invert right");
            default -> polarity;
        };
    }

    protected String t(String ru, String en) { return "en".equals(setting(store.snapshot(), "ui", "language", "ru")) ? en : ru; }

    protected List<AudioNode> filtered(List<AudioNode> nodes, String query) {
        if (query == null || query.isBlank()) return nodes;
        String q = query.toLowerCase(Locale.ROOT);
        return nodes.stream().filter(node -> (nodeDisplayName(node) + " " + node.description() + " " + node.name() + " " + node.applicationName() + " " + node.processName() + " " + node.applicationId()).toLowerCase(Locale.ROOT).contains(q)).toList();
    }
}
