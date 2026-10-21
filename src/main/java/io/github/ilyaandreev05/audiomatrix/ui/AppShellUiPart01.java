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

abstract class AppShellUiPart01 extends AppShellState {
    protected AppShellUiPart01(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }
    private double graphPageScrollH;
    private double graphPageScrollV;

    protected void initializeShell() {

        getStyleClass().add("app-shell");
        buildChrome();
        showPage(Page.HOME);
        search.textProperty().addListener((obs, old, value) -> showPage(currentPage));
        store.addListener(event -> {
            if (suppressConfigListener) return;
            Platform.runLater(() -> {
                if (event.message().startsWith("Configuration rejected:")) setNotice(event.message(), true);
                else {
                    setNotice(event.message(), false);
                    applyConfiguredDeviceRules(snapshot, event.config());
                    applyConfiguredStreamRules(snapshot, event.config());
                    maybeApplyConfiguredRoutes(event.config(), snapshot);
                    showPage(currentPage);
                }
                publishGnomeState();
            });
        });

    }

    public void start() {
        meterRefresh.setCycleCount(Timeline.INDEFINITE);
        meterRefresh.play();
        routeRefresh = new Timeline(new KeyFrame(javafx.util.Duration.seconds(1),
                event -> maybeApplyConfiguredRoutes(store.snapshot(), backend.current())));
        routeRefresh.setCycleCount(Timeline.INDEFINITE);
        routeRefresh.play();
        gnomeBridge.start();
        publishGnomeState();
        backend.start(next -> Platform.runLater(() -> {
            snapshot = next;
            syncSystemMasterFromDefaultSink(next, store.snapshot());
            applyConfiguredDeviceRules(next,store.snapshot());
            applyConfiguredStreamRules(next, store.snapshot());
            maybeApplyConfiguredRoutes(store.snapshot(), next);
            if (selectedSink != null) selectedSink = next.sinks().stream().filter(n -> n.name().equals(selectedSink.name())).findFirst().orElse(null);
            if (selectedSink == null) selectedSink = configuredSink();
            if (selectedSink == null) selectedSink = next.sinks().stream().filter(n -> n.id() == next.defaultSinkId()).findFirst().orElse(null);
            updateMeterTarget();
            connection.setText(next.connected() ? t("PipeWire подключён", "PipeWire connected") : t("PipeWire недоступен", "PipeWire unavailable"));
            connection.getStyleClass().removeAll("accent", "danger", "warning");
            if(currentPage==Page.GRAPH)refreshGraphPageRuntime();
            else showPage(currentPage);
            publishGnomeState();
        }));
    }

    protected void buildChrome() {
        sidebar.getStyleClass().add("sidebar");
        HBox brand = new HBox(9, new Label("AM"), new Label("Audio Matrix"));
        brand.getChildren().getFirst().getStyleClass().add("brand-mark");
        brand.getChildren().getLast().getStyleClass().add("brand");
        brand.setAlignment(Pos.CENTER_LEFT);
        sidebar.getChildren().add(brand);
        Separator separator = new Separator(); sidebar.getChildren().add(separator);
        ToggleGroup group = new ToggleGroup();
        for (Page page : Page.values()) {
            ToggleButton button = new ToggleButton(pageLabel(page));
            button.getStyleClass().add("nav-button");
            button.setMaxWidth(Double.MAX_VALUE);
            button.setAlignment(Pos.CENTER_LEFT);
            button.setAccessibleText(pageLabel(page));
            button.setToggleGroup(group);
            button.setOnAction(event -> showPage(page));
            navButtons.put(page, button);
            sidebar.getChildren().add(button);
        }
        Region spacer = new Region(); VBox.setVgrow(spacer, Priority.ALWAYS); sidebar.getChildren().add(spacer);
        connection.getStyleClass().add("status-pill");
        sidebar.getChildren().addAll(new Separator(), connection, label(t("Настройки сохраняются в config.json", "Settings are stored in config.json"), "muted"));
        sidebar.setPrefWidth(228);

        HBox topbar = new HBox(14);
        title.getStyleClass().add("page-title");
        search.setPromptText(t("Поиск в разделе…", "Search this section…"));
        search.setAccessibleText(t("Поиск в текущем разделе", "Search the current section"));
        search.setPrefWidth(250);
        Region push = new Region(); HBox.setHgrow(push, Priority.ALWAYS);
        notice.setVisible(false); notice.setManaged(false);
        topbar.getChildren().addAll(title, push, search);
        topbar.getStyleClass().add("topbar");
        body.setTop(topbar);
        setLeft(sidebar); setCenter(body);
        setPadding(Insets.EMPTY);
    }

    protected void showPage(Page page) {
        if(currentPage==Page.GRAPH&&body.getCenter() instanceof ScrollPane previousGraphScroll){
            graphPageScrollH=previousGraphScroll.getHvalue();graphPageScrollV=previousGraphScroll.getVvalue();
        }
        currentPage = page;
        if (page == Page.DSP) backend.monitorOutput(selectedSink);
        else {
            backend.monitorOutput(null);
            if (inputMeterEnabled) inputMeterEnabled = false;
            backend.monitorInput(null);
        }
        ToggleButton button = navButtons.get(page);
        if (button != null) button.setSelected(true);
        title.setText(pageLabel(page));
        Node content = switch (page) {
            case HOME -> homePage();
            case APPLICATIONS -> applicationsPage();
            case DEVICES -> devicesPage();
            case ROUTES -> routesPage();
            case GRAPH -> graphPage();
            case DSP -> dspPage();
            case CALIBRATION -> calibrationPage();
            case PROFILES -> profilesPage();
            case SETTINGS -> settingsPage();
        };
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true); scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("page-scroll");
        body.setCenter(scroll);
        if(page==Page.GRAPH)Platform.runLater(()->{
            if(currentPage==Page.GRAPH&&body.getCenter()==scroll){scroll.setHvalue(graphPageScrollH);scroll.setVvalue(graphPageScrollV);}
        });
        if (notice.isVisible()) body.setBottom(notice);
    }
}
