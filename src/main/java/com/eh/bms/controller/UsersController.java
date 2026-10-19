package com.eh.bms.controller;

import com.eh.bms.model.UserRole;
import com.eh.bms.service.UserSession;
import javafx.fxml.FXML;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

/**
 * Users page (Stage 17, admin only): the users list, the permission editor and the audit log tabs. Opening the
 * users or audit tab loads it again, so a change made elsewhere shows. The permission tab is not reloaded, so
 * what is being ticked is not lost by a click on another tab. The support login sees only the users list.
 */
public class UsersController {

    @FXML private TabPane tabs;
    @FXML private Tab listTab;
    @FXML private Tab auditTab;
    /** Controllers of the included tabs (fx:id="list" and "audit"). */
    @FXML private UsersListController listController;
    @FXML private AuditLogController auditController;

    @FXML
    private void initialize() {
        if (UserSession.get().getRole() == UserRole.SUPER_ADMIN) {
            tabs.getTabs().removeIf(tab -> tab != listTab);
        }
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, old, tab) -> {
            if (tab == listTab) {
                listController.reload();
            } else if (tab == auditTab) {
                auditController.reload();
            }
        });
    }
}
