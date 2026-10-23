package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.ThemeManager;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;
import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import io.github.ilyaandreev05.audiomatrix.gnome.GnomeControlBridge;
import io.github.ilyaandreev05.audiomatrix.graph.*;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

abstract class AppShellUiPart30 extends AppShellUiPart29 {
    protected AppShellUiPart30(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    @Override
    protected boolean graphNodePinned(String routeId, String nodeId) {
        Map<String,Object> pin=QuickSettingsConfig.findPin(store.snapshot(),routeId,nodeId,"","");
        return pin!=null&&!Boolean.FALSE.equals(pin.get("pinned"));
    }

    @Override
    protected void toggleGraphNodePin(String routeId, GraphNode node, boolean pinned) {
        if (pinned) {
            updateRouteConfig(t("Открепить узел от GNOME", "Unpin node from GNOME"),
                    config -> QuickSettingsConfig.unpinNode(config, routeId, node.id(), ""));
            return;
        }
        Dialog<Void> dialog=new Dialog<>();
        if(getScene()!=null)dialog.initOwner(getScene().getWindow());
        dialog.setTitle(t("Закрепление в GNOME","GNOME pin"));
        dialog.setHeaderText(t("Отображение узла не меняет его работу в аудиографе.",
                "Node presentation does not change its audio graph behavior."));
        dialog.getDialogPane().setContent(gnomePanelProperties(new GnomePanelItem("NODE",node.name(),
                routeId,node.id(),"","",null,node,null,null),store.snapshot()));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setPrefWidth(560);
        styleDialog(dialog);
        dialog.showAndWait();
    }

    protected CompletableFuture<Void> setGnomeQuickPinView(String value) {
        String[] fields = value.split("\\t", 2);
        if (fields.length != 2 || !List.of("COMPACT", "EXPANDED").contains(fields[1]))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Pinned node view is malformed"));
        var current=store.snapshot();
        DynamicInstanceRegistry.ReconcileResult runtime=dynamicGraphState(String.valueOf(current.getOrDefault("active_route","")));
        List<Map<String,Object>> projected=fields[0].startsWith("generated-pin:")
                ?maps(GnomeControlBridge.stateProjection(snapshot,current,current.revision(),browserAdapters,runtime).get("quickPins")):List.of();
        return updateRouteConfigCommand(t("Изменён вид закреплённой ноды", "Pinned node view changed"), config -> {
            Map<String,Object> pin=pinForDisclosure(config,fields[0],runtime,projected);
            if (!GraphUiRoutes.available(config, String.valueOf(pin.get("route_id"))))
                throw new IllegalArgumentException("Pinned node is not on the active route");
            pin.put("expanded", "EXPANDED".equals(fields[1]));
            pin.put("view",fields[1]);
        });
    }

    static Map<String,Object> pinForDisclosure(Map<String,Object> config,String pinId,
            DynamicInstanceRegistry.ReconcileResult runtime,List<Map<String,Object>> projected){
        Map<String,Object> saved=QuickSettingsConfig.pins(config).stream()
                .filter(pin->pinId.equals(pin.get("id"))&&!Boolean.FALSE.equals(pin.get("pinned"))).findFirst().orElse(null);
        if(saved!=null)return saved;
        if(runtime==null)throw new IllegalArgumentException("Закреплённый элемент больше недоступен.");
        DynamicInstanceRegistry.RuntimeInstance instance=runtime.instances().values().stream()
                .filter(item->pinId.equals("generated-pin:"+item.generatorId()+":"+item.instanceKey())).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Созданный экземпляр больше не существует."));
        Map<String,Object> automatic=projected.stream().filter(pin->pinId.equals(pin.get("id"))).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Созданный экземпляр больше не закреплён."));
        String groupId=String.valueOf(automatic.get("groupId"));
        int order=automatic.get("order") instanceof Number number?number.intValue():0;
        return QuickSettingsConfig.pinNode(config,runtime.configuredGraph().id(),instance.generatorId(),
                instance.instanceKey(),groupId,order,String.valueOf(automatic.getOrDefault("view","EXPANDED")));
    }

}
