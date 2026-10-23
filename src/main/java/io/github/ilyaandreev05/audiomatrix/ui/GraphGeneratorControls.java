package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.AudioPort;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Settings panel of a dynamic generator node of the graph inspector. */
final class GraphGeneratorControls {
    private GraphGeneratorControls() {}

    static VBox create(AppShellUiPart06 shell,AudioGraph graph,DynamicGeneratorNode generator){
        VBox controls=new VBox(8);
        controls.getChildren().add(shell.label(shell.t("Параметры генератора","Generator settings"),"card-title"));
        TextField selector=shell.field(generator.selector(),shell.t("Правило отбора объектов","Object selection rule"));
        ComboBox<String> registry=new ComboBox<>();registry.getItems().setAll("applications","outputs","browser_tabs");
        registry.setValue(generator.registry());registry.setAccessibleText(shell.t("Тип реестра генератора","Generator registry"));
        registry.setConverter(new StringConverter<>(){
            @Override public String toString(String value){return switch(value==null?"":value){
                case "applications"->shell.t("Приложения","Applications");case "outputs"->shell.t("Выходные устройства","Output devices");
                case "browser_tabs"->shell.t("Вкладки браузеров","Browser tabs");default->"";};}
            @Override public String fromString(String value){return value;}
        });
        ComboBox<String> template=new ComboBox<>();template.setAccessibleText(shell.t("Шаблон экземпляра генератора","Generator instance template"));
        template.setConverter(new StringConverter<>(){
            @Override public String toString(String value){return switch(value==null?"":value){
                case "default-output"->shell.t("Выход с громкостью","Output with volume");
                case "default-tab"->shell.t("Вкладка с доступными аудиовозможностями","Tab with available audio capabilities");
                case "default-application"->shell.t("Приложение с громкостью","Application with volume");default->value;};}
            @Override public String fromString(String value){return value;}
        });
        Runnable refreshTemplate=()->{
            String value=registry.getValue();
            template.getItems().setAll(switch(value==null?"applications":value){
                case "outputs"->List.of("default-output");
                case "browser_tabs"->List.of("default-tab");
                default->List.of("default-application");
            });
            if(template.getItems().contains(generator.template()))template.setValue(generator.template());
            else template.getSelectionModel().selectFirst();
        };
        refreshTemplate.run();
        ComboBox<GeneratorAnchor> anchor=new ComboBox<>();
        anchor.setMaxWidth(Double.MAX_VALUE);anchor.setAccessibleText(shell.t("Порт подключения экземпляров генератора","Generator instance connection port"));
        anchor.setConverter(new StringConverter<>(){@Override public String toString(GeneratorAnchor value){return value==null?"":value.nodeName()+" · "+value.nodeId()+" · "+value.portId();}@Override public GeneratorAnchor fromString(String value){return null;}});
        Runnable refreshAnchors=()->{
            Map<String,Object> current=generator.connectTo();
            String currentNode=AppShellBase.string(current.get("node_id"),""),currentPort=AppShellBase.string(current.get("port_id"),"");
            anchor.getItems().clear();
            boolean outputRegistry="outputs".equals(registry.getValue());
            for(GraphNode candidate:graph.nodes()){
                if(candidate.id().equals(generator.id()))continue;
                List<AudioPort> ports=outputRegistry?candidate.outputs():candidate.inputs();
                for(AudioPort port:ports)anchor.getItems().add(new GeneratorAnchor(candidate.id(),candidate.name(),port.id()));
            }
            anchor.getItems().stream().filter(item->currentNode.equals(item.nodeId())&&currentPort.equals(item.portId()))
                    .findFirst().ifPresent(anchor.getSelectionModel()::select);
            if(anchor.getValue()==null&&!anchor.getItems().isEmpty())anchor.getSelectionModel().selectFirst();
        };
        refreshAnchors.run();
        registry.setOnAction(event->{refreshTemplate.run();refreshAnchors.run();});

        Slider initialGain=new Slider(0,1,generator.initialGain());initialGain.setBlockIncrement(.01);
        initialGain.setAccessibleText(shell.t("Начальный уровень созданного экземпляра","Initial generated instance volume"));
        Label initialGainValue=shell.label(String.format(Locale.ROOT,"%.0f%%",generator.initialGain()*100),"muted");
        initialGain.valueProperty().addListener((obs,old,value)->initialGainValue.setText(String.format(Locale.ROOT,"%.0f%%",value.doubleValue()*100)));
        CheckBox autoPin=new CheckBox();autoPin.setSelected(generator.autoPin());
        Runnable pinLabel=()->autoPin.setText(switch(registry.getValue()){
            case "outputs"->shell.t("Автозакреплять новые выходы","Auto-pin new outputs");
            case "browser_tabs"->shell.t("Автозакреплять новые вкладки","Auto-pin new tabs");
            default->shell.t("Автозакреплять новые приложения","Auto-pin new applications");
        });
        pinLabel.run();
        CheckBox audibleOnly=new CheckBox(shell.t("Показывать только аудиоактивные вкладки","Show only audio-active tabs"));
        audibleOnly.setSelected(!Boolean.FALSE.equals(generator.parameters().get("only_audio_active_tabs")));
        audibleOnly.setDisable(!"browser_tabs".equals(registry.getValue()));
        registry.valueProperty().addListener((obs,old,value)->{pinLabel.run();audibleOnly.setDisable(!"browser_tabs".equals(value));});
        List<Map<String,Object>> groups=QuickSettingsConfig.groups(shell.store.snapshot());
        ComboBox<String> group=new ComboBox<>();
        groups.forEach(item->group.getItems().add(AppShellBase.string(item.get("id"),"")));
        group.setAccessibleText(shell.t("Группа GNOME по умолчанию","Default GNOME group"));
        group.setConverter(new StringConverter<>(){
            @Override public String toString(String value){return groups.stream().filter(item->value!=null&&value.equals(item.get("id")))
                    .map(item->AppShellBase.string(item.get("name"),"")).findFirst().orElse("");}
            @Override public String fromString(String value){return value;}
        });
        String defaultGroup=generator.groupId().isBlank()?("outputs".equals(generator.registry())?"outputs":"applications"):generator.groupId();
        group.setValue(group.getItems().contains(defaultGroup)?defaultGroup:group.getItems().stream().findFirst().orElse(null));
        CheckBox retain=new CheckBox(shell.t("Сохранять настройки при исчезновении","Retain settings when instance disappears"));retain.setSelected(generator.retainOnDisappear());
        CheckBox restore=new CheckBox(shell.t("Восстанавливать экземпляр при появлении","Restore instance when it reappears"));restore.setSelected(generator.restoreOnReappear());
        TextField exclusions=shell.field(String.join(", ",generator.excludedSelectors()),shell.t("Исключённые правила через запятую","Excluded selectors, comma separated"));
        Label instanceCount=shell.label(shell.t("Сохранённые экземпляры: ","Saved instances: ")+generator.instances().size(),"muted");
        Button save=shell.actionButton(shell.t("Сохранить параметры генератора","Save generator settings"),()->{
            GeneratorAnchor target=anchor.getValue();
            shell.updateGraphNodeParameters(graph.id(),generator.id(),parameters->{
                parameters.put("selector",selector.getText().trim());
                parameters.put("registry",registry.getValue());
                parameters.put("template",template.getValue());
                parameters.put("initial_gain",initialGain.getValue());
                parameters.put("auto_pin",autoPin.isSelected());
                if("browser_tabs".equals(registry.getValue()))parameters.put("only_audio_active_tabs",audibleOnly.isSelected());
                else parameters.remove("only_audio_active_tabs");
                parameters.remove("auto_select_trusted_hdmi_on_first_discovery");
                parameters.put("retain_on_disappear",retain.isSelected());
                parameters.put("restore_on_reappear",restore.isSelected());
                List<String> excluded=java.util.Arrays.stream(exclusions.getText().split(","))
                        .map(String::trim).filter(value->!value.isBlank()).distinct().toList();
                parameters.put("excluded_selectors",excluded);
                if(group.getValue()==null)parameters.remove("group_id");else parameters.put("group_id",group.getValue());
                if(target==null)parameters.put("connect_to",Map.of());
                else parameters.put("connect_to",Map.of("node_id",target.nodeId(),"port_id",target.portId()));
            });
        });
        save.setDisable(selector.getText().isBlank());
        selector.textProperty().addListener((obs,old,value)->save.setDisable(value.isBlank()));
        controls.getChildren().addAll(shell.label(shell.t("Объекты","Objects"),"muted"),registry,
                shell.label(shell.t("Правило отбора","Selection rule"),"muted"),selector,shell.label(shell.t("Шаблон","Template"),"muted"),template,
                shell.label(shell.t("Подключение экземпляров","Instance connection"),"muted"),anchor,
                new HBox(8,shell.label(shell.t("Начальная громкость","Initial volume"),"muted"),initialGain,initialGainValue),
                autoPin,audibleOnly,shell.label(shell.t("Группа по умолчанию","Default group"),"muted"),group,
                retain,restore,exclusions,instanceCount,
                shell.label(shell.t("Узлов шаблона: ","Template nodes: ")+generator.templateNodes().size()
                        +shell.t(" · связей: "," · connections: ")+generator.templateConnections().size(),"muted"),save);
        return controls;
    }

    private record GeneratorAnchor(String nodeId,String nodeName,String portId) {}
}
