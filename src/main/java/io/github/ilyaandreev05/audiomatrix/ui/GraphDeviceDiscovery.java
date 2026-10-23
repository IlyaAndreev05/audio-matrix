package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceDiscovery;
import io.github.ilyaandreev05.audiomatrix.config.DeviceDisplayNames;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.util.*;
import java.util.function.*;

/** Explicit discovery decisions; discovery alone never selects an audio branch. */
final class GraphDeviceDiscovery {
    private GraphDeviceDiscovery() {}
    static VBox create(List<AudioNode> sinks, Map<String, Object> config,
                       BiFunction<String, String, String> t, Consumer<Consumer<Map<String, Object>>> save) {
        VBox box = new VBox(10);
        box.getStyleClass().add("card");
        box.getChildren().add(new Label(t.apply("Обнаружение и привязка устройств", "Discovery and device binding")));
        box.getChildren().add(new Label(t.apply("Новый выход молчит до явной привязки и включения его ветки MultiSwitch.",
                "New outputs remain silent until explicitly bound and selected in MultiSwitch.")));
        List<Map<String, Object>> devices = maps(config.get("devices"));
        for (DeviceDiscovery.Entry entry : DeviceDiscovery.catalog(sinks, devices)) {
            AudioNode node = entry.node();
            HBox row = new HBox(8);
            String name = DeviceDisplayNames.displayName(config,entry.slotId(),node==null?"":node.description());
            if (entry.identity() != null) row.getChildren().add(new Label(
                    entry.identity().channels() + " ch · " + entry.identity().cardProfile() + " · " + entry.identity().port()));
            if (entry.state() == DeviceDiscovery.State.IGNORED) {
                Button restore = new Button(t.apply("Вернуть в обнаружение", "Restore discovery"));
                restore.setOnAction(event -> save.accept(candidate -> {
                    List<Map<String, Object>> updated = new ArrayList<>(maps(candidate.get("devices")));
                    updated.removeIf(device -> entry.slotId().equals(device.get("id")) && Boolean.TRUE.equals(device.get("ignored")));
                    candidate.put("devices", updated);
                }));
                row.getChildren().add(restore);
            }
            row.getChildren().add(new Label(name + " · " + stateLabel(entry.state(),t)
                    + (entry.conflict().isBlank()?"":" · "+entry.conflict())));
            if (node != null) {
                boolean identifiable = entry.identity() != null && entry.identity().reliable();
                Button bind = new Button(t.apply("Привязать к слоту…", "Bind to slot…"));
                bind.setDisable(!identifiable);
                if (!identifiable) bind.setTooltip(new Tooltip(t.apply(
                        "Недостаточно надёжная идентификация: привязка запрещена", "Identity confidence is too low to bind")));
                bind.setOnAction(event -> {
                    Set<String> slots = new LinkedHashSet<>();
                    devices.stream().filter(device -> "device_slot".equals(device.get("kind")))
                            .forEach(device -> slots.add(String.valueOf(device.get("id"))));
                    slots.addAll(logicalSlotIds(config));
                    ComboBox<String> target = new ComboBox<>();
                    target.setEditable(true); target.getItems().addAll(slots);
                    target.setValue(entry.slotId() == null || entry.slotId().isBlank() ? null : entry.slotId());
                    target.setPromptText(t.apply("Выбери логический слот графа", "Choose a graph logical slot"));
                    Dialog<String> dialog = new Dialog<>();
                    dialog.setTitle(t.apply("Привязать устройство", "Bind device"));
                    dialog.setHeaderText(t.apply("ID логического слота (выбор Switch не меняется)", "Logical slot ID (Switch selection is unchanged)"));
                    dialog.getDialogPane().setContent(target);
                    dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
                    dialog.setResultConverter(button -> button == ButtonType.OK ? target.getEditor().getText().trim() : null);
                    dialog.showAndWait().filter(id -> !id.isBlank()).ifPresent(id -> save.accept(candidate -> DeviceDiscovery.bind(candidate, id, node)));
                });
                Button ignore = new Button(t.apply("Игнорировать", "Ignore"));
                ignore.setDisable(entry.state() != DeviceDiscovery.State.NEW || !identifiable);
                ignore.setOnAction(event -> save.accept(candidate -> DeviceDiscovery.ignore(candidate, node)));
                row.getChildren().addAll(bind, ignore);
            }
            box.getChildren().add(row);
        }
        return box;
    }
    static String stateLabel(DeviceDiscovery.State state,BiFunction<String,String,String> t) {
        return switch(state) {
            case NEW -> t.apply("Новое устройство","New device");
            case ONLINE -> t.apply("Подключено","Connected");
            case OFFLINE -> t.apply("Не подключено","Not connected");
            case AMBIGUOUS -> t.apply("Неоднозначная идентификация","Ambiguous identity");
            case CONFLICT -> t.apply("Конфликт привязки","Binding conflict");
            case IGNORED -> t.apply("Игнорируется","Ignored");
        };
    }

    private static List<String> logicalSlotIds(Map<String, Object> config) {
        List<Map<String, Object>> encoded = new ArrayList<>();
        if (config.get("common_vibro_graph") instanceof Map<?, ?> common) {
            Map<String, Object> copy = new LinkedHashMap<>();
            common.forEach((key, value) -> copy.put(String.valueOf(key), value));
            encoded.add(copy);
        }
        if (config.get("route_library") instanceof List<?> routes)
            for (Object route : routes) if (route instanceof Map<?, ?> raw) {
                Map<String, Object> copy = new LinkedHashMap<>();
                raw.forEach((key, value) -> copy.put(String.valueOf(key), value));
                encoded.add(copy);
            }
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> route : encoded) {
            try {
                AudioGraph graph = GraphCodec.fromMap(route);
                graph.nodes().stream().filter(DeviceSlotNode.class::isInstance).map(DeviceSlotNode.class::cast)
                        .map(DeviceSlotNode::id).filter(id -> !ids.contains(id)).forEach(ids::add);
            } catch (RuntimeException invalid) { /* A broken unrelated route must not block discovery. */ }
        }
        return ids;
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> items ? (List<Map<String, Object>>) (List<?>) items : List.of();
    }
}
