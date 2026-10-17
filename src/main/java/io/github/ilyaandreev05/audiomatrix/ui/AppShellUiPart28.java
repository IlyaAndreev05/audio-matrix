package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.ThemeManager;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;
import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

abstract class AppShellUiPart28 extends AppShellUiPart27 {
    protected AppShellUiPart28(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    @Override
    protected VBox settingsPage() {
        VBox page = super.settingsPage();
        page.getChildren().add(quickSettingsGroupCard());
        return page;
    }

    private VBox quickSettingsGroupCard() {
        VBox card = card(t("Группы меню громкости GNOME", "GNOME volume menu groups"));
        List<GroupEditor> editors = new ArrayList<>();
        List<Map<String, Object>> groups = QuickSettingsConfig.groups(store.snapshot());
        for (Map<String, Object> group : groups) {
            String id = String.valueOf(group.get("id"));
            TextField name = new TextField(String.valueOf(group.get("name")));
            Spinner<Integer> order = new Spinner<>(0, 99, ((Number) group.getOrDefault("order", 0)).intValue());
            order.setEditable(true);
            CheckBox collapsed = new CheckBox(t("Сворачивать при открытии", "Start collapsed"));
            collapsed.setSelected(Boolean.TRUE.equals(group.get("collapsed")));
            HBox row = new HBox(9, name, order, collapsed);
            row.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(name, Priority.ALWAYS);
            card.getChildren().add(row);
            editors.add(new GroupEditor(id, name, order, collapsed));
        }
        card.getChildren().addAll(
                actionButton(t("Сохранить порядок и вид", "Save group order and display"), () -> saveGroups(editors)),
                actionButton(t("Добавить группу", "Add group"), this::createQuickGroup));
        return card;
    }

    private void saveGroups(List<GroupEditor> editors) {
        updateRouteConfig(t("Изменить группы GNOME", "Edit GNOME groups"), config -> {
            List<Map<String, Object>> groups = QuickSettingsConfig.groups(config);
            for (GroupEditor editor : editors) {
                Map<String, Object> group = groups.stream().filter(item -> editor.id().equals(item.get("id")))
                        .findFirst().orElseThrow();
                String name = editor.name().getText().trim();
                if (name.isBlank()) throw new IllegalArgumentException("Group name must not be blank");
                group.put("name", name);
                group.put("order", editor.order().getValue());
                group.put("collapsed", editor.collapsed().isSelected());
                for (Map<String, Object> control : maps(config.get("quick_controls"))) {
                    if (editor.id().equals(control.get("group_id"))) control.put("section", name);
                }
            }
        });
    }

    private void createQuickGroup() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle(t("Добавить группу", "Add group"));
        dialog.setHeaderText(t("Имя группы в меню громкости", "Name the volume menu group"));
        styleDialog(dialog);
        dialog.showAndWait().map(String::trim).filter(name -> !name.isBlank()).ifPresent(name ->
                updateRouteConfig(t("Добавить группу GNOME", "Add GNOME group"),
                        config -> QuickSettingsConfig.findOrCreateGroup(config, name)));
    }

    private record GroupEditor(String id, TextField name, Spinner<Integer> order, CheckBox collapsed) {}
}
