package com.eh.bms.controller;

import javafx.fxml.FXML;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

/**
 * Employees page: the employees list, daily attendance and salary tabs. Opening a tab loads it again,
 * so a change made in one tab (e.g. a new employee) shows in the others.
 */
public class EmployeesController {

    @FXML private TabPane tabs;
    @FXML private Tab listTab;
    @FXML private Tab attendanceTab;
    @FXML private Tab salaryTab;
    /** Controllers of the included tabs (fx:id="list", "attendance" and "salary"). */
    @FXML private EmployeesListController listController;
    @FXML private AttendanceController attendanceController;
    @FXML private SalaryController salaryController;

    @FXML
    private void initialize() {
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, old, tab) -> {
            if (tab == listTab) {
                listController.reload();
            } else if (tab == attendanceTab) {
                attendanceController.reload();
            } else if (tab == salaryTab) {
                salaryController.reload();
            }
        });
    }
}
