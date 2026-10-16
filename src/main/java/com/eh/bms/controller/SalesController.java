package com.eh.bms.controller;

import javafx.fxml.FXML;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

/** Sales page: the new-sale tab and the sales list tab; a saved sale reloads the list. */
public class SalesController {

    @FXML private TabPane tabs;
    @FXML private Tab listTab;
    /** Controllers of the included tabs (fx:id="entry" and "list"). */
    @FXML private SaleEntryController entryController;
    @FXML private SalesListController listController;

    @FXML
    private void initialize() {
        entryController.setOnSaved(listController::reload);
        // Opening the list tab shows the latest sales
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, old, tab) -> {
            if (tab == listTab) {
                listController.reload();
            }
        });
    }
}
