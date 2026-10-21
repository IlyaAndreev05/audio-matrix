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
import javafx.scene.input.*;
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
import java.util.function.*;

abstract class AppShellUiPart04GraphInspector extends AppShellUiPart04GraphPage {
    protected AppShellUiPart04GraphInspector(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }
    protected VBox graphDiagnosticsCard(AudioGraph graph,Map<String,Object> config){
        VBox diagnosticsCard=card(t("Диагностика выбранного узла","Selected node diagnostics"));
        GraphNode selected=graph.nodes().stream().filter(node->selectedGraphNodeId.equals(node.id())).findFirst().orElse(null);
        diagnosticsCard.getChildren().add(label(t("Логический узел","Logical node"),"card-title"));
        diagnosticsCard.getChildren().add(label(selected==null?t("Узел не выбран или отсутствует в графе.","No node is selected or the node is absent from the graph.")
                :selected.name()+" · "+GraphNodeLabels.typeName(selected,this::t)+" · "+selected.id(),selected==null?"warning":"muted"));
        diagnosticsCard.getChildren().add(label(t("Стабильная идентичность / runtime binding","Stable identity / runtime binding"),"card-title"));
        diagnosticsCard.getChildren().add(label(selected==null?t("Недоступно: логический узел отсутствует.","Unavailable: logical node is absent.")
                :graphBindingDiagnostic(selected,config),"muted"));

        boolean selectedInConfig=GraphUiRoutes.common(graph.id())||graph.id().equals(config.get("active_route"));
        diagnosticsCard.getChildren().add(label(t("Выбранный маршрут в конфигурации","Configured selected route"),"card-title"));
        diagnosticsCard.getChildren().add(label(graph.name()+" · "+graph.id()
                +" · "+(selectedInConfig?t("выбран в конфигурации","selected in configuration"):t("сохранён, но не выбран","saved but not selected")),"muted"));
        diagnosticsCard.getChildren().add(label(t("Фактически наблюдаемые связи по stable owner ID","Actually observed links by stable owner ID"),"card-title"));
        List<PipeWireRouteTypes.BranchDiagnostic> routeBranches=routeBranchDiagnostics(graph);
        if(routeBranches.isEmpty())diagnosticsCard.getChildren().add(label(t("Недоступно: runtime не опубликовал диагностику для ID этого маршрута или его связей.",
                "Unavailable: runtime has not published diagnostics for this route ID or its edge IDs."),"warning"));
        for(PipeWireRouteTypes.BranchDiagnostic branch:routeBranches)
            diagnosticsCard.getChildren().add(branchDiagnosticRow(branch));
        List<PipeWireRouteTypes.BranchDiagnostic> selectedBranches=selected==null?List.of():nodeBranchDiagnostics(graph,selected);
        if(selected!=null){
            diagnosticsCard.getChildren().add(label(t("Связи выбранного узла","Selected node links"),"card-title"));
            if(selectedBranches.isEmpty())diagnosticsCard.getChildren().add(label(t("Для стабильного ID этого узла и его рёбер нет runtime отчёта; linked status недоступен.",
                    "No runtime report exists for this node’s stable ID or its edges; linked status is unavailable."),"warning"));
            selectedBranches.forEach(branch->diagnosticsCard.getChildren().add(branchDiagnosticRow(branch)));
        }

        diagnosticsCard.getChildren().add(label(t("Измерение сигнала","Signal measurement"),"card-title"));
        diagnosticsCard.getChildren().add(label(t("Не измерено: для этого узла отдельный meter не подключён. Доступность, binding и наличие link не считаются активностью сигнала.",
                "Not measured: no node-specific meter is attached. Availability, binding, and link presence are not signal activity."),"muted"));
        diagnosticsCard.getChildren().add(label(t("Причина сбоя backend","Backend failure cause"),"card-title"));
        List<String> branchErrors=selectedBranches.stream().map(PipeWireRouteTypes.BranchDiagnostic::error)
                .filter(error->error!=null&&!error.isBlank()).toList();
        if(branchErrors.isEmpty())diagnosticsCard.getChildren().add(label(backend.backendMessage(),"muted"));
        else branchErrors.forEach(error->diagnosticsCard.getChildren().add(label(error,"danger")));
        var report=routeManager.lastReport();
        if(report!=null&&!report.warnings().isEmpty()){
            diagnosticsCard.getChildren().add(label(t("Предупреждения route manager (глобальные):","Route-manager warnings (global):"),"warning"));
            report.warnings().forEach(warning->diagnosticsCard.getChildren().add(label(warning,"danger")));
        }

        return diagnosticsCard;
    }
    private List<PipeWireRouteTypes.BranchDiagnostic> routeBranchDiagnostics(AudioGraph graph){
        Set<String> owners=new LinkedHashSet<>();owners.add(graph.id());
        graph.connections().forEach(edge->owners.add(edge.id()));
        for(GraphNode node:graph.nodes()){
            owners.add(node.id());
            if(node instanceof SourceNode source)owners.add(source.selector());
            if(node instanceof DeviceSlotNode slot)stableDeviceFingerprint(slot.deviceId()).ifPresent(owners::add);
            if(node instanceof OutputNode output)stableDeviceFingerprint(output.deviceId()).ifPresent(owners::add);
        }
        return routeManager.branchDiagnostics().values().stream().filter(branch->owners.contains(branch.ownerId())).toList();
    }

    private List<PipeWireRouteTypes.BranchDiagnostic> nodeBranchDiagnostics(AudioGraph graph,GraphNode node){
        Set<String> owners=new LinkedHashSet<>();owners.add(node.id());
        graph.connections().stream().filter(edge->node.id().equals(edge.fromNode())||node.id().equals(edge.toNode()))
                .map(GraphConnection::id).forEach(owners::add);
        if(node instanceof SourceNode source)owners.add(source.selector());
        if(node instanceof DeviceSlotNode slot)stableDeviceFingerprint(slot.deviceId()).ifPresent(owners::add);
        if(node instanceof OutputNode output)stableDeviceFingerprint(output.deviceId()).ifPresent(owners::add);
        return routeManager.branchDiagnostics().values().stream().filter(branch->owners.contains(branch.ownerId())).toList();
    }

    private java.util.Optional<String> stableDeviceFingerprint(String deviceId){
        if(deviceId==null||deviceId.isBlank())return java.util.Optional.empty();
        Map<String,Object> device=maps(store.snapshot().get("devices")).stream()
                .filter(candidate->deviceId.equals(candidate.get("id"))).findFirst().orElse(null);
        if(device==null)return java.util.Optional.empty();
        Map<String,Object> identity=map(device.get("identity"));
        String fingerprint=string(identity.get("fingerprint"),"");
        return fingerprint.isBlank()?java.util.Optional.empty():java.util.Optional.of(fingerprint);
    }

    private Node branchDiagnosticRow(PipeWireRouteTypes.BranchDiagnostic branch){
        long desired=branch.desiredLinks().size();
        long matched=branch.desiredLinks().stream().filter(branch.observedLinks()::contains).count();
        VBox row=new VBox(3);row.getStyleClass().add("node-card");
        row.getChildren().add(label(branch.ownerId()+" · "+branch.kind()
                +" · "+(branch.selected()?t("selected","selected"):t("not selected","not selected"))
                +" · "+(branch.available()?t("available","available"):t("unavailable","unavailable"))
                +" · "+(branch.processAlive()?t("process alive","process alive"):t("process stopped","process stopped")),"muted"));
        row.getChildren().add(label(t("Наблюдаемые связи ","Observed links ")+matched+"/"+desired
                +" · "+branch.observedLinks().size()+t(" owned links seen"," owned links seen"),"muted"));
        if(branch.error()!=null&&!branch.error().isBlank())row.getChildren().add(label(branch.error(),"danger"));
        return row;
    }
    protected boolean isSavedGraphNode(String routeId,String nodeId){
        try{
            return maps(GraphUiRoutes.route(store.snapshot(),routeId).get("nodes")).stream()
                    .anyMatch(node->nodeId.equals(node.get("id")));
        }catch(RuntimeException missing){return false;}
    }

    protected DynamicChildAddress dynamicChildAddress(String routeId,String childNodeId){
        DynamicInstanceRegistry.ReconcileResult runtime=dynamicGraphState(routeId);
        if(runtime==null)return null;
        return runtime.instances().values().stream()
                .filter(instance->instance.nodeIds().containsValue(childNodeId))
                .findFirst().map(instance->new DynamicChildAddress(instance.generatorId(),instance.instanceKey(),instance)).orElse(null);
    }
    protected boolean browserTabInstance(String routeId,DynamicChildAddress address){
        DynamicInstanceRegistry.ReconcileResult runtime=dynamicGraphState(routeId);
        if(runtime==null)return false;
        return runtime.configuredGraph().nodes().stream().filter(node->address.generatorId().equals(node.id()))
                .filter(DynamicGeneratorNode.class::isInstance).map(DynamicGeneratorNode.class::cast)
                .anyMatch(generator->"browser_tabs".equals(generator.registry()));
    }

    protected boolean independentTabVolumeAvailable(DynamicChildAddress address){
        Map<String,Object> binding=address.instance().runtimeBinding();
        String captureKey=string(binding.get("captureStreamKey"),"");
        return !captureKey.isBlank()&&"captured".equals(binding.get("volumeCapability"))
                &&("audio-matrix-chrome-"+captureKey).equals(binding.get("nodeName"))
                &&!string(binding.get("applicationRuleId"),"").isBlank();
    }

    private String graphBindingDiagnostic(GraphNode node,Map<String,Object> config){
        if(node instanceof DeviceSlotNode slot)return deviceBindingDiagnostic(slot.deviceId(),config);
        if(node instanceof OutputNode output)return deviceBindingDiagnostic(output.deviceId(),config);
        if(node instanceof DynamicGeneratorNode generator)
            return t("Генератор · selector ","Generator · selector ")+generator.selector()
                    +t("; конкретные экземпляры принадлежат runtime registry.","; concrete instances are owned by the runtime registry.");
        DynamicChildAddress runtimeAddress=dynamicChildAddress(selectedGraphRouteId,node.id());
        if(runtimeAddress!=null){
            Map<String,Object> binding=runtimeAddress.instance().runtimeBinding();
            if(browserTabInstance(selectedGraphRouteId,runtimeAddress)){
                String captureKey=string(binding.get("captureStreamKey"),"");
                String nodeName=string(binding.get("nodeName"),"");
                boolean exactBinding=!captureKey.isBlank()&&("audio-matrix-chrome-"+captureKey).equals(nodeName);
                String base=t("Chrome tab runtime binding · instance ","Chrome tab runtime binding · instance ")
                        +runtimeAddress.instanceKey()+" · "+(exactBinding?t("точная capture binding","exact capture binding"):t("capture binding отсутствует","capture binding unavailable"));
                return independentTabVolumeAvailable(runtimeAddress)?base+t(" · independentTabVolume доступен"," · independentTabVolume available")
                        :base+t(" · управление громкостью вкладки недоступно"," · tab volume control unavailable");
            }
            String rule=string(binding.get("applicationRuleId"),"");
            return t("Runtime instance ","Runtime instance ")+runtimeAddress.instanceKey()
                    +t(" · generator "," · generator ")+runtimeAddress.generatorId()
                    +(rule.isBlank()?"":" · "+t("application rule ","application rule ")+rule)
                    +" · "+(runtimeAddress.instance().available()?t("binding доступен","binding available"):t("binding недоступен","binding unavailable"));
        }
        if(node instanceof SourceNode source){
            String selector=source.selector();
            if(selector.startsWith("application:")){
                String applicationId=selector.substring("application:".length());
                long matches=snapshot.streams().stream().filter(stream->applicationId.equals(stream.applicationId())).count();
                return t("Сохранённый selector ","Saved selector ")+selector+" · "+matches+t(" подходящих потоков в текущем snapshot."," matching streams in the current snapshot.");
            }
            return t("Сохранённый selector ","Saved selector ")+selector+t("; отдельная аппаратная identity не используется.","; no hardware identity is used.");
        }
        if(node instanceof GainNode gain&&!gain.endpointNodeId().isBlank())
            return t("Привязан к узлу графа ","Bound to graph node ")+gain.endpointNodeId();
        return t("Обработка графа; отдельная hardware identity для этого узла не требуется.",
                "Graph processor; a separate hardware identity is not required for this node.");
    }

    private String deviceBindingDiagnostic(String deviceId,Map<String,Object> config){
        if(deviceId==null||deviceId.isBlank())
            return t("Устройство не привязано; это не подтверждает наличие сигнала.","Device is unbound; this does not indicate signal activity.");
        Map<String,Object> device=maps(config.get("devices")).stream()
                .filter(candidate->deviceId.equals(candidate.get("id"))).findFirst().orElse(null);
        if(device==null)return t("Сохранённая ссылка устройства: ","Saved device reference: ")+deviceId
                +t("; каталог не содержит стабильную identity.","; the catalogue has no stable identity.");
        DeviceDiscovery.Resolution resolution=DeviceDiscovery.resolve(physicalSinks(),device);
        String stable=string(device.get("name"),deviceId);
        String result=stable+" · "+resolution.state();
        if(!resolution.message().isBlank())result+=" · "+resolution.message();
        if(resolution.node()!=null)result+=" · "+resolution.node().description();
        return result;
    }
    protected record DynamicChildAddress(String generatorId,String instanceKey,DynamicInstanceRegistry.RuntimeInstance instance) {}
}
