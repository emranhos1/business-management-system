package com.eh.bms.service;

import com.eh.bms.config.AppConfig;
import com.eh.bms.model.AppModule;
import com.eh.bms.model.AuditAction;
import com.eh.bms.model.Permission;
import com.eh.bms.model.User;
import com.eh.bms.model.UserStatus;
import com.eh.bms.repository.PermissionRepository;
import com.eh.bms.repository.UserRepository;
import com.eh.bms.util.TransactionHelper;
import org.mindrot.jbcrypt.BCrypt;

import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/** Checks login credentials and starts the {@link UserSession}. */
public class AuthService {

    /** Compared against when the username does not exist, so both cases take the same time. */
    private static final String DUMMY_HASH = BCrypt.hashpw("dummy-password", BCrypt.gensalt());

    public enum Status {
        SUCCESS,
        /** Unknown username or wrong password; never tell the user which one. */
        INVALID_CREDENTIALS,
        /** Right password but the user is deactivated. */
        INACTIVE,
        /**
         * Right password, but it is one somebody else chose: no session is started until the user picks their
         * own ({@link #setFirstPassword}, then {@link #startSession}).
         */
        MUST_CHANGE_PASSWORD
    }

    /** {@code user} is set when {@code status} is {@link Status#SUCCESS} or {@link Status#MUST_CHANGE_PASSWORD}. */
    public record LoginResult(Status status, User user) {
    }

    public enum PasswordStatus {
        DONE,
        /** The new password is the same as the one in use. */
        SAME_AS_OLD,
        /** The user does not have to change the password (so this call is not allowed), or does not exist. */
        NOT_ALLOWED
    }

    /** The only profile where {@link #devAutoLogin} works. */
    private static final String DEV_PROFILE = "dev";
    private static final Logger LOG = Logger.getLogger(AuthService.class.getName());

    private final UserRepository userRepository = new UserRepository();
    private final PermissionRepository permissionRepository = new PermissionRepository();
    private final AuditService auditService = new AuditService();

    public LoginResult login(String username, String password) {
        Optional<User> found = TransactionHelper.inTransaction(
                session -> userRepository.findByUsername(session, username.trim()));

        if (found.isEmpty()) {
            BCrypt.checkpw(password, DUMMY_HASH);
            return new LoginResult(Status.INVALID_CREDENTIALS, null);
        }
        User user = found.get();
        if (!BCrypt.checkpw(password, user.getPasswordHash())) {
            return new LoginResult(Status.INVALID_CREDENTIALS, null);
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            return new LoginResult(Status.INACTIVE, null);
        }
        if (user.isMustChangePassword()) {
            return new LoginResult(Status.MUST_CHANGE_PASSWORD, user);
        }
        startSession(user);
        return new LoginResult(Status.SUCCESS, user);
    }

    /** Starts the session of a user who passed the login checks (and, if needed, picked their own password). */
    public void startSession(User user) {
        List<Permission> permissions = TransactionHelper.inTransaction(
                session -> permissionRepository.findByRole(session, user.getRole()));
        UserSession.start(user, permissions);
    }

    /**
     * The user's own password at the first login (or after the admin set one): replaces the one somebody else
     * chose and clears the "must change" mark. Only for a user who has to change it; the new password may not be
     * the old one.
     */
    public PasswordStatus setFirstPassword(long userId, String newPassword) {
        return TransactionHelper.inTransaction(session -> {
            User u = userRepository.findById(session, userId).orElse(null);
            if (u == null || !u.isMustChangePassword() || u.getStatus() != UserStatus.ACTIVE) {
                return PasswordStatus.NOT_ALLOWED;
            }
            if (BCrypt.checkpw(newPassword, u.getPasswordHash())) {
                return PasswordStatus.SAME_AS_OLD;
            }
            u.setPasswordHash(BCrypt.hashpw(newPassword, BCrypt.gensalt()));
            u.setMustChangePassword(false);
            // Nobody is logged in yet, so the audit row names the user explicitly
            auditService.log(session, AuditAction.UPDATE, AppModule.USER.name(), u.getId(), null,
                    "username=" + u.getUsername() + ", password changed by the user (first login)", u.getId());
            return PasswordStatus.DONE;
        });
    }

    /**
     * Development only: logs in as {@code dev.auto-login} (a username in application-dev.yml) without a
     * password, so the app opens straight to the dashboard. Works only when the active profile is
     * {@value #DEV_PROFILE}; stage and prod always show the login screen. False (login screen) when the key is
     * blank or the user is missing or inactive.
     */
    public boolean devAutoLogin() {
        AppConfig config = AppConfig.get();
        if (!DEV_PROFILE.equals(config.getProfile())) {
            return false;
        }
        String username = config.getString("dev.auto-login", "").trim();
        if (username.isEmpty()) {
            return false;
        }
        Optional<User> found = TransactionHelper.inTransaction(session -> userRepository.findByUsername(session, username));
        if (found.isEmpty() || found.get().getStatus() != UserStatus.ACTIVE) {
            LOG.warning("dev.auto-login user not found or inactive: " + username + "; showing the login screen");
            return false;
        }
        startSession(found.get());
        LOG.warning("DEV profile: logged in as '" + username + "' without the login screen (dev.auto-login)");
        return true;
    }
}
