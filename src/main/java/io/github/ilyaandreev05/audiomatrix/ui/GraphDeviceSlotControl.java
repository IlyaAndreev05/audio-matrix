package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.*;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import java.util.*;
import java.util.function.*;

final class GraphDeviceSlotControl {
    private GraphDeviceSlotControl() {}
    static VBox create(DeviceSlotNode slot, List<AudioNode> sinks, List<Map<String, Object>> devices,
                       BiFunction<String, String, String> t, Consumer<String> assign) {
        VBox box = new VBox(5);
        Map<String, Object> bound = devices.stream().filter(device -> slot.deviceId().equals(device.get("id"))).findFirst().orElse(null);
        String state = bound == null ? "UNBOUND" : DeviceDiscovery.catalog(sinks, devices).stream()
                .filter(entry -> slot.deviceId().equals(entry.slotId())).map(entry -> entry.state() + " · " + entry.conflict())
                .findFirst().orElse("OFFLINE");
        box.getChildren().add(new Label(state + " · " + slot.deviceId()));
        ComboBox<Map<String, Object>> picker = new ComboBox<>();
        picker.getItems().addAll(devices.stream().filter(device -> "device_slot".equals(device.get("kind"))).toList());
        picker.setConverter(new StringConverter<>() {
            @Override public String toString(Map<String, Object> device) { return device == null ? "" : device.getOrDefault("name", device.get("id")) + " · " + device.get("id"); }
            @Override public Map<String, Object> fromString(String value) { return null; }
        });
        picker.setPromptText(t.apply("Выберите явную привязку", "Choose explicit binding"));
        if (bound != null) picker.setValue(bound);
        picker.setOnAction(event -> { if (picker.getValue() != null) assign.accept(String.valueOf(picker.getValue().get("id"))); });
        Button clear = new Button(t.apply("Снять привязку (тишина)", "Unbind (silent)"));
        clear.setOnAction(event -> assign.accept(""));
        box.getChildren().addAll(picker, clear);
        if (bound == null) box.getChildren().add(new javafx.scene.control.Label(
                t.apply("Устройство привязывается явно в разделе «Устройства».",
                        "Bind a device explicitly in the Devices section.")));
        return box;
    }
}
