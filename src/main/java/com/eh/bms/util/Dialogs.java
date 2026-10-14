package com.eh.bms.util;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

/** Pop-up dialogs in the current language. Call on the JavaFX thread. */
public final class Dialogs {

    private Dialogs() {
    }

    /** Yes/No question. True only if the user clicks Yes; closing the dialog counts as No. */
    public static boolean confirm(String message) {
        ButtonType yes = new ButtonType(I18n.get("button.yes"), ButtonBar.ButtonData.YES);
        ButtonType no = new ButtonType(I18n.get("button.no"), ButtonBar.ButtonData.NO);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, yes, no);
        alert.setTitle(I18n.get("dialog.confirm.title"));
        alert.setHeaderText(null);
        // Same button look as the rest of the app: Yes = save-button, No = cancel-button
        alert.getDialogPane().getStylesheets().add(WindowManager.stylesheet());
        alert.getDialogPane().lookupButton(yes).getStyleClass().add("save-button");
        alert.getDialogPane().lookupButton(no).getStyleClass().add("cancel-button");
        // Owner = the window in front (e.g. an add/edit popup), so the question never hides behind it
        Window owner = Window.getWindows().stream().filter(Window::isFocused).findFirst()
                .orElse(WindowManager.currentWindow());
        if (owner != null) {
            alert.initOwner(owner);
        }
        AppIcons.apply(alert.getDialogPane().getScene().getWindow());
        return alert.showAndWait().filter(yes::equals).isPresent();
    }

    /** Asks "Do you want to save?" before every save. */
    public static boolean confirmSave() {
        return confirm(I18n.get("msg.confirm_save"));
    }
}
