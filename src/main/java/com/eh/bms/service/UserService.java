package com.eh.bms.service;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.User;
import com.eh.bms.model.UserRole;
import com.eh.bms.model.UserStatus;
import com.eh.bms.repository.UserRepository;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.TransactionHelper;
import org.hibernate.Session;
import org.mindrot.jbcrypt.BCrypt;

import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Login users (17.1, 17.2). Only an admin manages users. Rules:
 * <ul>
 * <li>The username is fixed once the user exists (the startup admin check finds the admin by it).</li>
 * <li>A user cannot change their own role or switch themselves off, and the last active admin can never be
 * switched off, demoted or deleted, so nobody can be locked out of the app.</li>
 * <li>A user who appears in the audit log or on a salary payment is never deleted, only made inactive.</li>
 * <li>A password is stored as a BCrypt hash and never written to the audit log.</li>
 * </ul>
 */
public class UserService {

    public enum SaveResult {
        SAVED, DUPLICATE_USERNAME, OWN_ROLE, LAST_ADMIN, NOT_FOUND
    }

    public enum StatusResult {
        DONE, SELF, LAST_ADMIN, NOT_FOUND
    }

    public enum DeleteResult {
        DELETED, IN_USE, SELF, LAST_ADMIN, NOT_FOUND
    }

    /**
     * Values from the form. {@code id} is null for a new user. {@code password}: required for a new user; for an
     * existing one blank = keep the old one. {@code username} is ignored for an existing user.
     */
    public record UserData(Long id, String name, String username, String password, UserRole role) {
    }

    private final UserRepository repository = new UserRepository();
    private final AuditService auditService = new AuditService();

    public PageResult<User> page(UserStatus status, String search, int offset, int limit) {
        requireAdmin();
        return TransactionHelper.inTransaction(session -> new PageResult<>(
                repository.page(session, status, search, offset, limit), repository.count(session, status, search)));
    }

    /** Every user by name (the audit log's user filter). */
    public List<User> all() {
        requireAdmin();
        return TransactionHelper.inTransaction(repository::all);
    }

    public SaveResult save(UserData data) {
        requireAdmin();
        if (data.role() == UserRole.SUPER_ADMIN) {
            throw new IllegalArgumentException("The support role cannot be given to a user");
        }
        return TransactionHelper.inTransaction(session -> {
            if (data.id() == null) {
                String username = data.username().trim();
                if (repository.existsByUsername(session, username, null)) {
                    return SaveResult.DUPLICATE_USERNAME;
                }
                User u = new User();
                u.setName(data.name().trim());
                u.setUsername(username);
                u.setPasswordHash(BCrypt.hashpw(data.password(), BCrypt.gensalt()));
                u.setRole(data.role());
                u.setStatus(UserStatus.ACTIVE);
                // The admin chose this password, so the user picks their own at the first login
                u.setMustChangePassword(true);
                repository.save(session, u);
                auditService.created(session, AppModule.USER, u.getId(), describe(u));
                return SaveResult.SAVED;
            }

            User u = find(session, data.id()).orElse(null);
            if (u == null) {
                return SaveResult.NOT_FOUND;
            }
            if (data.role() != u.getRole()) {
                if (u.getId() == UserSession.get().getUserId()) {
                    return SaveResult.OWN_ROLE;
                }
                if (isLastActiveAdmin(session, u)) {
                    return SaveResult.LAST_ADMIN;
                }
            }
            String old = describe(u);
            u.setName(data.name().trim());
            u.setRole(data.role());
            boolean newPassword = data.password() != null && !data.password().isEmpty();
            boolean resetForOther = newPassword && u.getId() != UserSession.get().getUserId();
            if (newPassword) {
                u.setPasswordHash(BCrypt.hashpw(data.password(), BCrypt.gensalt()));
                // A password the admin sets for somebody else is temporary; one set for yourself is your own
                u.setMustChangePassword(resetForOther);
            }
            String now = describe(u) + (newPassword ? ", password changed" + (resetForOther ? " (must change)" : "") : "");
            if (newPassword || !old.equals(describe(u))) {
                auditService.updated(session, AppModule.USER, u.getId(), old, now);
            }
            return SaveResult.SAVED;
        });
    }

    /** Switches a user on or off; an inactive user cannot log in (a session already open runs until logout). */
    public StatusResult setStatus(long id, UserStatus status) {
        requireAdmin();
        return TransactionHelper.inTransaction(session -> {
            User u = find(session, id).orElse(null);
            if (u == null) {
                return StatusResult.NOT_FOUND;
            }
            if (u.getStatus() == status) {
                return StatusResult.DONE;
            }
            if (status == UserStatus.INACTIVE) {
                if (u.getId() == UserSession.get().getUserId()) {
                    return StatusResult.SELF;
                }
                if (isLastActiveAdmin(session, u)) {
                    return StatusResult.LAST_ADMIN;
                }
            }
            String old = describe(u);
            u.setStatus(status);
            auditService.updated(session, AppModule.USER, u.getId(), old, describe(u));
            return StatusResult.DONE;
        });
    }

    /** True if {@link #delete} would delete the user: not yourself, not the last admin, no history. */
    public boolean canDelete(long id) {
        requireAdmin();
        return TransactionHelper.inTransaction(session -> {
            User u = find(session, id).orElse(null);
            return u != null && u.getId() != UserSession.get().getUserId() && !isLastActiveAdmin(session, u)
                    && !repository.hasHistory(session, id);
        });
    }

    /** Deletes a user added by mistake: one with no history (see the class notes). */
    public DeleteResult delete(long id) {
        requireAdmin();
        return TransactionHelper.inTransaction(session -> {
            User u = find(session, id).orElse(null);
            if (u == null) {
                return DeleteResult.NOT_FOUND;
            }
            if (u.getId() == UserSession.get().getUserId()) {
                return DeleteResult.SELF;
            }
            if (isLastActiveAdmin(session, u)) {
                return DeleteResult.LAST_ADMIN;
            }
            if (repository.hasHistory(session, id)) {
                return DeleteResult.IN_USE;
            }
            String old = describe(u);
            repository.delete(session, u);
            auditService.deleted(session, AppModule.USER, id, old);
            return DeleteResult.DELETED;
        });
    }

    /** The user with this id; the support team's hidden login is never found, so the app cannot change it. */
    private Optional<User> find(Session session, long id) {
        return repository.findById(session, id).filter(u -> u.getRole() != UserRole.SUPER_ADMIN);
    }

    private boolean isLastActiveAdmin(Session session, User u) {
        return u.getRole() == UserRole.ADMIN && u.getStatus() == UserStatus.ACTIVE
                && repository.countActiveAdmins(session) <= 1;
    }

    /** User management is for the admin only, whatever a screen shows. */
    private static void requireAdmin() {
        if (!UserSession.isLoggedIn() || !UserSession.get().isAdmin()) {
            throw new IllegalStateException("Only an admin may manage users");
        }
    }

    private static String describe(User u) {
        return new StringJoiner(", ")
                .add("name=" + u.getName())
                .add("username=" + u.getUsername())
                .add("role=" + u.getRole())
                .add("status=" + u.getStatus())
                .toString();
    }
}
