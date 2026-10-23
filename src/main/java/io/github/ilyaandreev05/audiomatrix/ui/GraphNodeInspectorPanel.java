package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.config.ChannelMatrix;
import io.github.ilyaandreev05.audiomatrix.config.GraphMatrixViewSettings;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.AudioPort;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.FilterNode;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GateNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.MatrixNode;
import io.github.ilyaandreev05.audiomatrix.graph.MixerNode;
import io.github.ilyaandreev05.audiomatrix.graph.MultiSelectorNode;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;
import io.github.ilyaandreev05.audiomatrix.graph.RouterNode;
import io.github.ilyaandreev05.audiomatrix.graph.SelectorNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import io.github.ilyaandreev05.audiomatrix.graph.SwitchNode;
import io.github.ilyaandreev05.audiomatrix.gnome.GnomeQuickControlParameter;
import io.github.ilyaandreev05.audiomatrix.ui.AppShellUiPart04GraphInspector.DynamicChildAddress;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Inspector card of the selected graph node together with its dynamic generator controls. */
final class GraphNodeInspectorPanel {
    private GraphNodeInspectorPanel() {}

    static VBox create(AppShellUiPart06 shell, AudioGraph graph){
        VBox box=shell.card(shell.t("Инспектор выбранного узла","Selected node inspector"));
        GraphNode selected=graph.nodes().stream().filter(node->!shell.selectedGraphNodeId.isBlank()&&shell.selectedGraphNodeId.equals(node.id()))
                .findFirst().orElse(null);
        if(selected==null){
            box.getChildren().add(shell.label(graph.nodes().isEmpty()?shell.t("Граф пуст.","Graph is empty.")
                    :shell.t("Выбери узел на схеме или в дереве GNOME.","Select a node on the canvas or in the GNOME tree."),"muted"));
            return box;
        }
        if(selected instanceof DynamicGeneratorNode generator){
            box.getChildren().add(GraphGeneratorControls.create(shell,graph,generator));
            boolean pinned=shell.graphNodePinned(graph.id(),generator.id());
            box.getChildren().addAll(
                    shell.actionButton(pinned?shell.t("Открепить генератор от GNOME","Unpin generator from GNOME"):shell.t("Закрепить генератор в GNOME","Pin generator in GNOME"),()->shell.toggleGraphNodePin(graph.id(),generator,pinned)),
                    shell.actionButton(shell.t("Удалить генератор","Delete generator"),()->shell.removeGraphNode(graph.id(),generator.id())));
            return box;
        }
        if(!shell.isSavedGraphNode(graph.id(),selected.id())){
            DynamicChildAddress address=shell.dynamicChildAddress(graph.id(),selected.id());
            if(address!=null)box.getChildren().add(shell.gnomePanelProperties(new GnomePanelItem("GENERATED_NODE",
                    selected.name(),graph.id(),address.generatorId(),address.instanceKey(),"",null,selected,null,address.instance()),
                    shell.store.snapshot()));
            if(selected instanceof GainNode gain){
                if(address==null||!address.instance().available()){
                    box.getChildren().add(shell.label(shell.t("Экземпляр не подключён; управление звуком недоступно.","The instance is disconnected; audio control is unavailable."),"muted"));
                    return box;
                }
                if(shell.browserTabInstance(graph.id(),address)&&!shell.independentTabVolumeAvailable(address)){
                    box.getChildren().add(shell.label(shell.t("Независимая громкость вкладки недоступна: браузер не передал отдельный захваченный аудиопоток. Громкость браузера не заменяет громкость вкладки.",
                            "Independent tab volume is unavailable: the browser has not supplied a separate captured audio stream. Browser volume does not substitute for tab volume."),"muted"));
                    return box;
                }
                box.getChildren().add(GraphGainControl.create(gain,shell.t("Громкость экземпляра: ","Instance volume: ")+gain.name(),
                        (level,muted)->dispatchGeneratedGain(shell,graph.id(),address,level,muted)));
                if(shell.browserTabInstance(graph.id(),address))
                    box.getChildren().add(shell.label(shell.t("Управляет отдельным захваченным аудиопотоком вкладки.",
                            "Controls the tab's independently captured audio stream."),"muted"));
            }else box.getChildren().add(shell.label(shell.t("Дочерний узел принадлежит генератору; удаление отключено.",
                    "This child node is owned by its generator; deletion is disabled."),"muted"));
            return box;
        }
        for(GraphNode node:List.of(selected)){
            HBox row=new HBox(12);row.setAlignment(Pos.CENTER_LEFT);
            row.getChildren().add(shell.label(node.name()+" · "+GraphNodeLabels.typeName(node,shell::t),"card-title"));
            if(node instanceof GainNode gain)row.getChildren().add(GraphGainControl.create(gain,shell.t("Уровень графа: ","Graph level: ")+gain.name(),(level,muted)->shell.updateGraphNodeParameters(graph.id(),gain.id(),parameters->{if(level!=null)parameters.put("gain",level);parameters.put("muted",muted);})));
            else if(node instanceof MixerNode mixer){
                row.getChildren().add(GraphMixerControls.create(graph,mixer,shell.store.snapshot(),shell.dynamicGraphState(graph.id()),shell.snapshot.connected(),
                        shell.t("Уровень и mute входов","Mixer input levels and mute"),shell.t("Mute","Mute"),
                        (port,gain)->shell.updateGraphMixerInput(graph.id(),mixer.id(),port,gain,null),
                        (port,muted)->shell.updateGraphMixerInput(graph.id(),mixer.id(),port,null,muted)));
            }
            else if(node instanceof DeviceSlotNode slot)row.getChildren().add(GraphDeviceSlotControl.create(slot,shell.physicalSinks(),AppShellBase.maps(shell.store.snapshot().get("devices")),shell::t,deviceId->shell.updateGraphNodeParameters(graph.id(),node.id(),parameters->parameters.put("device_id",deviceId))));
            else if(node instanceof OutputNode output){
                ComboBox<String> channelTarget=new ComboBox<>();channelTarget.getItems().setAll(
                        shell.t("Передние каналы FL/FR","Front channels FL/FR"),
                        shell.t("Тыловые каналы RL/RR · нужен профиль 4.0","Rear channels RL/RR · requires 4.0 profile"));
                channelTarget.setAccessibleText(shell.t("Каналы физического выхода","Physical output channels"));
                channelTarget.setValue(output.channelMatrix()!=null&&output.channelMatrix().outputs().equals(List.of("RL","RR"))
                        ?channelTarget.getItems().get(1):channelTarget.getItems().getFirst());
                channelTarget.setOnAction(event->{boolean rear=channelTarget.getSelectionModel().getSelectedIndex()==1;
                    shell.updateGraphNodeParameters(graph.id(),node.id(),parameters->{
                        if(rear){ChannelMatrix matrix=new ChannelMatrix(List.of("FL","FR"),List.of("RL","RR"),List.of(List.of(1.0,0.0),List.of(0.0,1.0)));parameters.put("channel_matrix",matrix.toMap());}
                        else parameters.remove("channel_matrix");
                    });
                });
                row.getChildren().add(channelTarget);
            }
            else if(node instanceof GateNode gate){CheckBox open=new CheckBox(gate.open()?shell.t("Вкл.","On"):shell.t("Выкл.","Off"));open.setAccessibleText(shell.t("Gate: включён или выключен","Gate: on or off"));open.setSelected(gate.open());open.setOnAction(e->{open.setText(open.isSelected()?shell.t("Вкл.","On"):shell.t("Выкл.","Off"));shell.updateGraphNodeParameters(graph.id(),node.id(),parameters->parameters.put("open",open.isSelected()));});row.getChildren().add(open);}
            else if(node instanceof FilterNode filter){CheckBox bypass=new CheckBox(filter.enabled()?shell.t("Вкл.","On"):shell.t("Выкл.","Off"));bypass.setAccessibleText(shell.t("Фильтр: включён или обойдён","Filter: enabled or bypassed"));bypass.setSelected(filter.enabled());bypass.setOnAction(e->{bypass.setText(bypass.isSelected()?shell.t("Вкл.","On"):shell.t("Выкл.","Off"));shell.updateGraphNodeParameters(graph.id(),node.id(),parameters->parameters.put("enabled",bypass.isSelected()));});row.getChildren().add(bypass);if(GraphFilterEditor.supportsEditor(filter))row.getChildren().add(shell.actionButton(shell.t("Настроить эффект","Edit effect"),()->GraphFilterEditor.show(shell.getScene()==null?null:shell.getScene().getWindow(),filter,shell::t).ifPresent(settings->shell.updateGraphNodeParameters(graph.id(),node.id(),parameters->{parameters.clear();parameters.put("processor",settings.processor());parameters.put("enabled",settings.enabled());parameters.putAll(settings.parameters());}))));}
            else if(node instanceof RouterNode router){
                List<String> active=router.activeLinks();
                VBox routerControls=new VBox(5);
                if(node instanceof MatrixNode matrix){
                    routerControls.getChildren().add(GraphMatrixViews.create(matrix,
                            shell.t("Матрица","Matrix"),shell.t("Стрелки","Arrows"),shell.t("Вход ↓ / выход →","Input ↓ / output →"),
                            GraphMatrixViewSettings.get(shell.store.snapshot(), graph.id(), node.id()),
                            (link,enabled)->shell.setRouterLink(graph.id(),node.id(),link,enabled,router.multiple()),
                            view->GraphNodeCommands.saveMatrixView(shell, graph.id(), node.id(), view)));
                }else{
                    GridPane table=new GridPane();table.setHgap(8);table.setVgap(4);
                    table.add(shell.label(shell.t("Вход ↓ / выход →","Input ↓ / output →"),"muted"),0,0);
                    for(int col=0;col<router.outputs().size();col++)table.add(shell.label(router.outputs().get(col).id(),"muted"),col+1,0);
                    for(int r=0;r<router.inputs().size();r++){
                        AudioPort input=router.inputs().get(r);table.add(shell.label(input.id(),"muted"),0,r+1);
                        for(int c=0;c<router.outputs().size();c++){
                            AudioPort output=router.outputs().get(c);String link=input.id()+"->"+output.id();
                            CheckBox cell=new CheckBox();cell.setSelected(active.contains(link));cell.setAccessibleText(node.name()+" "+input.id()+" → "+output.id());
                            cell.setOnAction(event->shell.setRouterLink(graph.id(),node.id(),link,cell.isSelected(),router.multiple()));table.add(cell,c+1,r+1);
                        }
                    }
                    routerControls.getChildren().add(table);
                }
                routerControls.getChildren().add(shell.label(active.size()+shell.t(" активных связей"," active links"),"muted"));
                if(node instanceof MatrixNode||node instanceof SelectorNode||node instanceof MultiSelectorNode)routerControls.getChildren().add(shell.actionButton(shell.t("Добавить вход","Add input"),()->shell.addRouterPort(graph.id(),node,"input")));
                if(node instanceof MatrixNode||node instanceof SwitchNode)routerControls.getChildren().add(shell.actionButton(shell.t("Добавить выход","Add output"),()->shell.addRouterPort(graph.id(),node,"output")));
                row.getChildren().add(routerControls);
            }
            if(node instanceof SourceNode||node instanceof OutputNode){GainNode bound=GraphGainControl.endpoint(graph,node);row.getChildren().add(bound==null?shell.label(shell.t("Нет привязанного Gain: уровень недоступен","No bound Gain: level unavailable"),"muted"):GraphGainControl.create(bound,shell.t("Уровень графа: ","Graph level: ")+bound.name(),(level,muted)->shell.updateGraphNodeParameters(graph.id(),bound.id(),parameters->{if(level!=null)parameters.put("gain",level);parameters.put("muted",muted);})));}
            boolean pinned=shell.graphNodePinned(graph.id(),node.id());row.getChildren().add(shell.actionButton(pinned?shell.t("Открепить от GNOME","Unpin from GNOME"):shell.t("Закрепить в GNOME","Pin in GNOME"),()->shell.toggleGraphNodePin(graph.id(),node,pinned)));
            if(node instanceof FilterNode){List<GnomeQuickControlParameter.Definition> controls=GnomeQuickControlParameter.definitions(node);row.getChildren().add(GraphQuickControlMenu.create(shell.t("Быстрые контролы","Quick controls"),controls,control->shell.quickControlPinned(graph.id(),node.id(),control.id()),control->shell.t(control.russianLabel(),control.englishLabel()),(control,checked)->shell.toggleGraphQuickControl(graph.id(),node,control.id(),!checked)));}
            else{String quickParameter=shell.quickControlParameter(node);if(!quickParameter.isBlank()){boolean controlPinned=shell.quickControlPinned(graph.id(),node.id(),quickParameter);row.getChildren().add(shell.actionButton(controlPinned?shell.t("Открепить параметр","Unpin parameter"):shell.t("Закрепить параметр","Pin parameter"),()->shell.toggleGraphQuickControl(graph.id(),node,quickParameter,controlPinned)));}}
            Region space=new Region();HBox.setHgrow(space,Priority.ALWAYS);row.getChildren().add(space);row.getChildren().add(shell.actionButton(shell.t("Удалить","Delete"),()->shell.removeGraphNode(graph.id(),node.id())));box.getChildren().add(row);
        }
        return box;
    }

    private static void dispatchGeneratedGain(AppShellUiPart06 shell,String routeId,DynamicChildAddress address,Double level,Boolean muted){
        CompletableFuture<Void> operation=CompletableFuture.completedFuture(null);
        if(level!=null)operation=operation.thenCompose(ignored->shell.setGraphNodeVolume(routeId,address.generatorId(),address.instanceKey(),level));
        if(muted!=null)operation=operation.thenCompose(ignored->shell.setGraphNodeMuted(routeId,address.generatorId(),address.instanceKey(),muted));
        operation.whenComplete((ignored,error)->{
            if(error!=null)Platform.runLater(()->{
                shell.setNotice(shell.t("Громкость экземпляра не изменена: ","Instance volume was not changed: ")+AppShellBase.rootMessage(error),true);
                shell.refreshGraphPageRuntime();
            });
        });
    }
}
