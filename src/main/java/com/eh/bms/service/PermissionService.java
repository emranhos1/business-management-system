package com.eh.bms.service;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.Permission;
import com.eh.bms.model.UserRole;
import com.eh.bms.repository.PermissionRepository;
import com.eh.bms.util.TransactionHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * What each role may do in each module (17.3). The admin role can always do everything (see
 * {@link UserSession}), so only the other roles are edited; the user management module is the admin's alone.
 * A change takes effect when a user of that role logs in again (permissions are read at login).
 */
public class PermissionService {

    /** One module's permission for the role being edited. */
    public record Row(AppModule module, boolean view, boolean create, boolean edit, boolean delete) {
    }

    private final PermissionRepository repository = new PermissionRepository();
    private final AuditService auditService = new AuditService();

    /** Modules a role's permissions can be set for: every module except user management. */
    public static List<AppModule> editableModules() {
        List<AppModule> modules = new ArrayList<>();
        for (AppModule m : AppModule.values()) {
            if (m != AppModule.USER) {
                modules.add(m);
            }
        }
        return modules;
    }

    /** Permissions of a role for each editable module, in menu order; a module with no row gets none. */
    public List<Row> forRole(UserRole role) {
        requireAdmin();
        requireEditable(role);
        return TransactionHelper.inTransaction(session -> {
            List<Row> rows = new ArrayList<>();
            for (AppModule module : editableModules()) {
                Optional<Permission> p = repository.find(session, role, module);
                rows.add(p.map(PermissionService::toRow).orElse(new Row(module, false, false, false, false)));
            }
            return rows;
        });
    }

    /** Saves the role's permissions; an audit row lists what changed. Create, edit or delete without view is made view too. */
    public void save(UserRole role, List<Row> rows) {
        requireAdmin();
        requireEditable(role);
        TransactionHelper.runInTransaction(session -> {
            StringJoiner before = new StringJoiner("; ");
            StringJoiner after = new StringJoiner("; ");
            for (Row row : rows) {
                if (row.module() == AppModule.USER) {
                    continue;
                }
                boolean view = row.view() || row.create() || row.edit() || row.delete();
                Permission p = repository.find(session, role, row.module()).orElse(null);
                if (p == null) {
                    p = new Permission();
                    p.setRole(role);
                    p.setModule(row.module());
                    repository.save(session, p);
                }
                Row old = toRow(p);
                Row now = new Row(row.module(), view, row.create(), row.edit(), row.delete());
                if (!old.equals(now)) {
                    before.add(describe(old));
                    after.add(describe(now));
                }
                p.setCanView(now.view());
                p.setCanCreate(now.create());
                p.setCanEdit(now.edit());
                p.setCanDelete(now.delete());
            }
            if (before.length() > 0) {
                auditService.updated(session, "PERMISSION", null, role + ": " + before, role + ": " + after);
            }
        });
    }

    private static Row toRow(Permission p) {
        return new Row(p.getModule(), p.isCanView(), p.isCanCreate(), p.isCanEdit(), p.isCanDelete());
    }

    /** "SALE view,create" ; a module with nothing shows "none". */
    private static String describe(Row r) {
        StringJoiner what = new StringJoiner(",");
        if (r.view()) {
            what.add("view");
        }
        if (r.create()) {
            what.add("create");
        }
        if (r.edit()) {
            what.add("edit");
        }
        if (r.delete()) {
            what.add("delete");
        }
        return r.module() + " " + (what.length() == 0 ? "none" : what);
    }

    private static void requireEditable(UserRole role) {
        if (role == UserRole.ADMIN || role == UserRole.SUPER_ADMIN) {
            throw new IllegalArgumentException("The admin role always has every permission");
        }
    }

    private static void requireAdmin() {
        if (!UserSession.isLoggedIn() || !UserSession.get().isAdmin()) {
            throw new IllegalStateException("Only an admin may change permissions");
        }
    }
}
