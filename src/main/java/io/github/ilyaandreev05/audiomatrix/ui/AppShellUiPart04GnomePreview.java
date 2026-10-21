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

abstract class AppShellUiPart04GnomePreview extends AppShellUiPart04GraphCanvasSupport {
    protected AppShellUiPart04GnomePreview(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }
    protected VBox gnomePanelPreview(List<Map<String,Object>> groups,List<Map<String,Object>> pins,String projectionError){
        VBox preview=card(t("Предпросмотр GNOME","GNOME live preview"));
        preview.getChildren().add(label(t("Показываются те же quickGroups и quickPins, которые публикуются в bridge state.",
                "This uses the same quickGroups and quickPins published in bridge state."),"muted"));
        if(!projectionError.isBlank())preview.getChildren().add(label(t("Предпросмотр недоступен: ","Preview unavailable: ")+projectionError,"danger"));
        if(groups.isEmpty()){
            preview.getChildren().add(label(t("В проекции пока нет групп.","No groups are available in the state projection yet."),"muted"));
            return preview;
        }
        List<Map<String,Object>> ordered=new ArrayList<>(groups);
        ordered.sort(Comparator.comparingInt(group->intValue(group.get("order"),0)));
        for(Map<String,Object> group:ordered){
            String groupId=string(group.get("id"),"");
            List<Map<String,Object>> groupPins=pins.stream().filter(pin->groupId.equals(pin.get("groupId")))
                    .sorted(Comparator.comparingInt(pin->intValue(pin.get("order"),0))).toList();
            VBox groupCard=new VBox(6);groupCard.getStyleClass().add("card");
            groupCard.getChildren().add(label(string(group.get("name"),groupId)
                    +" · "+groupPins.size()+t(" элементов"," items"),"card-title"));
            if(Boolean.TRUE.equals(group.get("collapsed"))){
                groupCard.getChildren().add(label(t("Группа свёрнута в меню GNOME.","This group is collapsed in GNOME."),"muted"));
            }else if(groupPins.isEmpty()){
                groupCard.getChildren().add(label(t("Нет отображаемых закреплений.","No projected pins are visible."),"muted"));
            }else{
                for(Map<String,Object> pin:groupPins)groupCard.getChildren().add(gnomePanelPreviewPin(pin));
            }
            preview.getChildren().add(groupCard);
        }
        return preview;
    }

    private VBox gnomePanelPreviewPin(Map<String,Object> pin){
        VBox row=new VBox(3);row.getStyleClass().add("node-card");
        String name=string(pin.get("displayName"),string(pin.get("name"),string(pin.get("nodeId"),"")));
        String instanceKey=string(pin.get("instanceKey"),"");
        if(!instanceKey.isBlank())name+=" · "+instanceKey;
        String icon=string(pin.get("icon"),"");
        row.getChildren().add(label((icon.isBlank()?"":icon+" · ")+name+" · "+string(pin.get("nodeType"),""),"card-title"));
        Map<String,Object> gain=map(pin.get("gain"));
        if(!gain.isEmpty())row.getChildren().add(label(t("Volume ","Volume ")+percent(gain.get("volume"))+" · "+(Boolean.TRUE.equals(gain.get("muted"))?t("mute","muted"):t("включён","unmuted")),"muted"));
        Map<String,Object> sourceGain=map(pin.get("sourceGain"));
        if(!sourceGain.isEmpty())row.getChildren().add(label(t("Уровень источника ","Source gain ")+percent(sourceGain.get("volume"))+" · "+(Boolean.TRUE.equals(sourceGain.get("muted"))?t("mute","muted"):t("включён","unmuted")),"muted"));
        Map<String,Object> output=map(pin.get("output"));
        if(!output.isEmpty()){
            String outputStatus=string(output.get("state"),string(output.get("status"),""));
            String reason=string(output.get("reason"),"");
            row.getChildren().add(label(t("Выход ","Output ")+outputStatus+(reason.isBlank()?"":" · "+reason),"muted"));
        }
        List<Map<String,Object>> applications=maps(pin.get("applications"));
        for(Map<String,Object> application:applications){
            String applicationName=string(application.get("name"),string(application.get("applicationName"),""));
            if(applicationName.isBlank())continue;
            row.getChildren().add(label(applicationName+" · "+percent(application.get("volume"))+" · "
                    +(Boolean.TRUE.equals(application.get("muted"))?t("mute","muted"):t("включён","unmuted")),"muted"));
        }
        for(Map<String,Object> input:maps(pin.get("mixerInputs"))){
            row.getChildren().add(label(string(input.get("name"),string(input.get("id"),""))
                    +" · "+percent(input.get("gain"))+" · "+(Boolean.TRUE.equals(input.get("muted"))?t("mute","muted"):t("включён","unmuted")),"muted"));
        }
        List<Map<String,Object>> controls=maps(pin.get("controls"));
        for(Map<String,Object> control:controls){
            String labelText=string(control.get("label"),string(control.get("parameterId"),""));
            Object value=control.get("value");
            row.getChildren().add(label(labelText+(value==null?"":" · "+value),"muted"));
        }
        return row;
    }

    private String percent(Object value){
        if(!(value instanceof Number number)||!Double.isFinite(number.doubleValue()))return t("не измерено","not measured");
        return String.format(Locale.ROOT,"%.0f%%",Math.max(0,Math.min(1,number.doubleValue()))*100);
    }

    protected int intValue(Object value,int fallback){
        return value instanceof Number number?number.intValue():fallback;
    }
}
