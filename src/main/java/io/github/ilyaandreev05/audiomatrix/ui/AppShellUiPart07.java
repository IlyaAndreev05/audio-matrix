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

abstract class AppShellUiPart07 extends AppShellUiPart06 {
    protected AppShellUiPart07(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    protected void duplicateSelectedGraphGroup(String groupId){if(groupId.isBlank())return;updateRouteConfig(t("Дублировать группу","Duplicate group"),config->GraphRouteLibrary.duplicateGroup(config,groupId,null));}

    protected void deleteGraphGroup(String groupId){if(groupId.isBlank())return;updateRouteConfig(t("Удалить группу","Delete group"),config->GraphRouteLibrary.deleteGroup(config,groupId));}

    protected void restoreGraphCopy(String copyId){updateRouteConfig(t("Восстановить копию маршрута","Restore route snapshot"),config->{AudioGraph restored=GraphRouteLibrary.restoreSavedCopy(config,copyId,null);selectedGraphRouteId=restored.id();});}

    protected VBox routeHistoryCard() {
        VBox history = card(t("История маршрутов", "Route history"));
        history.getChildren().add(label(t("История хранится в текущем сеансе. Отмена сохраняет прежний config.json; если граф уже применён, Audio Matrix обновит его согласно восстановленной конфигурации.",
                "History is kept for this session. Undo saves the previous JSON; if a route graph is active, Audio Matrix updates it to match the restored configuration."), "muted"));
        if (routeHistory.isEmpty()) {
            history.getChildren().add(label(t("Изменений маршрутов в этом сеансе пока нет.", "No route changes in this session yet."), "muted"));
        } else {
            int shown = 0;
            for (RouteHistoryEntry entry : routeHistory) {
                if (shown++ == 10) break;
                history.getChildren().add(label("• " + entry.description(), "muted"));
            }
        }
        Button undo = actionButton(t("Отменить последнее изменение", "Undo latest change"), this::undoLastRouteChange);
        undo.setDisable(routeUndoInProgress || routeHistory.isEmpty());
        Button redo = actionButton(t("Повторить изменение", "Redo change"), this::redoLastRouteChange);
        redo.setDisable(routeUndoInProgress || routeRedoHistory.isEmpty());
        history.getChildren().add(new HBox(8, undo, redo));
        return history;
    }

    protected void undoLastRouteChange() {
        if (routeUndoInProgress || routeHistory.isEmpty()) return;
        if (store.revision() != routeHistoryRevision) {
            routeHistory.clear();
            routeRedoHistory.clear();
            routeHistoryRevision = -1;
            setNotice(t("История устарела после другого изменения конфигурации; откат не выполнен.",
                    "Route history is stale after another configuration change; undo was not applied."), true);
            showPage(Page.ROUTES);
            return;
        }
        RouteHistoryEntry entry = routeHistory.peekFirst();
        Map<String,Object> currentSnapshot = store.snapshot();
        routeUndoInProgress = true;
        showPage(Page.ROUTES);
        store.restoreIfRevisionAsync(entry.previousSnapshot(), routeHistoryRevision).whenComplete((revision, error) -> Platform.runLater(() -> {
            routeUndoInProgress = false;
            if (error != null) {
                routeHistory.clear();
                routeRedoHistory.clear();
                routeHistoryRevision = -1;
                setNotice(t("Откат маршрута не сохранён: ", "Route undo was not saved: ") + rootMessage(error), true);
            } else {
                routeHistory.removeFirst();
                routeRedoHistory.addFirst(new RouteHistoryEntry(entry.description(), currentSnapshot));
                while (routeRedoHistory.size() > 20) routeRedoHistory.removeLast();
                routeHistoryRevision = revision;
                setNotice(t("Отменено: ", "Undid: ") + entry.description(), false);
            }
            showPage(Page.ROUTES);
        }));
    }

    protected void redoLastRouteChange() {
        if (routeUndoInProgress || routeRedoHistory.isEmpty()) return;
        if (store.revision() != routeHistoryRevision) {
            routeHistory.clear();
            routeRedoHistory.clear();
            routeHistoryRevision = -1;
            setNotice(t("История устарела после другого изменения конфигурации; повтор не выполнен.",
                    "Route history is stale after another configuration change; redo was not applied."), true);
            showPage(Page.ROUTES);
            return;
        }
        RouteHistoryEntry entry = routeRedoHistory.peekFirst();
        Map<String,Object> currentSnapshot = store.snapshot();
        routeUndoInProgress = true;
        showPage(Page.ROUTES);
        store.restoreIfRevisionAsync(entry.previousSnapshot(), routeHistoryRevision).whenComplete((revision, error) -> Platform.runLater(() -> {
            routeUndoInProgress = false;
            if (error != null) {
                routeHistory.clear();
                routeRedoHistory.clear();
                routeHistoryRevision = -1;
                setNotice(t("Повтор изменения маршрута не сохранён: ", "Route redo was not saved: ") + rootMessage(error), true);
            } else {
                routeRedoHistory.removeFirst();
                routeHistory.addFirst(new RouteHistoryEntry(entry.description(), currentSnapshot));
                while (routeHistory.size() > 20) routeHistory.removeLast();
                routeHistoryRevision = revision;
                setNotice(t("Повторено: ", "Redid: ") + entry.description(), false);
            }
            showPage(Page.ROUTES);
        }));
    }

    protected void updateRouteConfig(String description, Consumer<Map<String, Object>> change) {
        store.updateWithHistoryAsync(change).whenComplete((saved, error) -> Platform.runLater(() -> finishRouteHistory(description, saved, error)));
    }

    protected CompletableFuture<Void> updateRouteConfigCommand(String description, Consumer<Map<String,Object>> change) {
        return store.updateWithHistoryAsync(change).handle((saved,error)->{
            Platform.runLater(()->finishRouteHistory(description,saved,error));
            if(error!=null)throw new CompletionException(error);
            return null;
        });
    }

    protected void saveRouteConfig(String description, Map<String, Object> next) {
        store.saveWithHistoryAsync(next).whenComplete((saved, error) -> Platform.runLater(() -> finishRouteHistory(description, saved, error)));
    }

    protected void finishRouteHistory(String description, ConfigStore.ConfigChange saved, Throwable error) {
        if (error != null) {
            setNotice(t("Конфигурация маршрута не сохранена: ", "Route configuration was not saved: ") + rootMessage(error), true);
            showPage(currentPage);
            return;
        }
        if (!routeHistory.isEmpty() && routeHistoryRevision != saved.beforeRevision()) routeHistory.clear();
        routeRedoHistory.clear();
        routeHistory.addFirst(new RouteHistoryEntry(description, saved.previousSnapshot()));
        while (routeHistory.size() > 20) routeHistory.removeLast();
        routeHistoryRevision = saved.afterRevision();
        setNotice(t("Изменение маршрута сохранено.", "Route change saved."), false);
        showPage(currentPage);
    }
}
