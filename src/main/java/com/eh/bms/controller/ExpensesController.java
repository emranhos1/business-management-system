package com.eh.bms.controller;

import javafx.fxml.FXML;

/**
 * Expenses page: the month's expenses grouped by category, with the categories beside them.
 * A category change reloads the expenses (names, types and totals).
 */
public class ExpensesController {

    /** Controllers of the included parts (fx:id="list" and "categories"). */
    @FXML private ExpensesListController listController;
    @FXML private ExpenseCategoriesController categoriesController;

    @FXML
    private void initialize() {
        categoriesController.setOnChange(listController::reload);
    }
}
