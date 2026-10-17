package com.eh.bms.controller;

import javafx.fxml.FXML;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

/**
 * Expenses page: the expenses list, the monthly summary and the categories tabs. Opening the summary loads it
 * again; a category change refreshes the list's category filter.
 */
public class ExpensesController {

    @FXML private TabPane tabs;
    @FXML private Tab listTab;
    @FXML private Tab summaryTab;
    /** Controllers of the included tabs (fx:id="list", "summary" and "categories"). */
    @FXML private ExpensesListController listController;
    @FXML private ExpenseSummaryController summaryController;
    @FXML private ExpenseCategoriesController categoriesController;

    @FXML
    private void initialize() {
        categoriesController.setOnChange(listController::reloadCategories);
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, old, tab) -> {
            if (tab == summaryTab) {
                summaryController.refresh();
            } else if (tab == listTab) {
                listController.reload();
            }
        });
    }
}
