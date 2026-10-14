package com.eh.bms.service;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.Permission;
import com.eh.bms.model.User;
import com.eh.bms.model.UserRole;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/**
 * The logged-in user. Started by {@link AuthService} on a successful login, ended on logout.
 * ADMIN can always do everything, whatever the permissions table says, so an admin can never lock themselves out.
 */
public final class UserSession {

    public enum Action {
        VIEW, CREATE, EDIT, DELETE
    }

    /** What the user may do in one module. */
    public record ModulePermission(boolean view, boolean create, boolean edit, boolean delete) {
        static final ModulePermission NONE = new ModulePermission(false, false, false, false);
        static final ModulePermission ALL = new ModulePermission(true, true, true, true);

        public boolean allows(Action action) {
            return switch (action) {
                case VIEW -> view;
                case CREATE -> create;
                case EDIT -> edit;
                case DELETE -> delete;
            };
        }
    }

    private static volatile UserSession current;

    private final long userId;
    private final String name;
    private final String username;
    private final UserRole role;
    private final Map<AppModule, ModulePermission> permissions;

    private UserSession(User user, Collection<Permission> rows) {
        this.userId = user.getId();
        this.name = user.getName();
        this.username = user.getUsername();
        this.role = user.getRole();

        Map<AppModule, ModulePermission> map = new EnumMap<>(AppModule.class);
        for (Permission p : rows) {
            map.put(p.getModule(), new ModulePermission(p.isCanView(), p.isCanCreate(), p.isCanEdit(), p.isCanDelete()));
        }
        this.permissions = Map.copyOf(map);
    }

    static void start(User user, Collection<Permission> rows) {
        current = new UserSession(user, rows);
    }

    /** Ends the session (logout). */
    public static void end() {
        current = null;
    }

    public static boolean isLoggedIn() {
        return current != null;
    }

    /** The current session. Throws if nobody is logged in. */
    public static UserSession get() {
        UserSession s = current;
        if (s == null) {
            throw new IllegalStateException("No user is logged in");
        }
        return s;
    }

    public long getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public String getUsername() {
        return username;
    }

    public UserRole getRole() {
        return role;
    }

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }

    /** Permission for a module. A module with no row in the table gets no permission. */
    public ModulePermission permission(AppModule module) {
        if (isAdmin()) {
            return ModulePermission.ALL;
        }
        return permissions.getOrDefault(module, ModulePermission.NONE);
    }

    public boolean can(AppModule module, Action action) {
        return permission(module).allows(action);
    }
}
