package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.gnome.GnomeQuickControlParameter;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.MenuButton;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** Presents the available parameters of a graph node as GNOME pin actions. */
final class GraphQuickControlMenu {
    private GraphQuickControlMenu() {}

    static MenuButton create(String title, List<GnomeQuickControlParameter.Definition> definitions,
                             Predicate<GnomeQuickControlParameter.Definition> pinned,
                             Function<GnomeQuickControlParameter.Definition, String> label,
                             BiConsumer<GnomeQuickControlParameter.Definition, Boolean> onChange) {
        MenuButton menu = new MenuButton(title);
        for (GnomeQuickControlParameter.Definition definition : definitions) {
            CheckMenuItem item = new CheckMenuItem(label.apply(definition));
            item.setSelected(pinned.test(definition));
            item.setOnAction(event -> onChange.accept(definition, item.isSelected()));
            menu.getItems().add(item);
        }
        return menu;
    }
}
