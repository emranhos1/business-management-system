package com.eh.bms.util;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Pop-up dialogs in the current language. Call on the JavaFX thread. */
public final class Dialogs {

    private static final double MESSAGE_WIDTH = 380;

    private Dialogs() {
    }

    /** Yes/No question. True only if the user clicks Yes; closing the dialog (Esc) counts as No. */
    public static boolean confirm(String message) {
        return confirm(message, "save-button");
    }

    /** Yes/No before every delete; Yes is red (delete-button) so the user sees it removes something. */
    public static boolean confirmDelete(String message) {
        return confirm(message, "delete-button");
    }

    /** A popup like every other (title on the left strip): the message, then Yes and No (grey). Enter = Yes. */
    private static boolean confirm(String message, String yesStyle) {
        Label text = new Label(message);
        text.setWrapText(true);
        text.setMaxWidth(MESSAGE_WIDTH);
        text.getStyleClass().add("confirm-message");

        Button yes = new Button(I18n.get("button.yes"));
        yes.getStyleClass().add(yesStyle);
        yes.setDefaultButton(true);
        Button no = new Button(I18n.get("button.no"));
        no.getStyleClass().add("no-button");
        no.setCancelButton(true);
        HBox buttons = new HBox(yes, no);
        buttons.getStyleClass().add("dialog-buttons");
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(text, buttons);
        root.getStyleClass().add("confirm-dialog");

        // Owner = the window in front (e.g. an add/edit popup), so the question never hides behind it
        Window owner = Window.getWindows().stream().filter(Window::isFocused).findFirst()
                .orElse(WindowManager.currentWindow());
        Stage stage = WindowManager.dialog(root, I18n.get("dialog.confirm.title"), owner);
        boolean[] answer = {false};
        yes.setOnAction(e -> {
            answer[0] = true;
            stage.close();
        });
        no.setOnAction(e -> stage.close());
        stage.showAndWait();
        return answer[0];
    }

    /** Answer to {@link #askSave}. */
    public enum SaveChoice {
        SAVE, DISCARD, CANCEL
    }

    /**
     * Unsaved input: Save (blue, Enter), Don't save (red), Cancel (grey, Esc). Closing the dialog = Cancel.
     */
    public static SaveChoice askSave(String message) {
        Label text = new Label(message);
        text.setWrapText(true);
        text.setMaxWidth(MESSAGE_WIDTH);
        text.getStyleClass().add("confirm-message");

        Button save = new Button(I18n.get("button.save"));
        save.getStyleClass().add("save-button");
        save.setDefaultButton(true);
        Button discard = new Button(I18n.get("button.dont_save"));
        discard.getStyleClass().add("delete-button");
        Button cancel = new Button(I18n.get("button.cancel"));
        cancel.getStyleClass().add("no-button");
        cancel.setCancelButton(true);
        HBox buttons = new HBox(save, discard, cancel);
        buttons.getStyleClass().add("dialog-buttons");
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(text, buttons);
        root.getStyleClass().add("confirm-dialog");
        Window owner = Window.getWindows().stream().filter(Window::isFocused).findFirst()
                .orElse(WindowManager.currentWindow());
        Stage stage = WindowManager.dialog(root, I18n.get("dialog.confirm.title"), owner);
        SaveChoice[] answer = {SaveChoice.CANCEL};
        save.setOnAction(e -> {
            answer[0] = SaveChoice.SAVE;
            stage.close();
        });
        discard.setOnAction(e -> {
            answer[0] = SaveChoice.DISCARD;
            stage.close();
        });
        cancel.setOnAction(e -> stage.close());
        stage.showAndWait();
        return answer[0];
    }

    /** Asks "Do you want to save?" before every save. */
    public static boolean confirmSave() {
        return confirm(I18n.get("msg.confirm_save"));
    }
}
