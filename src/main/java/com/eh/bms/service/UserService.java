package com.eh.bms.service;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.SalaryType;
import com.eh.bms.model.User;
import com.eh.bms.model.UserRole;
import com.eh.bms.model.UserStatus;
import com.eh.bms.repository.UserRepository;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.TransactionHelper;
import org.hibernate.Session;
import org.mindrot.jbcrypt.BCrypt;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Login users (17.1, 17.2). Only an admin manages users. Rules:
 * <ul>
 * <li>The username is fixed once the user exists (the startup admin check finds the admin by it).</li>
 * <li>A user cannot change their own role or switch themselves off, and the last active admin can never be
 * switched off, demoted or deleted, so nobody can be locked out of the app.</li>
 * <li>A user who appears in the audit log or has salary rows is never deleted, only made inactive; a user with
 * salary due or advance not earned yet cannot be made inactive.</li>
 * <li>Every user is staff (N12): mobile, salary type and salary are required; NID is unique when given.</li>
 * <li>A password is stored as a BCrypt hash and never written to the audit log.</li>
 * </ul>
 */
public class UserService {

    public enum SaveResult {
        SAVED, DUPLICATE_USERNAME, DUPLICATE_NID, OWN_ROLE, LAST_ADMIN, NOT_FOUND
    }

    public enum StatusResult {
        DONE, SELF, LAST_ADMIN, NOT_FOUND,
        /** Inactive refused: salary is due on a worked-out month ({@code amount}). */
        HAS_DUE,
        /** Inactive refused: advance paid and not earned yet ({@code amount}). */
        HAS_ADVANCE
    }

    /** {@code amount}: the open money for HAS_DUE / HAS_ADVANCE, else null. */
    public record StatusOutcome(StatusResult status, BigDecimal amount) {
    }

    public enum DeleteResult {
        DELETED, IN_USE, SELF, LAST_ADMIN, NOT_FOUND
    }

    /**
     * Values from the form. {@code id} is null for a new user. {@code password}: required for a new user; for an
     * existing one blank = keep the old one. {@code username} is ignored for an existing user. Staff details (N12):
     * mobile (11 digits), salary type and salary (above 0) are required; NID, address and join date may be blank.
     */
    public record UserData(Long id, String name, String username, String password, UserRole role, String phone,
                           String nid, String address, LocalDate joinDate, SalaryType salaryType,
                           BigDecimal baseSalary) {
    }

    public static final int NAME_MAX = 150;
    public static final int PHONE_LENGTH = 11;
    public static final int NID_MAX = 30;
    public static final int ADDRESS_MAX = 255;

    private final UserRepository repository = new UserRepository();
    private final AuditService auditService = new AuditService();
    private final SalaryService salaryService = new SalaryService();

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
        validate(data);
        String nid = blankToNull(data.nid());
        return TransactionHelper.inTransaction(session -> {
            if (nid != null && repository.existsByNid(session, nid, data.id())) {
                return SaveResult.DUPLICATE_NID;
            }
            if (data.id() == null) {
                String username = data.username().trim();
                if (repository.existsByUsername(session, username, null)) {
                    return SaveResult.DUPLICATE_USERNAME;
                }
                User u = new User();
                u.setName(data.name().trim());
                fillStaff(u, data, nid);
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
            fillStaff(u, data, nid);
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

    /**
     * Switches a user on or off; an inactive user cannot log in (a session already open runs until logout) and
     * leaves the salary sheet. Off is refused while salary is due or advance is not earned yet.
     */
    public StatusOutcome setStatus(long id, UserStatus status) {
        requireAdmin();
        return TransactionHelper.inTransaction(session -> {
            User u = find(session, id).orElse(null);
            if (u == null) {
                return new StatusOutcome(StatusResult.NOT_FOUND, null);
            }
            if (u.getStatus() == status) {
                return new StatusOutcome(StatusResult.DONE, null);
            }
            if (status == UserStatus.INACTIVE) {
                if (u.getId() == UserSession.get().getUserId()) {
                    return new StatusOutcome(StatusResult.SELF, null);
                }
                if (isLastActiveAdmin(session, u)) {
                    return new StatusOutcome(StatusResult.LAST_ADMIN, null);
                }
                repository.lock(session, id);
                SalaryService.OpenMoney open = salaryService.openMoney(session, id);
                if (open.due().signum() > 0) {
                    return new StatusOutcome(StatusResult.HAS_DUE, open.due());
                }
                if (open.advance().signum() > 0) {
                    return new StatusOutcome(StatusResult.HAS_ADVANCE, open.advance());
                }
            }
            String old = describe(u);
            u.setStatus(status);
            auditService.updated(session, AppModule.USER, u.getId(), old, describe(u));
            return new StatusOutcome(StatusResult.DONE, null);
        });
    }

    /** Approves a registration (N1): the admin picks its role and it can log in with its own password. */
    public StatusResult approve(long id, UserRole role) {
        requireAdmin();
        if (role == UserRole.SUPER_ADMIN) {
            throw new IllegalArgumentException("The support role cannot be given to a user");
        }
        return TransactionHelper.inTransaction(session -> {
            User u = find(session, id).filter(p -> p.getStatus() == UserStatus.PENDING).orElse(null);
            if (u == null) {
                return StatusResult.NOT_FOUND;
            }
            String old = describe(u);
            u.setRole(role);
            u.setStatus(UserStatus.ACTIVE);
            auditService.updated(session, AppModule.USER, u.getId(), old, describe(u) + ", registration approved");
            return StatusResult.DONE;
        });
    }

    /** Rejects a registration (N1): it is deleted, so the username is free again. */
    public DeleteResult reject(long id) {
        requireAdmin();
        return TransactionHelper.inTransaction(session -> {
            User u = find(session, id).filter(p -> p.getStatus() == UserStatus.PENDING).orElse(null);
            if (u == null) {
                return DeleteResult.NOT_FOUND;
            }
            String old = describe(u);
            repository.delete(session, u);
            auditService.deleted(session, AppModule.USER, id, old + ", registration rejected");
            return DeleteResult.DELETED;
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

    private static void validate(UserData data) {
        if (data.name() == null || data.name().isBlank() || data.name().trim().length() > NAME_MAX) {
            throw new IllegalArgumentException("Name is required (at most " + NAME_MAX + " characters)");
        }
        if (data.phone() == null || !data.phone().trim().matches("\\d{" + PHONE_LENGTH + "}")) {
            throw new IllegalArgumentException("Mobile number must be " + PHONE_LENGTH + " digits");
        }
        if (data.salaryType() == null) {
            throw new IllegalArgumentException("Salary type is required");
        }
        if (data.baseSalary() == null || data.baseSalary().signum() <= 0) {
            throw new IllegalArgumentException("Salary must be more than 0");
        }
        String nid = blankToNull(data.nid());
        if (nid != null && nid.length() > NID_MAX) {
            throw new IllegalArgumentException("NID is too long");
        }
    }

    private static void fillStaff(User u, UserData data, String nid) {
        u.setPhone(data.phone().trim());
        u.setNid(nid);
        String address = blankToNull(data.address());
        u.setAddress(address == null || address.length() <= ADDRESS_MAX ? address : address.substring(0, ADDRESS_MAX));
        u.setJoinDate(data.joinDate());
        u.setSalaryType(data.salaryType());
        u.setBaseSalary(data.baseSalary());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String describe(User u) {
        return new StringJoiner(", ")
                .add("name=" + u.getName())
                .add("username=" + u.getUsername())
                .add("role=" + u.getRole())
                .add("status=" + u.getStatus())
                .add("phone=" + u.getPhone())
                .add("nid=" + u.getNid())
                .add("address=" + u.getAddress())
                .add("joinDate=" + u.getJoinDate())
                .add("salaryType=" + u.getSalaryType())
                .add("baseSalary=" + (u.getBaseSalary() == null ? null : u.getBaseSalary().toPlainString()))
                .toString();
    }
}
