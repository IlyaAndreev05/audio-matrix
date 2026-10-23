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

abstract class AppShellUiPart03 extends AppShellUiPart02 {
    protected AppShellUiPart03(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    protected VBox devicesPage() {
        VBox page = page(t("Sink, source и их свойства сообщаются PipeWire.", "Sinks, sources, and properties reported by PipeWire."));
        Map<String,Object> config=store.snapshot();
        page.getChildren().add(GraphDeviceDiscovery.create(physicalSinks(), config, this::t,
                change -> updateRouteConfig(t("Изменить привязку устройства", "Change device binding"), change)));
        VBox outputs = card(t("Выходы · sinks", "Outputs · sinks"));
        if (physicalSinks().isEmpty()) outputs.getChildren().add(label(t("Система не сообщила выходы.", "No outputs were reported by the system."), "muted"));
        for (AudioNode node : filtered(physicalSinks(), search.getText())) {
            String status = node.id() == snapshot.defaultSinkId() ? t("Системный выход по умолчанию", "System default output") : node.state();
            outputs.getChildren().add(deviceRow(node, status, true));
        }
        VBox inputs = card(t("Входы · sources", "Inputs · sources"));
        if(snapshot.sources().isEmpty())inputs.getChildren().add(label(t("Система не сообщила входы.","No inputs were reported by the system."),"muted"));
        for (AudioNode node : filtered(snapshot.sources(), search.getText())) inputs.getChildren().add(deviceRow(node, node.state(), false));
        VBox identities=card(t("Сохранённые selectors", "Saved device selectors"));
        List<Map<String,Object>> savedDevices=maps(config.get("devices")).stream().filter(device->matchesSearch(String.valueOf(device.getOrDefault("name",device.get("id")))+" "+device.get("id")+" "+map(device.get("selector")),search.getText())).toList();
        if(savedDevices.isEmpty())identities.getChildren().add(label(t("Сохранённых устройств пока нет. Выберите или настройте выход, чтобы создать selector.","No saved devices yet. Select or configure an output to create a selector."),"muted"));
        for (Map<String, Object> device : savedDevices) {
            String id = string(device.get("id"), "");
            Map<String, Object> selector = map(device.get("selector"));
            AudioNode online = resolveDevice(config, device);
            int matches = matchingNodes(device).size();
            String state = matches > 1 ? t("Неоднозначный selector", "Ambiguous selector")
                    : online == null ? t("Не подключён", "Offline") : t("Подключён", "Online");
            String outputEq = string(device.get("filters_ref"), "");
            String detail = id + " · " + selector.getOrDefault("kind", "") + " = " + selector.getOrDefault("value", "") + " · " + state;
            if (!outputEq.isBlank()) detail += " · " + t("EQ: ", "EQ: ") + outputEq.replace("dsp.route_chains.", "");
            HBox row = new HBox(12); row.setAlignment(Pos.CENTER_LEFT); row.getStyleClass().add("node-card");
            VBox details = new VBox(4, label(string(device.getOrDefault("name", id), id), "card-title"), label(detail, online == null || matches > 1 ? "warning" : "muted"));
            Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
            Button edit = actionButton(t("Изменить selector", "Edit selector"), () -> editDeviceSelector(device, online));
            row.getChildren().addAll(details, spacer);
            if (Set.of("sink", "output").contains(string(device.get("role"), "")))
                row.getChildren().add(actionButton(t("EQ выхода", "Output EQ"), () -> editOutputEq(device, online)));
            row.getChildren().add(edit);
            identities.getChildren().add(row);
        }
        page.getChildren().addAll(outputs,inputs,identities);
        return page;
    }

    protected Node deviceRow(AudioNode node, String status, boolean output) {
        HBox row = new HBox(12); row.setAlignment(Pos.CENTER_LEFT); row.getStyleClass().add("node-card");
        Map<String,Object> config=store.snapshot();Map<String,Object> device=matchingDevice(config,node);
        String heading=DeviceDisplayNames.displayName(device,node.description());String detail=node.description()+" · "+node.name();
        String outputEq = output && device != null ? string(device.get("filters_ref"), "") : "";
        if (!outputEq.isBlank()) detail += " · " + t("EQ: ", "EQ: ") + outputEq.replace("dsp.route_chains.", "");
        VBox details = new VBox(4, label(heading, "card-title"), label(detail, "muted"));
        String channels = node.channels() + t(" каналов"," channels");
        String rates = node.sampleRates().isEmpty() ? t("частота неизвестна", "sample rate unavailable") : node.sampleRates().stream().map(String::valueOf).distinct().reduce((a,b) -> a + " / " + b).orElse("") + t(" Гц"," Hz");
        VBox facts = new VBox(4, label(status, "status-pill"), label(channels + " · " + rates, "muted"));
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        row.getChildren().addAll(details, spacer, facts);
        if (output) {
            Button choose = actionButton(t("Выбрать для управления", "Select for controls"), () -> {
                selectedSink = node;
                setNotice(t("Выход выбран для индивидуальной настройки.", "Output selected for individual controls."), false);
                showPage(Page.HOME);
            });
            row.getChildren().add(choose);
            row.getChildren().add(actionButton(t("EQ выхода", "Output EQ"), () -> editOutputEq(device, node)));
        } else row.getChildren().add(label(t("Уровень входа не измеряется", "Input level not measured"), "muted"));
        row.getChildren().add(actionButton(t("Имя и правило поиска","Name and selector"),()->editDeviceSelector(device,node)));
        return row;
    }

    protected VBox routesPage() {
        Map<String, Object> config = store.snapshot();
        Map<String, Object> profile = activeProfile(config);
        List<Map<String, Object>> buses = maps(config.get("buses"));
        List<Map<String, Object>> routes = maps(config.get("routes"));
        List<Map<String, Object>> applications = maps(config.get("applications"));
        Set<String> activeBusIds = new java.util.HashSet<>(stringList(profile.get("active_buses")));
        Set<String> activeRouteIds = new java.util.HashSet<>(stringList(profile.get("active_routes")));
        VBox page = page(t("Примените сохранённую схему, чтобы направить потоки через шины и маршруты PipeWire.", "Apply the saved graph to route streams through PipeWire buses and routes."));
        String routeStatus = routeManager.status();
        String routeStatusStyle = routeStatus.startsWith("Route apply failed") ? "danger" : routeManager.active() ? "accent" : "warning";
        Label banner = label(routeStatus, routeStatusStyle);
        VBox card = card(t("Приложения → шины → выходы", "Applications → buses → outputs"));
        List<AudioNode> streams = filtered(snapshot.streams(), search.getText());
        List<AudioNode> sinks = filtered(physicalSinks(), search.getText());
        int sinkCount = sinks.size() + (routes.stream().anyMatch(route -> "system_default".equals(route.get("to"))) ? 1 : 0);
        double canvasHeight = Math.max(430, 100 + Math.max(buses.size(), Math.max(streams.size(), sinkCount)) * 104);
        Pane canvas = new Pane(); canvas.setMinSize(900, canvasHeight); canvas.setPrefSize(1050, canvasHeight); canvas.getStyleClass().add("canvas");
        List<Line> wires = new ArrayList<>();
        List<Node> appCards = new ArrayList<>();
        List<String> appBusIds = new ArrayList<>();
        Map<String, Node> busCards = new LinkedHashMap<>();
        Map<String, Node> sinkCards = new LinkedHashMap<>();
        double leftY = 28;
        if (streams.isEmpty()) {
            Label app = label(t("Нет активных потоков", "No active streams"), "muted"); app.setLayoutX(24); app.setLayoutY(leftY); canvas.getChildren().add(app);
        } else for (AudioNode stream : streams) {
            Map<String, Object> rule = ApplicationRuleMatcher.find(applications, stream);
            String busId = rule == null ? "" : string(rule.get("bus"), "");
            Map<String, Object> bus = buses.stream().filter(candidate -> busId.equals(candidate.get("id"))).findFirst().orElse(null);
            String appName = stream.applicationName().isBlank() ? stream.description() : stream.applicationName();
            String detail = rule == null ? t("нет сохранённого правила", "no saved rule")
                    : t("шина: ", "bus: ") + (bus == null ? busId : string(bus.get("name"), busId))
                    + (rule.get("filters_ref") == null ? "" : " · " + t("EQ приложения: ", "App EQ: ")
                    + string(rule.get("filters_ref"), "").replace("dsp.route_chains.", ""));
            VBox node = routeNode(appName, detail);
            node.setLayoutX(26); node.setLayoutY(leftY); leftY += 104; canvas.getChildren().add(node);
            appCards.add(node); appBusIds.add(bus == null ? "" : busId);
        }
        double busY = 28;
        if (buses.isEmpty()) {
            Label noBuses = label(t("Шин пока нет — добавьте шину ниже.", "No buses yet — add one below."), "muted");
            noBuses.setLayoutX(350); noBuses.setLayoutY(28); canvas.getChildren().add(noBuses);
        }
        for (Map<String, Object> bus : buses) {
            String id = string(bus.get("id"), "");
            String detail = string(bus.get("channels"), "stereo") + " · " + volumeLabel(number(bus.get("volume"), 1))
                    + (Boolean.TRUE.equals(bus.get("mute")) ? " · Mute" : "")
                    + (activeBusIds.contains(id) ? " · " + t("активна", "active") : " · " + t("выключена", "off"));
            VBox node = routeNode(string(bus.get("name"), id), detail);
            node.setLayoutX(350); node.setLayoutY(busY); busY += 104; canvas.getChildren().add(node); busCards.put(id, node);
        }
        for (int i = 0; i < appCards.size(); i++) {
            Node bus = busCards.get(appBusIds.get(i));
            if (bus != null) addWire(canvas, wires, appCards.get(i), bus);
        }
        double sinkY = 28;
        boolean needsSystemDefault = routes.stream().anyMatch(route -> "system_default".equals(route.get("to")) && !Boolean.FALSE.equals(route.get("enabled")));
        if (needsSystemDefault) {
            VBox node = routeNode(t("Системный выход", "System default"), t("текущий выход PipeWire", "current PipeWire default"));
            node.setLayoutX(680); node.setLayoutY(sinkY); sinkY += 104; canvas.getChildren().add(node); sinkCards.put("system_default", node);
        }
        for (AudioNode sink : sinks) {
            VBox node = routeNode(nodeDisplayName(sink), sink.state()); node.setLayoutX(680); node.setLayoutY(sinkY); sinkY += 104; canvas.getChildren().add(node);
            Map<String, Object> savedDevice = matchingDevice(config, sink);
            sinkCards.put(savedDevice == null ? "" : string(savedDevice.get("id"), ""), node);
        }
        for (Map<String, Object> route : routes) {
            if (Boolean.FALSE.equals(route.get("enabled")) || !activeRouteIds.contains(string(route.get("id"), ""))) continue;
            Node from = busCards.get(string(route.get("from"), ""));
            String toId = string(route.get("to"), "");
            Node to = busCards.get(toId);
            if (to == null) to = sinkCards.get(toId);
            if (to == null) {
                AudioNode target = DeviceSelectorMatcher.resolve(sinks, toId, maps(config.get("devices")));
                if (target != null) {
                    Map<String, Object> targetDevice = matchingDevice(config, target);
                    to = sinkCards.get(targetDevice == null ? "" : string(targetDevice.get("id"), ""));
                }
            }
            if (from != null && to != null) addWire(canvas, wires, from, to);
        }
        appCards.forEach(node -> makeDraggable(node, canvas, wires));
        busCards.values().forEach(node -> makeDraggable(node, canvas, wires));
        sinkCards.values().forEach(node -> makeDraggable(node, canvas, wires));
        card.getChildren().addAll(banner, canvas);
        HBox actions = new HBox(10);
        Button apply = actionButton(t("Применить к PipeWire", "Apply to PipeWire"), this::applyRoutes);
        Button clear = actionButton(t("Сбросить маршруты", "Clear routes"), this::clearRoutes);
        clear.setDisable(!routeManager.active());
        actions.getChildren().addAll(apply, clear);
        card.getChildren().add(actions);
        page.getChildren().add(card);
        VBox busesCard = card(t("Шины", "Buses"));
        for (Map<String, Object> bus : buses) busesCard.getChildren().add(busRow(bus, activeBusIds.contains(string(bus.get("id"), ""))));
        busesCard.getChildren().add(new HBox(10, actionButton(t("Добавить шину", "Add bus"), () -> editBus(null))));
        page.getChildren().add(busesCard);
        VBox routesCard = card(t("Маршруты", "Routes"));
        if (routes.isEmpty()) routesCard.getChildren().add(label(t("Маршрутов пока нет.", "No routes yet."), "muted"));
        for (Map<String, Object> route : routes) routesCard.getChildren().add(routeRow(route, activeRouteIds.contains(string(route.get("id"), ""))));
        routesCard.getChildren().add(new HBox(10, actionButton(t("Добавить маршрут", "Add route"), () -> editRoute(null))));
        page.getChildren().add(routesCard);
        page.getChildren().add(routeHistoryCard());
        return page;
    }
}
