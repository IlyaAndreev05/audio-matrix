package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.ThemeManager;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;
import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

abstract class AppShellUiPart30 extends AppShellUiPart29 {
    protected AppShellUiPart30(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    @Override
    protected boolean graphNodePinned(String routeId, String nodeId) {
        return QuickSettingsConfig.pins(store.snapshot()).stream()
                .anyMatch(pin -> routeId.equals(pin.get("route_id")) && nodeId.equals(pin.get("node_id")));
    }

    @Override
    protected void toggleGraphNodePin(String routeId, GraphNode node, boolean pinned) {
        if (pinned) {
            updateRouteConfig(t("Открепить ноду от GNOME", "Unpin node from GNOME"),
                    config -> QuickSettingsConfig.removeNode(config, routeId, node.id()));
            return;
        }
        Map<String, Object> config = store.snapshot();
        List<Map<String, Object>> groups = QuickSettingsConfig.groups(config);
        String suggested = switch(node.type()){
            case "source" -> "Источники";
            case "output" -> "Выходные устройства";
            case "mixer" -> "Коммутация и управление";
            default -> "Обработка звука";
        };
        String order = Integer.toString(QuickSettingsConfig.pins(config).stream()
                .filter(pin -> routeId.equals(pin.get("route_id"))).mapToInt(pin -> number(pin.get("order"), -1))
                .max().orElse(-1) + 1);
        String defaultGroupId=switch(node.type()){
            case "source" -> "sources";
            case "output" -> "outputs";
            case "mixer" -> "routing";
            default -> "effects";
        };
        Map<String, Object> defaultGroup = groups.stream()
                .filter(group -> defaultGroupId.equals(group.get("id")))
                .findFirst().orElse(null);
        TextField groupName = new TextField(defaultGroup == null ? suggested : String.valueOf(defaultGroup.get("name")));
        TextField orderField = new TextField(order);
        ComboBox<String> view = new ComboBox<>();
        view.getItems().setAll("DEFAULT", "COMPACT", "EXPANDED");
        view.setValue("EXPANDED");
        Dialog<Map<String, Object>> dialog = new Dialog<>();
        if (getScene() != null) dialog.initOwner(getScene().getWindow());
        dialog.setTitle(t("Закрепить ноду", "Pin graph node"));
        dialog.setHeaderText(t("Нода появится в выбранной группе микшера GNOME.", "The node will appear in the selected GNOME mixer group."));
        dialog.getDialogPane().setContent(new VBox(8,
                label(t("Группа", "Group"), "muted"), groupName,
                label(t("Порядок (меньше — выше)", "Order (lower appears first)"), "muted"), orderField,
                label(t("Вид карточки", "Card view"), "muted"), view));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Button save = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        Runnable validate = () -> save.setDisable(groupName.getText().isBlank() || !validOrder(orderField.getText()));
        groupName.textProperty().addListener((obs, old, value) -> validate.run());
        orderField.textProperty().addListener((obs, old, value) -> validate.run());
        validate.run();
        dialog.setResultConverter(button -> button == ButtonType.OK
                ? Map.of("group", groupName.getText().trim(), "order", Integer.parseInt(orderField.getText()), "view", view.getValue())
                : null);
        dialog.showAndWait().ifPresent(settings -> updateRouteConfig(t("Закрепить ноду в GNOME", "Pin node in GNOME"),
                candidate -> {
                    Map<String, Object> group = QuickSettingsConfig.findOrCreateGroup(candidate, String.valueOf(settings.get("group")));
                    QuickSettingsConfig.pinNode(candidate, routeId, node.id(), String.valueOf(group.get("id")),
                            number(settings.get("order"), 0), String.valueOf(settings.get("view")));
                }));
    }

    protected CompletableFuture<Void> setGnomeQuickPinView(String value) {
        String[] fields = value.split("\\t", 2);
        if (fields.length != 2 || !List.of("COMPACT", "EXPANDED").contains(fields[1]))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Pinned node view is malformed"));
        return updateRouteConfigCommand(t("Изменён вид закреплённой ноды", "Pinned node view changed"), config -> {
            Map<String, Object> pin = QuickSettingsConfig.pins(config).stream()
                    .filter(item -> fields[0].equals(item.get("id"))).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Pinned node no longer exists"));
            if (!GraphUiRoutes.available(config, String.valueOf(pin.get("route_id"))))
                throw new IllegalArgumentException("Pinned node is not on the active route");
            pin.put("view", fields[1]);
        });
    }

    private boolean validOrder(String value) {
        try { return Integer.parseInt(value) >= 0; }
        catch (NumberFormatException invalid) { return false; }
    }

    private int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }
}
