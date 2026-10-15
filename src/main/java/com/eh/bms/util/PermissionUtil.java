package com.eh.bms.util;

import com.eh.bms.model.AppModule;
import com.eh.bms.service.UserSession;
import com.eh.bms.service.UserSession.Action;
import javafx.scene.Node;

/**
 * Hides controls the logged-in user has no permission for.
 * Use in view controllers, e.g. {@code PermissionUtil.require(addButton, AppModule.SALE, Action.CREATE)}.
 * Hidden controls also free their layout space.
 */
public final class PermissionUtil {

    private PermissionUtil() {
    }

    /** Shows the node only if the current user may do the action in the module. */
    public static void require(Node node, AppModule module, Action action) {
        boolean allowed = UserSession.isLoggedIn() && UserSession.get().can(module, action);
        node.setVisible(allowed);
        node.setManaged(allowed);
    }

    /** Standard create/edit/delete buttons of a module view; pass null for a button the view does not have. */
    public static void applyCrud(AppModule module, Node createButton, Node editButton, Node deleteButton) {
        if (createButton != null) {
            require(createButton, module, Action.CREATE);
        }
        if (editButton != null) {
            require(editButton, module, Action.EDIT);
        }
        if (deleteButton != null) {
            require(deleteButton, module, Action.DELETE);
        }
    }
}
