package com.eh.bms.controller;

import com.eh.bms.model.AppModule;
import com.eh.bms.service.UserSession;
import com.eh.bms.service.UserSession.Action;
import javafx.fxml.FXML;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

/** Purchases page: the new-purchase tab and the purchases list tab; a saved purchase reloads the list. */
public class PurchasesController {

    @FXML private TabPane tabs;
    @FXML private Tab entryTab;
    @FXML private Tab listTab;
    /** Controllers of the included tabs (fx:id="entry" and "list"). */
    @FXML private PurchaseEntryController entryController;
    @FXML private PurchasesListController listController;

    @FXML
    private void initialize() {
        entryController.setOnSaved(listController::reload);
        // Opening the list tab shows the latest purchases
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, old, tab) -> {
            if (tab == listTab) {
                listController.reload();
            }
        });
        // Only users who may add purchases get the new-purchase tab
        if (!(UserSession.isLoggedIn() && UserSession.get().can(AppModule.PURCHASE, Action.CREATE))) {
            tabs.getTabs().remove(entryTab);
            tabs.getSelectionModel().select(listTab);
        }
    }
}
