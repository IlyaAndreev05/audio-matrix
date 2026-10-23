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

abstract class AppShellUiPart26 extends AppShellUiPart25 {
    protected AppShellUiPart26(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    protected void createProfile() {
        TextInputDialog dialog=new TextInputDialog(t("Новый профиль","New profile"));
        dialog.setTitle(t("Создать пустой профиль","Create empty profile"));
        dialog.setHeaderText(t("Параметры выхода скопируются из активного профиля, графов в новом профиле не будет.","Output preferences are copied from the active profile; the new profile starts without graphs."));
        dialog.setContentText(t("Название","Name"));styleDialog(dialog);
        String template=string(store.snapshot().get("active_profile"),"desk");
        dialog.showAndWait().map(String::trim).filter(name->!name.isBlank()).ifPresent(name->updateRouteConfig(t("Создать профиль","Create profile"),config->GraphProfileLibrary.createProfile(config,template,name)));
    }

    protected void renameProfile(String id) {
        Map<String, Object> profile = maps(store.snapshot().get("profiles")).stream().filter(p -> id.equals(p.get("id"))).findFirst().orElse(null);
        if (profile == null) return;
        TextInputDialog dialog = new TextInputDialog(string(profile.get("name"), id));
        dialog.setTitle(t("Переименовать профиль", "Rename profile")); dialog.setHeaderText(null); dialog.setContentText(t("Имя", "Name"));
        dialog.showAndWait().map(String::trim).filter(name -> !name.isBlank()).ifPresent(name -> updateConfig(config -> {
            maps(config.get("profiles")).stream().filter(p -> id.equals(p.get("id"))).findFirst().ifPresent(p -> p.put("name", name));
        }));
    }

    protected void deleteProfile(String id) {
        Map<String, Object> current = store.snapshot(); List<Map<String, Object>> profiles = maps(current.get("profiles"));
        if (profiles.size() <= 1) { setNotice(t("Нельзя удалить последний профиль.", "The last profile cannot be deleted."), true); return; }
        Map<String, Object> selected = profiles.stream().filter(p -> id.equals(p.get("id"))).findFirst().orElse(null); if (selected == null) return;
        boolean deletingActive=id.equals(current.get("active_profile"));
        if(!deletingActive){
            Alert confirm=new Alert(Alert.AlertType.CONFIRMATION,t("Удалить профиль «","Delete profile ")+string(selected.get("name"),id)+"? Его группы и графы также будут удалены.",ButtonType.OK,ButtonType.CANCEL);
            confirm.initOwner(getScene().getWindow());confirm.setHeaderText(null);
            confirm.showAndWait().filter(button->button==ButtonType.OK).ifPresent(button->updateRouteConfig(t("Удалить профиль","Delete profile"),config->GraphProfileLibrary.deleteProfile(config,id,null,null,false)));
            return;
        }
        Dialog<ButtonType> dialog=new Dialog<>();if(getScene()!=null)dialog.initOwner(getScene().getWindow());
        dialog.setTitle(t("Удалить активный профиль","Delete active profile"));
        dialog.setHeaderText(t("Выбери профиль и маршрут, которые станут активными. Удалятся все графы и группы удаляемого профиля.","Choose the profile and route to activate. All graphs and groups in the deleted profile will be removed."));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK,ButtonType.CANCEL);
        List<Map<String,Object>> replacements=profiles.stream().filter(profile->!id.equals(profile.get("id"))).toList();
        ComboBox<Map<String,Object>> replacementPicker=new ComboBox<>();replacementPicker.getItems().setAll(replacements);replacementPicker.setConverter(new StringConverter<>(){
            @Override public String toString(Map<String,Object> profile){return profile==null?"":string(profile.get("name"),string(profile.get("id"),""));}
            @Override public Map<String,Object> fromString(String value){return null;}
        });replacementPicker.getSelectionModel().selectFirst();
        ComboBox<Map<String,Object>> routePicker=new ComboBox<>();routePicker.setConverter(new StringConverter<>(){
            @Override public String toString(Map<String,Object> route){return route==null?t("Нет маршрута","No route"):string(route.get("name"),string(route.get("id"),""));}
            @Override public Map<String,Object> fromString(String value){return null;}
        });
        CheckBox stopGraph=new CheckBox(t("Остановить граф при удалении","Stop the graph when deleting"));
        Runnable refreshRoutes=()->{
            Map<String,Object> replacement=replacementPicker.getValue();
            List<Map<String,Object>> routes=replacement==null?List.of():GraphRouteLibrary.routesForProfile(current,string(replacement.get("id"),""));
            routePicker.getItems().setAll(routes);
            String remembered=replacement==null?"":string(replacement.get("selected_route_id"),"");
            routes.stream().filter(route->remembered.equals(route.get("id"))).findFirst().or(()->routes.stream().findFirst()).ifPresent(routePicker.getSelectionModel()::select);
            if(routes.isEmpty())routePicker.getSelectionModel().clearSelection();
        };
        replacementPicker.setOnAction(event->refreshRoutes.run());refreshRoutes.run();
        routePicker.disableProperty().bind(stopGraph.selectedProperty());
        VBox content=new VBox(9,label(t("Новый профиль","Replacement profile"),"muted"),replacementPicker,
                label(t("Маршрут для активации","Route to activate"),"muted"),routePicker,stopGraph);
        dialog.getDialogPane().setContent(content);styleDialog(dialog);
        Button confirm=(Button)dialog.getDialogPane().lookupButton(ButtonType.OK);
        confirm.addEventFilter(javafx.event.ActionEvent.ACTION,event->{
            if(replacementPicker.getValue()==null||(!stopGraph.isSelected()&&routePicker.getValue()==null)){
                event.consume();setNotice(t("Выбери маршрут или явно отметь остановку графа.","Choose a route or explicitly select Stop graph."),true);
            }
        });
        dialog.showAndWait().filter(button->button==ButtonType.OK).ifPresent(button->{
            Map<String,Object> replacement=replacementPicker.getValue();String replacementId=string(replacement.get("id"),"");
            String routeId=routePicker.getValue()==null?"":string(routePicker.getValue().get("id"),"");
            String output=string(replacement.get("default_output"),"system_default");
            AudioNode target=DeviceSelectorMatcher.resolve(physicalSinks(),output,maps(current.get("devices")));if(target!=null)selectedSink=target;
            updateRouteConfig(t("Удалить активный профиль","Delete active profile"),config->{GraphProfileLibrary.deleteProfile(config,id,replacementId,routeId,stopGraph.isSelected());selectedGraphRouteId=stopGraph.isSelected()?"":routeId;});
        });
    }

    protected void updateActiveProfile(String key, Object value) { updateConfig(config -> activeProfile(config).put(key, value)); }

    protected void updateUiSetting(String key, String value) { updateConfig(config -> map(config.get("ui")).put(key, value)); if (key.equals("language")) showPage(currentPage); }

    protected void updateConfig(Consumer<Map<String, Object>> change) {
        saveConfig(store.updateAsync(change));
    }

    protected void updateLimiter(String key, Object value) {
        updateConfig(config -> {
            Map<String, Object> dsp = map(config.get("dsp"));
            Map<String, Object> limiter = map(dsp.get("limiter"));
            dsp.put("limiter", limiter);
            limiter.put(key, value);
        });
    }

    protected void saveConfig(java.util.concurrent.CompletableFuture<Void> save) {
        save.whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (error != null) setNotice(t("Конфигурация не сохранена: ", "Configuration was not saved: ") + rootMessage(error), true);
            else { setNotice(t("Изменение сохранено в config.json.", "Change saved to config.json."), false); showPage(currentPage); }
        }));
    }

    protected void saveConfig(Map<String, Object> config) {
        saveConfig(store.saveAsync(config));
    }

    protected void styleDialog(Dialog<?> dialog) {
        if (getScene() == null) return;
        javafx.scene.control.DialogPane pane = dialog.getDialogPane();
        pane.getStylesheets().setAll(getScene().getStylesheets());
        pane.getStyleClass().add("app-shell");
        for (String theme : List.of("light-theme", "dark-theme"))
            if (getScene().getRoot().getStyleClass().contains(theme)) pane.getStyleClass().add(theme);
    }

    protected void setNotice(String text, boolean error) {
        notice.setText(text); notice.getStyleClass().removeAll("error-banner", "warning", "accent");
        notice.getStyleClass().add(error ? "error-banner" : "status-pill"); notice.setPadding(new Insets(9, 22, 9, 22));
        notice.setVisible(true); notice.setManaged(true); body.setBottom(notice);
    }

    protected VBox page(String subtitle) { VBox result = new VBox(18); result.getStyleClass().add("page-content"); result.getChildren().add(label(subtitle, "page-subtitle")); return result; }

    protected VBox card(String header) { VBox result = new VBox(12); result.getStyleClass().add("card"); Label title = label(header, "card-title"); result.getChildren().add(title); return result; }

    protected VBox metricCard(String name, String value, String detail) { VBox card = card(name); card.getChildren().addAll(label(value, "stat-value"), label(detail, "muted")); return card; }

    protected VBox errorCard(String header, String details) { VBox card = card(header); card.getChildren().add(label(details, "danger")); return card; }

    protected VBox routeNode(String header, String detail) {
        Label title = label(header, "card-title"); title.setMaxWidth(205);
        Label subtitle = label(detail, "muted"); subtitle.setMaxWidth(205);
        VBox node = new VBox(7, title, subtitle); node.setPrefWidth(220); node.setMaxWidth(220);
        node.getStyleClass().addAll("node-card", "route-node"); return node;
    }

    protected Label label(String text, String style) { Label label = new Label(text); label.setWrapText(true); if (style != null && !style.isBlank()) label.getStyleClass().add(style); return label; }

    protected Button actionButton(String text, Runnable action) { Button button = new Button(text); button.setMinHeight(36); button.setWrapText(true); button.setOnAction(event -> action.run()); button.setAccessibleText(text); return button; }

    protected TextField field(String value, String accessible) { TextField f = new TextField(value); f.setAccessibleText(accessible); f.setPrefColumnCount(8); return f; }

    protected javafx.scene.control.ListCell<AudioNode> nodeCell() { return new javafx.scene.control.ListCell<>() { @Override protected void updateItem(AudioNode item, boolean empty) { super.updateItem(item, empty); setText(empty || item == null ? null : nodeDisplayName(item)); } }; }

    protected void addWire(Pane pane, List<Line> wires, Node from, Node to) {
        Line line = new Line(); line.setStroke(javafx.scene.paint.Color.web("#268a98")); line.setStrokeWidth(2.0); line.setOpacity(0.78); line.setMouseTransparent(true);
        boolean sameColumn = Math.abs(from.getLayoutX() - to.getLayoutX()) < 1;
        line.setStartX(from.getLayoutX() + 220); line.setStartY(from.getLayoutY() + 48);
        line.setEndX(to.getLayoutX() + (sameColumn ? 220 : 0)); line.setEndY(to.getLayoutY() + 48);
        pane.getChildren().addFirst(line); wires.add(line);
    }

    protected void makeDraggable(Node node, Pane pane, List<Line> wires) {
        final double[] start = new double[2];
        node.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> { start[0] = event.getSceneX() - node.getLayoutX(); start[1] = event.getSceneY() - node.getLayoutY(); });
        node.addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> {
            Point2D point = pane.sceneToLocal(event.getSceneX(), event.getSceneY());
            node.setLayoutX(Math.max(0, Math.min(pane.getWidth() - node.getBoundsInParent().getWidth(), point.getX() - start[0])));
            node.setLayoutY(Math.max(0, Math.min(pane.getHeight() - 100, point.getY() - start[1])));
            // Lines are recomputed from nearby node geometry after each drag.
            for (Line line : wires) {
                if (Math.abs(line.getStartX() - (node.getLayoutX() + 220)) < 15) { line.setStartX(node.getLayoutX() + 220); line.setStartY(node.getLayoutY() + 48); }
                if (Math.abs(line.getStartX() - node.getLayoutX()) < 15) { line.setStartX(node.getLayoutX()); line.setStartY(node.getLayoutY() + 48); }
                if (Math.abs(line.getEndX() - (node.getLayoutX() + 220)) < 15) { line.setEndX(node.getLayoutX() + 220); line.setEndY(node.getLayoutY() + 48); }
                if (Math.abs(line.getEndX() - node.getLayoutX()) < 15) { line.setEndX(node.getLayoutX()); line.setEndY(node.getLayoutY() + 48); }
            }
        });
    }

    protected List<AudioNode> physicalSinks() {
        return snapshot.sinks().stream()
                .filter(node -> !node.name().startsWith("audiomatrix_"))
                .toList();
    }

    protected AudioNode configuredSink() {
        Map<String,Object> config=store.snapshot();String reference=string(activeProfile(config).get("default_output"),"system_default");
        return DeviceSelectorMatcher.resolve(physicalSinks(),reference,maps(config.get("devices")));
    }

    protected String outputReferenceName(Map<String,Object> config,String reference){
        if("system_default".equals(reference))return t("Системный выход","System default");
        for(Map<String,Object> device:maps(config.get("devices")))if(reference.equals(device.get("id"))){
            DeviceDiscovery.Resolution resolved=DeviceDiscovery.resolve(physicalSinks(),device);
            return DeviceDisplayNames.displayName(device,resolved.node()==null?"":resolved.node().description());
        }
        AudioNode live=physicalSinks().stream().filter(node->reference.equals(node.name())).findFirst().orElse(null);
        return DeviceDisplayNames.displayName(Map.of(),live==null?reference:live.description());
    }

    protected Map<String, Object> activeProfile() { return activeProfile(store.snapshot()); }

    protected String pageLabel(Page page) {
        return switch (page) {
            case HOME -> t("Обзор", "Overview"); case APPLICATIONS -> t("Приложения", "Applications");
            case DEVICES -> t("Устройства", "Devices"); case ROUTES -> t("Маршруты", "Routes"); case GRAPH -> t("Редактор графа", "Graph editor");
            case DSP -> t("DSP", "DSP"); case CALIBRATION -> t("Калибровка", "Calibration");
            case PROFILES -> t("Профили", "Profiles"); case SETTINGS -> t("Настройки и диагностика", "Settings & diagnostics");
        };
    }

    protected String polarityLabel(String polarity) {
        return switch (polarity) {
            case "invert_left" -> t("левый канал", "left channel");
            case "invert_right" -> t("правый канал", "right channel");
            default -> t("все каналы", "all channels");
        };
    }
}
